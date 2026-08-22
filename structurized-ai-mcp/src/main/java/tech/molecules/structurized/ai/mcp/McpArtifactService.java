package tech.molecules.structurized.ai.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import tech.molecules.structurized.ai.model.ChemOperationException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Session-scoped managed artifacts for large MCP tool outputs.
 */
final class McpArtifactService {
    private static final String ARTIFACT_DIR_PROPERTY = "structurized.mcp.artifactDir";

    private final ObjectMapper mapper;
    private final Path baseDirectory;
    private final Map<String, ArtifactRecord> artifacts = new LinkedHashMap<>();
    private int nextArtifactIndex = 1;

    McpArtifactService(ObjectMapper mapper) {
        this(mapper, defaultBaseDirectory());
    }

    McpArtifactService(ObjectMapper mapper, Path baseDirectory) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        try {
            Files.createDirectories(Objects.requireNonNull(baseDirectory, "baseDirectory"));
            this.baseDirectory = baseDirectory.toRealPath();
        } catch (IOException e) {
            throw new ChemOperationException("artifact_directory_error", "Could not initialize MCP artifact directory: " + e.getMessage(), e);
        }
    }

    synchronized ArtifactRecord writeJson(String sourceTool, String outputName, boolean overwrite, Object payload, Integer rowCount) {
        String artifactId = generatedArtifactId();
        Path relative = relativePath(sourceTool, outputName, "json");
        Path target = resolveTarget(relative, overwrite);
        try {
            Files.createDirectories(target.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), payload);
            long byteSize = Files.size(target);
            ArtifactRecord record = new ArtifactRecord(
                    artifactId,
                    target.toAbsolutePath().normalize().toString(),
                    portableRelativePath(target),
                    "json",
                    "application/json",
                    byteSize,
                    Instant.now().toString(),
                    sourceTool,
                    rowCount
            );
            artifacts.put(artifactId, record);
            return record;
        } catch (IOException e) {
            throw new ChemOperationException("artifact_write_error", "Could not write MCP artifact: " + e.getMessage(), e);
        }
    }

    synchronized ArtifactRecord writeText(
            String sourceTool,
            String outputName,
            boolean overwrite,
            String format,
            String contentType,
            String payload,
            Integer rowCount
    ) {
        String normalizedFormat = normalizeTextFormat(format);
        String artifactId = generatedArtifactId();
        Path relative = relativePath(sourceTool, outputName, normalizedFormat);
        Path target = resolveTarget(relative, overwrite);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, payload == null ? "" : payload, StandardCharsets.UTF_8);
            long byteSize = Files.size(target);
            ArtifactRecord record = new ArtifactRecord(
                    artifactId,
                    target.toAbsolutePath().normalize().toString(),
                    portableRelativePath(target),
                    normalizedFormat,
                    contentType == null || contentType.isBlank() ? "text/plain" : contentType,
                    byteSize,
                    Instant.now().toString(),
                    sourceTool,
                    rowCount
            );
            artifacts.put(artifactId, record);
            return record;
        } catch (IOException e) {
            throw new ChemOperationException("artifact_write_error", "Could not write MCP artifact: " + e.getMessage(), e);
        }
    }

    synchronized List<ArtifactRecord> listArtifacts() {
        return List.copyOf(artifacts.values());
    }

    synchronized ArtifactRecord getArtifact(String artifactId) {
        ArtifactRecord record = artifacts.get(normalizeArtifactId(artifactId));
        if (record == null) {
            throw new ChemOperationException("artifact_not_found", "Artifact " + artifactId + " does not exist.");
        }
        return record;
    }

    Path baseDirectory() {
        return baseDirectory;
    }

    private String portableRelativePath(Path target) {
        return baseDirectory.relativize(target).toString().replace('\\', '/');
    }

    private Path relativePath(String sourceTool, String outputName, String format) {
        if (outputName == null || outputName.isBlank()) {
            return Path.of(defaultFileName(sourceTool, format));
        }
        if (!"json".equals(format) && !"tsv".equals(format) && !"txt".equals(format) && !"csv".equals(format)) {
            throw new ChemOperationException("unsupported_artifact_format", "Artifact format must be json, tsv, csv, or txt.");
        }
        Path relative = Path.of(outputName.trim());
        validateRelativePath(relative);
        return relative;
    }

    private Path resolveTarget(Path relative, boolean overwrite) {
        Path candidate = baseDirectory.resolve(relative).normalize();
        if (!candidate.startsWith(baseDirectory)) {
            throw new ChemOperationException("invalid_artifact_path", "output_name must stay inside the managed artifact directory.");
        }
        rejectSymlinkEscape(candidate);
        if (overwrite) {
            return candidate;
        }
        return availablePath(candidate);
    }

    private Path availablePath(Path candidate) {
        if (!Files.exists(candidate)) {
            return candidate;
        }
        String fileName = candidate.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        String extension = dot > 0 ? fileName.substring(dot) : "";
        Path parent = candidate.getParent();
        int suffix = 2;
        while (true) {
            Path suffixed = parent.resolve(stem + "_" + suffix + extension).normalize();
            if (!suffixed.startsWith(baseDirectory)) {
                throw new ChemOperationException("invalid_artifact_path", "output_name must stay inside the managed artifact directory.");
            }
            rejectSymlinkEscape(suffixed);
            if (!Files.exists(suffixed)) {
                return suffixed;
            }
            suffix++;
        }
    }

    private void rejectSymlinkEscape(Path target) {
        Path current = baseDirectory;
        Path relative = baseDirectory.relativize(target);
        int count = relative.getNameCount();
        for (int i = 0; i < count; i++) {
            current = current.resolve(relative.getName(i));
            if (Files.isSymbolicLink(current)) {
                throw new ChemOperationException("invalid_artifact_path", "output_name must not traverse symbolic links.");
            }
        }
    }

    private static void validateRelativePath(Path relative) {
        if (relative.isAbsolute()) {
            throw new ChemOperationException("invalid_artifact_path", "output_name must be a relative path.");
        }
        if (relative.getNameCount() == 0) {
            throw new ChemOperationException("invalid_artifact_path", "output_name must not be blank.");
        }
        List<String> problems = new ArrayList<>();
        for (Path part : relative) {
            String text = part.toString();
            if (text.isBlank() || ".".equals(text) || "..".equals(text)) {
                problems.add(text);
            }
        }
        if (!problems.isEmpty()) {
            throw new ChemOperationException("invalid_artifact_path", "output_name must not contain blank, '.', or '..' path segments.");
        }
    }

    private String defaultFileName(String sourceTool, String format) {
        String safeTool = sourceTool == null || sourceTool.isBlank()
                ? "artifact"
                : sourceTool.replaceAll("[^A-Za-z0-9_-]", "_");
        return safeTool + "_" + Integer.toString(nextArtifactIndex, 36) + "." + format;
    }

    private String generatedArtifactId() {
        String id;
        do {
            id = "artifact_" + Integer.toString(nextArtifactIndex++, 36) + "_" + UUID.randomUUID().toString().substring(0, 8);
        } while (artifacts.containsKey(id));
        return id;
    }

    private static String normalizeArtifactId(String artifactId) {
        if (artifactId == null || artifactId.isBlank()) {
            throw new ChemOperationException("invalid_arguments", "Missing required argument: artifact_id");
        }
        return artifactId.trim();
    }

    private static String normalizeTextFormat(String format) {
        String normalized = format == null || format.isBlank() ? "txt" : format.trim().toLowerCase();
        if (!"tsv".equals(normalized) && !"txt".equals(normalized) && !"csv".equals(normalized)) {
            throw new ChemOperationException("unsupported_artifact_format", "Text artifact format must be tsv, csv, or txt.");
        }
        return normalized;
    }

    private static Path defaultBaseDirectory() {
        String configured = System.getProperty(ARTIFACT_DIR_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim());
        }
        return Path.of(System.getProperty("java.io.tmpdir"), "structurized-mcp-artifacts", UUID.randomUUID().toString());
    }

    record ArtifactRecord(
            String artifactId,
            String path,
            String relativePath,
            String format,
            String contentType,
            long byteSize,
            String createdAt,
            String sourceTool,
            Integer rowCount
    ) {}
}
