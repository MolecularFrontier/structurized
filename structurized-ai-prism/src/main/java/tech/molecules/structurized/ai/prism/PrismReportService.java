package tech.molecules.structurized.ai.prism;

import tech.molecules.structurized.ai.model.ChemOperationException;
import tech.molecules.structurized.prism.engine.PrismSession;
import tech.molecules.structurized.prism.engine.PrismViewRecord;
import tech.molecules.structurized.prism.report.EmbeddedPrismViewReportBlock;
import tech.molecules.structurized.prism.report.PrismReportDiagnostic;
import tech.molecules.structurized.prism.report.PrismReportDocument;
import tech.molecules.structurized.prism.report.PrismReportParser;
import tech.molecules.structurized.prism.report.PrismReportSchema;
import tech.molecules.structurized.prism.report.PrismReportSeverity;
import tech.molecules.structurized.prism.report.PrismReportValidator;
import tech.molecules.structurized.prism.report.PrismReportViewSpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class PrismReportService {
    private static final int MAX_SOURCE_CHARACTERS = 2_000_000;

    private final PrismSessionRegistry sessions;

    public PrismReportService(PrismSessionRegistry sessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    public PrismReportSchema schema() {
        return PrismReportSchema.current();
    }

    public PrismReportValidationSummary validate(String sessionId, PrismReportSource reportSource) {
        ManagedPrismSession managed = sessions.require(sessionId);
        LoadedSource loaded = load(reportSource);
        return managed.callAs(ManagedPrismSessionChangeOrigin.MCP,
                () -> analyze(managed, loaded).summary());
    }

    public PrismReportPublicationResult publish(String sessionId, PrismReportSource reportSource) {
        ManagedPrismSession managed = sessions.require(sessionId);
        LoadedSource loaded = load(reportSource);
        return managed.callAs(ManagedPrismSessionChangeOrigin.MCP, () -> publish(managed, loaded));
    }

    public PrismReportSaveResult save(String sessionId, String source, Path outputPath) {
        ManagedPrismSession managed = sessions.require(sessionId);
        Path output = reportPath(outputPath);
        LoadedSource loaded = inline(source);
        Analysis analysis = managed.callAs(ManagedPrismSessionChangeOrigin.MCP,
                () -> analyze(managed, loaded));
        if (!analysis.summary().valid()) {
            return new PrismReportSaveResult(false, managed.sessionId(), output.toString(), 0,
                    analysis.summary());
        }
        writeNewReport(output, loaded.source());
        return new PrismReportSaveResult(true, managed.sessionId(), output.toString(),
                loaded.source().getBytes(StandardCharsets.UTF_8).length, analysis.summary());
    }

    private PrismReportPublicationResult publish(ManagedPrismSession managed, LoadedSource loaded) {
        Analysis analysis = analyze(managed, loaded);
        if (!analysis.summary().valid()) {
            return new PrismReportPublicationResult(false, managed.sessionId(), null,
                    analysis.document().metadata().title(), analysis.summary());
        }

        PrismSession workspace = managed.workspace();
        String baseId = "report:" + reportId(analysis.document(), loaded);
        String viewId = uniqueViewId(workspace, baseId);
        PrismReportViewSpec specification = new PrismReportViewSpec(
                viewId, analysis.document().metadata().title(), analysis.document());
        Instant now = Instant.now();
        LinkedHashMap<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("reportSource", loaded.label());
        provenance.put("datasetSource", managed.sourcePath().toAbsolutePath().normalize().toString());
        provenance.put("validatedAt", now.toString());
        provenance.put("publishedBy", "Structurized MCP");
        workspace.addView(new PrismViewRecord(viewId, specification.viewType(), specification.title(),
                specification, now, Map.copyOf(provenance)));
        return new PrismReportPublicationResult(true, managed.sessionId(), viewId,
                specification.title(), analysis.summary());
    }

    private static Analysis analyze(ManagedPrismSession managed, LoadedSource loaded) {
        PrismReportDocument parsed = new PrismReportParser().parse(loaded.source());
        List<PrismReportDiagnostic> diagnostics =
                new PrismReportValidator().validate(parsed, managed.workspace());
        long errors = diagnostics.stream()
                .filter(item -> item.severity() == PrismReportSeverity.ERROR).count();
        long warnings = diagnostics.stream()
                .filter(item -> item.severity() == PrismReportSeverity.WARNING).count();
        LinkedHashSet<String> columns = new LinkedHashSet<>();
        LinkedHashSet<String> rowSets = new LinkedHashSet<>();
        int prismBlocks = 0;
        for (var block : parsed.blocks()) {
            if (block instanceof EmbeddedPrismViewReportBlock embedded) {
                prismBlocks++;
                columns.addAll(embedded.specification().referencedColumnIds());
                rowSets.addAll(embedded.specification().referencedRowSetIds());
            }
        }
        PrismReportDocument validated = new PrismReportDocument(
                parsed.metadata(), parsed.blocks(), parsed.source(), diagnostics);
        PrismReportValidationSummary summary = new PrismReportValidationSummary(
                managed.sessionId(), loaded.label(), errors == 0, Math.toIntExact(errors),
                Math.toIntExact(warnings), parsed.metadata(), prismBlocks, List.copyOf(columns),
                List.copyOf(rowSets), diagnostics);
        return new Analysis(validated, summary);
    }

    private static LoadedSource load(PrismReportSource reportSource) {
        Objects.requireNonNull(reportSource, "reportSource");
        if (reportSource.path() == null) return inline(reportSource.source());
        Path path = reportPath(reportSource.path());
        if (!Files.isRegularFile(path)) {
            throw new ChemOperationException("prism_report_not_found",
                    "Prism report does not exist or is not a regular file: " + path);
        }
        try {
            return checked(Files.readString(path), path.toString(), path);
        } catch (IOException exception) {
            throw new ChemOperationException("prism_report_read_error",
                    "Could not read Prism report: " + path, exception);
        }
    }

    private static LoadedSource inline(String source) {
        if (source == null) {
            throw new ChemOperationException("invalid_prism_report_source",
                    "Prism report source must not be null.");
        }
        return checked(source, "inline", null);
    }

    private static LoadedSource checked(String source, String label, Path path) {
        if (source.length() > MAX_SOURCE_CHARACTERS) {
            throw new ChemOperationException("prism_report_too_large",
                    "Prism report exceeds the limit of " + MAX_SOURCE_CHARACTERS + " characters.");
        }
        return new LoadedSource(source, label, path);
    }

    private static Path reportPath(Path path) {
        if (path == null) {
            throw new ChemOperationException("invalid_prism_report_path",
                    "Prism report path must not be null.");
        }
        Path normalized = path.toAbsolutePath().normalize();
        Path fileName = normalized.getFileName();
        if (fileName == null || !fileName.toString().toLowerCase().endsWith(".prism.md")) {
            throw new ChemOperationException("invalid_prism_report_path",
                    "Prism report filename must end in .prism.md: " + normalized);
        }
        return normalized;
    }

    private static void writeNewReport(Path output, String source) {
        Path parent = output.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new ChemOperationException("prism_report_directory_not_found",
                    "Prism report output directory does not exist: " + parent);
        }
        if (Files.exists(output)) {
            throw new ChemOperationException("prism_report_exists",
                    "Refusing to overwrite existing Prism report: " + output);
        }
        Path temporary = null;
        try {
            temporary = Files.createTempFile(parent, "." + output.getFileName() + ".", ".tmp");
            Files.writeString(temporary, source, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            Files.move(temporary, output);
        } catch (FileAlreadyExistsException exception) {
            throw new ChemOperationException("prism_report_exists",
                    "Refusing to overwrite existing Prism report: " + output, exception);
        } catch (IOException exception) {
            throw new ChemOperationException("prism_report_write_error",
                    "Could not save Prism report: " + output, exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String reportId(PrismReportDocument document, LoadedSource loaded) {
        if (document.metadata().id() != null) return slug(document.metadata().id());
        if (loaded.path() != null) {
            String filename = loaded.path().getFileName().toString();
            return slug(filename.substring(0, filename.length() - ".prism.md".length()));
        }
        return slug(document.metadata().title());
    }

    private static String slug(String value) {
        if (value == null) return "report";
        String slug = value.toLowerCase().replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug.isBlank() ? "report" : slug;
    }

    private static String uniqueViewId(PrismSession session, String base) {
        String candidate = base;
        int suffix = 2;
        while (containsView(session, candidate)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    private static boolean containsView(PrismSession session, String viewId) {
        return session.views().stream().anyMatch(view -> view.id().equals(viewId));
    }

    private record LoadedSource(String source, String label, Path path) {
    }

    private record Analysis(PrismReportDocument document, PrismReportValidationSummary summary) {
    }
}
