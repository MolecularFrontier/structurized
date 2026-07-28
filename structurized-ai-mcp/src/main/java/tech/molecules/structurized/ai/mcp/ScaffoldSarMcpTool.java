package tech.molecules.structurized.ai.mcp;

import com.actelion.research.chem.Canonizer;
import com.actelion.research.chem.IDCodeParser;
import com.actelion.research.chem.IsomericSmilesCreator;
import com.actelion.research.chem.Molecule;
import com.actelion.research.chem.SmilesParser;
import com.actelion.research.chem.StereoMolecule;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tech.molecules.structurized.ai.model.ChemOperationException;
import tech.molecules.structurized.ai.prism.CreatePrismRowSetFromRowsRequest;
import tech.molecules.structurized.ai.prism.PrismBridgeService;
import tech.molecules.structurized.ai.prism.PrismRowSetColumnSummary;
import tech.molecules.structurized.ai.prism.PrismRowSetStructureCollection;
import tech.molecules.structurized.ai.prism.PrismRowStructureEntry;
import tech.molecules.structurized.scaffolds.CompoundDecompositionRecord;
import tech.molecules.structurized.scaffolds.CompoundRecord;
import tech.molecules.structurized.scaffolds.ExitVectorAssignment;
import tech.molecules.structurized.scaffolds.ScaffoldAnalyzer;
import tech.molecules.structurized.scaffolds.ScaffoldCandidate;
import tech.molecules.structurized.scaffolds.ScaffoldDatasetDecomposition;
import tech.molecules.structurized.scaffolds.ScaffoldDiscoveryConfig;
import tech.molecules.structurized.scaffolds.ScaffoldDiscoveryEngine;
import tech.molecules.structurized.scaffolds.ScaffoldDiscoveryResult;
import tech.molecules.structurized.scaffolds.ScaffoldTemplate;
import tech.molecules.structurized.transforms.OclStrictMcsProvider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

final class ScaffoldSarMcpTool {
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;

    private final PrismBridgeService prism;
    private final McpArtifactService artifacts;
    private final McpToolOutputSupport output;
    private final Map<String, StoredDiscovery> discoveries = new LinkedHashMap<>();
    private final Map<String, StoredAnalysis> analyses = new LinkedHashMap<>();
    private final AtomicInteger discoveryCounter = new AtomicInteger(1);
    private final AtomicInteger analysisCounter = new AtomicInteger(1);

    ScaffoldSarMcpTool(PrismBridgeService prism, McpArtifactService artifacts, McpToolOutputSupport output) {
        this.prism = Objects.requireNonNull(prism, "prism");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.output = Objects.requireNonNull(output, "output");
    }

    Object discoverPrismScaffolds(ObjectNode args) {
        String sessionId = requiredString(args, "session_id");
        String rowSetId = optionalString(args, "row_set_id", "all");
        int offset = Math.max(0, optionalInt(args, "offset", 0));
        int limit = safeLimit(args, "limit", 20);
        int exampleLimit = safeLimit(args, "example_limit", 3);
        PrismRowSetStructureCollection structures = prism.rowSetStructures(sessionId, rowSetId);
        List<PreparedStructure> prepared = preparedStructures(structures);
        if (prepared.size() < 2) {
            throw new ChemOperationException("insufficient_scaffold_discovery_structures", "Scaffold discovery requires at least two usable structures.");
        }

        ScaffoldDiscoveryConfig cfg = discoveryConfig(args);
        ScaffoldDiscoveryResult result = ScaffoldDiscoveryEngine.discover(
                prepared.stream().map(PreparedStructure::molecule).toList(),
                new OclStrictMcsProvider(),
                cfg
        );
        String discoveryId = optionalString(args, "discovery_id", null);
        if (discoveryId == null || discoveryId.isBlank()) {
            discoveryId = "scaffold_discovery_" + discoveryCounter.getAndIncrement();
        }
        StoredDiscovery stored = new StoredDiscovery(discoveryId, sessionId, rowSetId, structures, prepared, result);
        discoveries.put(discoveryId, stored);

        List<ScaffoldCandidateRow> rows = candidateRows(stored, offset, limit, exampleLimit);
        ScaffoldDiscoveryView response = new ScaffoldDiscoveryView(
                discoveryId,
                sessionId,
                rowSetId,
                structures.rowCount(),
                structures.structureCount(),
                structures.skippedRows(),
                result.seedCount,
                result.pairwiseCandidateCount,
                result.uniqueCandidateCount,
                result.candidates.size(),
                rows.size(),
                offset,
                limit,
                rows
        );
        ScaffoldDiscoveryArtifactSummary summary = new ScaffoldDiscoveryArtifactSummary(
                discoveryId,
                sessionId,
                rowSetId,
                result.candidates.size(),
                rows.size(),
                offset,
                limit
        );
        return output.maybeFile(args, "discover_prism_scaffolds", response, summary, rows.size());
    }

    Object analyzePrismScaffold(ObjectNode args) {
        String sessionId = requiredString(args, "session_id");
        String rowSetId = optionalString(args, "row_set_id", "all");
        int topSubstituentLimit = safeLimit(args, "top_substituent_limit", 5);
        int exampleLimit = safeLimit(args, "example_limit", 3);
        ScaffoldTemplate template = scaffoldTemplate(args);
        PrismRowSetStructureCollection structures = prism.rowSetStructures(sessionId, rowSetId);
        List<PreparedStructure> prepared = preparedStructures(structures);
        if (prepared.isEmpty()) {
            throw new ChemOperationException("empty_scaffold_analysis", "No usable structures found in row set " + rowSetId + ".");
        }
        List<CompoundRecord> compounds = new ArrayList<>();
        for (PreparedStructure structure : prepared) {
            compounds.add(new CompoundRecord(structure.index(), structure.molecule(), new Canonizer(structure.molecule()).getIDCode(), new long[8], List.of()));
        }
        ScaffoldAnalyzer.Config cfg = new ScaffoldAnalyzer.Config();
        cfg.radiusR = Math.max(0, optionalInt(args, "context_radius", 1));
        ScaffoldDatasetDecomposition dataset = ScaffoldDatasetDecomposition.analyze(compounds, template, cfg);
        String analysisId = optionalString(args, "scaffold_analysis_id", null);
        if (analysisId == null || analysisId.isBlank()) {
            analysisId = "scaffold_analysis_" + analysisCounter.getAndIncrement();
        }
        StoredAnalysis analysis = new StoredAnalysis(analysisId, sessionId, rowSetId, structures, prepared, dataset);
        analyses.put(analysisId, analysis);
        ScaffoldAnalysisView response = analysisView(analysis, topSubstituentLimit, exampleLimit);
        ScaffoldAnalysisArtifactSummary summary = new ScaffoldAnalysisArtifactSummary(
                analysisId,
                sessionId,
                rowSetId,
                dataset.matchedCompoundCount,
                dataset.unmatchedCompoundCount,
                dataset.observedExitVectorAtoms.size()
        );
        return output.maybeFile(args, "analyze_prism_scaffold", response, summary, response.observedExitVectors().size());
    }

    Object getPrismScaffoldProjection(ObjectNode args) {
        StoredAnalysis analysis = analysis(requiredString(args, "scaffold_analysis_id"));
        List<Integer> atoms = scaffoldAtoms(args);
        int offset = Math.max(0, optionalInt(args, "offset", 0));
        int limit = safeLimit(args, "limit", DEFAULT_LIMIT);
        int exampleLimit = safeLimit(args, "example_limit", 3);
        List<String> columnIds = optionalStringList(args, "column_ids");
        Double threshold = optionalDouble(args, "threshold", null);
        String thresholdDirection = optionalString(args, "threshold_direction", "gte");
        int topValuesLimit = safeLimit(args, "top_values_limit", 5);

        ProjectionBuild projection = projection(analysis, atoms);
        List<ScaffoldProjectionRow> page = projection.rows().stream()
                .skip(offset)
                .limit(limit)
                .map(row -> projectionRow(analysis, row, atoms, exampleLimit, columnIds, threshold, thresholdDirection, topValuesLimit))
                .toList();
        ScaffoldProjectionView response = new ScaffoldProjectionView(
                analysis.analysisId(),
                analysis.sessionId(),
                analysis.rowSetId(),
                atoms.size(),
                atoms,
                projection.totalRows(),
                page.size(),
                offset,
                limit,
                page
        );
        ScaffoldProjectionArtifactSummary summary = new ScaffoldProjectionArtifactSummary(
                analysis.analysisId(),
                atoms.size(),
                projection.totalRows(),
                page.size(),
                offset,
                limit
        );
        return output.maybeFile(args, "get_prism_scaffold_projection", response, summary, page.size());
    }

    Object createPrismScaffoldBucketRowSet(ObjectNode args) {
        StoredAnalysis analysis = analysis(requiredString(args, "scaffold_analysis_id"));
        String bucketKey = requiredString(args, "bucket_key");
        ProjectionBuild projection = projection(analysis, scaffoldAtoms(args));
        ProjectionRow row = projection.rows().stream()
                .filter(candidate -> bucketKey.equals(candidate.bucketKey()))
                .findFirst()
                .orElseThrow(() -> new ChemOperationException("scaffold_bucket_not_found", "Bucket " + bucketKey + " does not exist in scaffold analysis " + analysis.analysisId() + "."));
        return prism.createRowSetFromRows(new CreatePrismRowSetFromRowsRequest(
                analysis.sessionId(),
                row.rowIds(),
                optionalString(args, "row_set_id", null),
                optionalString(args, "name", "Scaffold bucket " + bucketKey),
                optionalString(args, "description", "Rows in scaffold analysis " + analysis.analysisId() + " bucket " + bucketKey + "."),
                Map.of("source", "scaffold_projection_bucket", "scaffoldAnalysisId", analysis.analysisId(), "bucketKey", bucketKey)
        ));
    }

    Object exportPrismScaffoldProjection(ObjectNode args) {
        StoredAnalysis analysis = analysis(requiredString(args, "scaffold_analysis_id"));
        List<Integer> atoms = scaffoldAtoms(args);
        ProjectionBuild projection = projection(analysis, atoms);
        StringBuilder tsv = new StringBuilder();
        tsv.append("bucket_key\tcount\texample_row_ids");
        for (int i = 0; i < atoms.size(); i++) {
            tsv.append("\tatom_").append(i + 1).append("_scaffold_atom")
                    .append("\tatom_").append(i + 1).append("_bucket_type")
                    .append("\tatom_").append(i + 1).append("_label")
                    .append("\tatom_").append(i + 1).append("_fragment_idcode");
        }
        tsv.append('\n');
        int exampleLimit = safeLimit(args, "example_limit", 3);
        for (ProjectionRow row : projection.rows()) {
            appendTsv(tsv, row.bucketKey(), Integer.toString(row.rowIds().size()), String.join("|", row.rowIds().stream().limit(exampleLimit).toList()));
            for (int i = 0; i < row.buckets().size(); i++) {
                BucketView bucket = row.buckets().get(i);
                appendTsv(tsv, Integer.toString(atoms.get(i)), bucket.type(), bucket.label(), bucket.fragmentIdcode());
            }
            tsv.append('\n');
        }
        McpArtifactService.ArtifactRecord artifact = artifacts.writeText(
                "export_prism_scaffold_projection",
                optionalString(args, "output_name", null),
                optionalBoolean(args, "overwrite", false),
                "tsv",
                "text/tab-separated-values",
                tsv.toString(),
                projection.totalRows()
        );
        return new ExportScaffoldProjectionResult(
                new ExportScaffoldProjectionSummary(analysis.analysisId(), atoms.size(), projection.totalRows()),
                artifact
        );
    }

    private ScaffoldTemplate scaffoldTemplate(ObjectNode args) {
        String discoveryId = optionalString(args, "discovery_id", null);
        String candidateId = optionalString(args, "candidate_id", null);
        if (discoveryId != null && !discoveryId.isBlank()) {
            if (candidateId == null || candidateId.isBlank()) {
                throw new ChemOperationException("invalid_scaffold_candidate", "candidate_id is required when discovery_id is provided.");
            }
            StoredDiscovery discovery = discovery(discoveryId);
            int index = candidateIndex(candidateId);
            if (index < 0 || index >= discovery.result().candidates.size()) {
                throw new ChemOperationException("scaffold_candidate_not_found", "Candidate " + candidateId + " does not exist in discovery " + discoveryId + ".");
            }
            return discovery.result().candidates.get(index).template;
        }
        String smiles = requiredString(args, "scaffold_smiles");
        return ScaffoldTemplate.create(parseSmiles(smiles, "scaffold_smiles"));
    }

    private List<ScaffoldCandidateRow> candidateRows(StoredDiscovery discovery, int offset, int limit, int exampleLimit) {
        int from = Math.min(offset, discovery.result().candidates.size());
        int to = Math.min(from + limit, discovery.result().candidates.size());
        List<ScaffoldCandidateRow> rows = new ArrayList<>();
        for (int i = from; i < to; i++) {
            ScaffoldCandidate candidate = discovery.result().candidates.get(i);
            rows.add(new ScaffoldCandidateRow(
                    candidateId(i),
                    scaffoldSmiles(candidate.template),
                    candidate.template.idcode,
                    candidate.supportCount,
                    candidate.averageExplainedFraction,
                    candidate.scaffoldHeavyAtomCount,
                    candidate.observedExitVectorCount,
                    exitVectorViews(candidate.template, candidate.observedExitVectorAtoms),
                    rowIds(discovery.prepared(), candidate.supportCompoundIndices).stream().limit(exampleLimit).toList(),
                    candidate.combinedScore
            ));
        }
        return rows;
    }

    private ScaffoldAnalysisView analysisView(StoredAnalysis analysis, int topSubstituentLimit, int exampleLimit) {
        ScaffoldDatasetDecomposition dataset = analysis.dataset();
        List<ScaffoldExitVectorSummary> exitVectors = dataset.observedExitVectorAtoms.stream()
                .map(atom -> exitVectorSummary(analysis, atom, topSubstituentLimit, exampleLimit))
                .toList();
        return new ScaffoldAnalysisView(
                analysis.analysisId(),
                analysis.sessionId(),
                analysis.rowSetId(),
                scaffoldSmiles(dataset.template),
                dataset.template.idcode,
                analysis.structures().rowCount(),
                analysis.structures().structureCount(),
                analysis.structures().skippedRows(),
                dataset.matchedCompoundCount,
                dataset.unmatchedCompoundCount,
                dataset.multiAttachmentCompoundCount,
                exitVectors.size(),
                exitVectors
        );
    }

    private ScaffoldExitVectorSummary exitVectorSummary(StoredAnalysis analysis, int atom, int limit, int exampleLimit) {
        ProjectionBuild projection = projection(analysis, List.of(atom));
        List<ScaffoldProjectionRow> rows = projection.rows().stream()
                .limit(limit)
                .map(row -> projectionRow(analysis, row, List.of(atom), exampleLimit, null, null, "gte", 5))
                .toList();
        return new ScaffoldExitVectorSummary(
                atom,
                analysis.dataset().exitVectorLabel(atom),
                analysis.dataset().template.atomSymmetryClasses[atom],
                projection.totalRows(),
                rows
        );
    }

    private ProjectionBuild projection(StoredAnalysis analysis, List<Integer> atoms) {
        if (atoms.isEmpty()) {
            throw new ChemOperationException("invalid_scaffold_projection", "scaffold_atoms must contain at least one atom index.");
        }
        for (int atom : atoms) {
            if (atom < 0 || atom >= analysis.dataset().template.scaffold.getAtoms()) {
                throw new ChemOperationException("invalid_scaffold_atom", "Scaffold atom index " + atom + " is outside the scaffold atom range.");
            }
        }
        Map<String, MutableProjectionRow> rows = new LinkedHashMap<>();
        for (CompoundDecompositionRecord record : analysis.dataset().records) {
            List<BucketView> buckets = atoms.stream().map(atom -> bucket(analysis.dataset(), record, atom)).toList();
            String key = buckets.stream().map(BucketView::bucketKey).collect(Collectors.joining("||"));
            MutableProjectionRow row = rows.computeIfAbsent(key, ignored -> new MutableProjectionRow(key, buckets));
            row.rowIds.add(analysis.prepared().get(record.compound.index).entry().rowId());
        }
        List<ProjectionRow> sorted = rows.values().stream()
                .map(MutableProjectionRow::toRow)
                .sorted(Comparator.comparingInt((ProjectionRow row) -> row.rowIds().size()).reversed().thenComparing(ProjectionRow::bucketKey))
                .toList();
        return new ProjectionBuild(sorted.size(), sorted);
    }

    private ScaffoldProjectionRow projectionRow(StoredAnalysis analysis,
                                                ProjectionRow row,
                                                List<Integer> atoms,
                                                int exampleLimit,
                                                List<String> columnIds,
                                                Double threshold,
                                                String thresholdDirection,
                                                int topValuesLimit) {
        PrismRowSetColumnSummary columns = columnIds == null || columnIds.isEmpty()
                ? null
                : prism.summarizeRowsByColumns(analysis.sessionId(), row.rowIds(), columnIds, threshold, thresholdDirection, topValuesLimit);
        return new ScaffoldProjectionRow(
                row.bucketKey(),
                row.rowIds().size(),
                row.rowIds().stream().limit(exampleLimit).toList(),
                row.buckets(),
                columns == null ? null : columns.columns()
        );
    }

    private static BucketView bucket(ScaffoldDatasetDecomposition dataset, CompoundDecompositionRecord record, int atom) {
        if (!record.matched) {
            return new BucketView("unmatched", "UNMATCHED", "[unmatched]", null);
        }
        if (record.multiAttachmentAtoms.contains(atom) || record.ambiguousAtoms.contains(atom)) {
            return new BucketView("multi", "MULTI_ATTACHMENT", "[multi-attachment]", null);
        }
        ExitVectorAssignment assignment = record.assignmentsByScaffoldAtom.get(atom);
        if (assignment != null) {
            String label = assignment.fragmentSmiles == null || assignment.fragmentSmiles.isBlank() ? "[fragment]" : assignment.fragmentSmiles;
            return new BucketView("sub:" + assignment.fragmentIdcode, "SUBSTITUENT", label, assignment.fragmentIdcode);
        }
        return new BucketView("none", "UNSUBSTITUTED", "[unsubstituted]", null);
    }

    private List<PreparedStructure> preparedStructures(PrismRowSetStructureCollection structures) {
        ArrayList<PreparedStructure> result = new ArrayList<>();
        int index = 0;
        for (PrismRowStructureEntry entry : structures.structures()) {
            result.add(new PreparedStructure(index++, entry, parseSmiles(entry.smiles(), "row " + entry.rowId())));
        }
        return List.copyOf(result);
    }

    private ScaffoldDiscoveryConfig discoveryConfig(ObjectNode args) {
        ScaffoldDiscoveryConfig cfg = new ScaffoldDiscoveryConfig();
        cfg.neighborCount = Math.max(1, optionalInt(args, "neighbor_count", cfg.neighborCount));
        cfg.minNeighborSimilarity = optionalDouble(args, "min_neighbor_similarity", (double) cfg.minNeighborSimilarity).floatValue();
        cfg.maxSeeds = optionalInt(args, "max_seeds", cfg.maxSeeds);
        cfg.shuffleSeeds = optionalBoolean(args, "shuffle_seeds", cfg.shuffleSeeds);
        cfg.randomSeed = optionalLong(args, "random_seed", cfg.randomSeed);
        cfg.minScaffoldHeavyAtoms = Math.max(1, optionalInt(args, "min_scaffold_heavy_atoms", cfg.minScaffoldHeavyAtoms));
        cfg.requireConnectedScaffold = optionalBoolean(args, "require_connected_scaffold", cfg.requireConnectedScaffold);
        cfg.minSupport = Math.max(1, optionalInt(args, "min_support", cfg.minSupport));
        cfg.radiusR = Math.max(0, optionalInt(args, "context_radius", cfg.radiusR));
        return cfg;
    }

    private List<Integer> scaffoldAtoms(ObjectNode args) {
        JsonNode node = args.get("scaffold_atoms");
        if (node == null || node.isNull()) {
            JsonNode single = args.get("scaffold_atom");
            if (single != null && single.canConvertToInt()) {
                return List.of(single.asInt());
            }
            throw new ChemOperationException("invalid_scaffold_projection", "Provide scaffold_atoms as an array of zero-based scaffold atom indices.");
        }
        if (!node.isArray()) {
            throw new ChemOperationException("invalid_scaffold_projection", "scaffold_atoms must be an array of integers.");
        }
        ArrayList<Integer> atoms = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.canConvertToInt()) {
                throw new ChemOperationException("invalid_scaffold_projection", "scaffold_atoms must contain only integers.");
            }
            atoms.add(item.asInt());
        }
        return List.copyOf(atoms);
    }

    private StoredDiscovery discovery(String discoveryId) {
        StoredDiscovery discovery = discoveries.get(discoveryId);
        if (discovery == null) {
            throw new ChemOperationException("scaffold_discovery_not_found", "Scaffold discovery " + discoveryId + " does not exist.");
        }
        return discovery;
    }

    private StoredAnalysis analysis(String analysisId) {
        StoredAnalysis analysis = analyses.get(analysisId);
        if (analysis == null) {
            throw new ChemOperationException("scaffold_analysis_not_found", "Scaffold analysis " + analysisId + " does not exist.");
        }
        return analysis;
    }

    private static List<ScaffoldExitVectorView> exitVectorViews(ScaffoldTemplate template, List<Integer> atoms) {
        return atoms.stream()
                .map(atom -> new ScaffoldExitVectorView(atom, "Atom " + (atom + 1) + " (sym " + template.atomSymmetryClasses[atom] + ")", template.atomSymmetryClasses[atom]))
                .toList();
    }

    private static List<String> rowIds(List<PreparedStructure> prepared, List<Integer> indices) {
        return indices.stream().map(index -> prepared.get(index).entry().rowId()).toList();
    }

    private static String candidateId(int zeroBasedIndex) {
        return "scaffold_" + (zeroBasedIndex + 1);
    }

    private static int candidateIndex(String candidateId) {
        String normalized = candidateId == null ? "" : candidateId.trim();
        if (!normalized.startsWith("scaffold_")) {
            throw new ChemOperationException("invalid_scaffold_candidate", "candidate_id must look like scaffold_1.");
        }
        try {
            return Integer.parseInt(normalized.substring("scaffold_".length())) - 1;
        } catch (NumberFormatException exception) {
            throw new ChemOperationException("invalid_scaffold_candidate", "candidate_id must look like scaffold_1.", exception);
        }
    }

    private static StereoMolecule parseSmiles(String smiles, String label) {
        if (smiles == null || smiles.isBlank()) {
            throw new ChemOperationException("invalid_smiles", label + " must not be blank.");
        }
        try {
            StereoMolecule molecule = new StereoMolecule();
            new SmilesParser().parse(molecule, smiles);
            molecule.ensureHelperArrays(Molecule.cHelperSymmetrySimple);
            return molecule;
        } catch (Exception exception) {
            throw new ChemOperationException("invalid_smiles", "Could not parse " + label + ": " + smiles, exception);
        }
    }

    private static String scaffoldSmiles(ScaffoldTemplate template) {
        try {
            StereoMolecule molecule = new IDCodeParser(false).getCompactMolecule(template.idcode);
            molecule.ensureHelperArrays(Molecule.cHelperSymmetrySimple);
            return IsomericSmilesCreator.createSmiles(molecule);
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private static int safeLimit(ObjectNode args, String name, int defaultValue) {
        return Math.min(MAX_LIMIT, Math.max(1, optionalInt(args, name, defaultValue)));
    }

    private static String requiredString(ObjectNode args, String name) {
        String value = optionalString(args, name, null);
        if (value == null || value.isBlank()) {
            throw new ChemOperationException("invalid_arguments", "Missing required argument: " + name);
        }
        return value;
    }

    private static String optionalString(ObjectNode args, String name, String defaultValue) {
        JsonNode node = args.get(name);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (!node.isTextual()) {
            throw new ChemOperationException("invalid_arguments", "Argument " + name + " must be a string.");
        }
        return node.asText();
    }

    private static int optionalInt(ObjectNode args, String name, int defaultValue) {
        JsonNode node = args.get(name);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (!node.canConvertToInt()) {
            throw new ChemOperationException("invalid_arguments", "Argument " + name + " must be an integer.");
        }
        return node.asInt();
    }

    private static long optionalLong(ObjectNode args, String name, long defaultValue) {
        JsonNode node = args.get(name);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (!node.canConvertToLong()) {
            throw new ChemOperationException("invalid_arguments", "Argument " + name + " must be an integer.");
        }
        return node.asLong();
    }

    private static Double optionalDouble(ObjectNode args, String name, Double defaultValue) {
        JsonNode node = args.get(name);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (!node.isNumber()) {
            throw new ChemOperationException("invalid_arguments", "Argument " + name + " must be a number.");
        }
        return node.asDouble();
    }

    private static boolean optionalBoolean(ObjectNode args, String name, boolean defaultValue) {
        JsonNode node = args.get(name);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (!node.isBoolean()) {
            throw new ChemOperationException("invalid_arguments", "Argument " + name + " must be a boolean.");
        }
        return node.asBoolean();
    }

    private static List<String> optionalStringList(ObjectNode args, String name) {
        JsonNode node = args.get(name);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            throw new ChemOperationException("invalid_arguments", "Argument " + name + " must be an array of strings.");
        }
        ArrayList<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw new ChemOperationException("invalid_arguments", "Argument " + name + " must contain only strings.");
            }
            result.add(item.asText());
        }
        return List.copyOf(result);
    }

    private static void appendTsv(StringBuilder builder, String... values) {
        for (String value : values) {
            builder.append('\t').append(tsvCell(value));
        }
    }

    private static String tsvCell(String value) {
        return value == null ? "" : value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private record PreparedStructure(int index, PrismRowStructureEntry entry, StereoMolecule molecule) {}

    private record StoredDiscovery(
            String discoveryId,
            String sessionId,
            String rowSetId,
            PrismRowSetStructureCollection structures,
            List<PreparedStructure> prepared,
            ScaffoldDiscoveryResult result
    ) {}

    private record StoredAnalysis(
            String analysisId,
            String sessionId,
            String rowSetId,
            PrismRowSetStructureCollection structures,
            List<PreparedStructure> prepared,
            ScaffoldDatasetDecomposition dataset
    ) {}

    private record ScaffoldDiscoveryView(
            String discoveryId,
            String sessionId,
            String rowSetId,
            int sourceRowCount,
            int structureCount,
            int skippedRows,
            int seedCount,
            int pairwiseCandidateCount,
            int uniqueCandidateCount,
            int totalCandidates,
            int returnedCandidates,
            int offset,
            int limit,
            List<ScaffoldCandidateRow> candidates
    ) {}

    private record ScaffoldCandidateRow(
            String candidateId,
            String scaffoldSmiles,
            String scaffoldIdcode,
            int supportCount,
            double averageExplainedFraction,
            int scaffoldHeavyAtomCount,
            int observedExitVectorCount,
            List<ScaffoldExitVectorView> observedExitVectors,
            List<String> exampleRowIds,
            double score
    ) {}

    private record ScaffoldExitVectorView(int scaffoldAtom, String label, int symmetryClass) {}

    private record ScaffoldAnalysisView(
            String scaffoldAnalysisId,
            String sessionId,
            String rowSetId,
            String scaffoldSmiles,
            String scaffoldIdcode,
            int sourceRowCount,
            int structureCount,
            int skippedRows,
            int matchedCount,
            int unmatchedCount,
            int multiAttachmentCount,
            int observedExitVectorCount,
            List<ScaffoldExitVectorSummary> observedExitVectors
    ) {}

    private record ScaffoldExitVectorSummary(
            int scaffoldAtom,
            String label,
            int symmetryClass,
            int distinctBucketCount,
            List<ScaffoldProjectionRow> topBuckets
    ) {}

    private record ScaffoldProjectionView(
            String scaffoldAnalysisId,
            String sessionId,
            String rowSetId,
            int dimension,
            List<Integer> scaffoldAtoms,
            int totalBuckets,
            int returnedBuckets,
            int offset,
            int limit,
            List<ScaffoldProjectionRow> rows
    ) {}

    private record ScaffoldProjectionRow(
            String bucketKey,
            int count,
            List<String> exampleRowIds,
            List<BucketView> buckets,
            Object columnSummaries
    ) {}

    private record BucketView(String bucketKey, String type, String label, String fragmentIdcode) {}

    private record ProjectionBuild(int totalRows, List<ProjectionRow> rows) {}

    private record ProjectionRow(String bucketKey, List<BucketView> buckets, List<String> rowIds) {}

    private static final class MutableProjectionRow {
        private final String key;
        private final List<BucketView> buckets;
        private final LinkedHashSet<String> rowIds = new LinkedHashSet<>();

        private MutableProjectionRow(String key, List<BucketView> buckets) {
            this.key = key;
            this.buckets = List.copyOf(buckets);
        }

        private ProjectionRow toRow() {
            return new ProjectionRow(key, buckets, List.copyOf(rowIds));
        }
    }

    private record ScaffoldDiscoveryArtifactSummary(
            String discoveryId,
            String sessionId,
            String rowSetId,
            int totalCandidates,
            int returnedCandidates,
            int offset,
            int limit
    ) {}

    private record ScaffoldAnalysisArtifactSummary(
            String scaffoldAnalysisId,
            String sessionId,
            String rowSetId,
            int matchedCount,
            int unmatchedCount,
            int observedExitVectorCount
    ) {}

    private record ScaffoldProjectionArtifactSummary(
            String scaffoldAnalysisId,
            int dimension,
            int totalBuckets,
            int returnedBuckets,
            int offset,
            int limit
    ) {}

    private record ExportScaffoldProjectionResult(ExportScaffoldProjectionSummary summary, McpArtifactService.ArtifactRecord artifact) {}

    private record ExportScaffoldProjectionSummary(String scaffoldAnalysisId, int dimension, int rowCount) {}
}
