package tech.molecules.structurized.ai.prism;

import com.actelion.research.chem.StereoMolecule;
import tech.molecules.structurized.ai.model.ChemOperationException;
import tech.molecules.structurized.mmp.MmpInputCompound;
import tech.molecules.structurized.mmp.MmpMiner;
import tech.molecules.structurized.mmp.MmpMiningConfig;
import tech.molecules.structurized.mmp.MmpMiningResult;
import tech.molecules.structurized.mmp.MmpPair;
import tech.molecules.structurized.prism.engine.PrismColumn;
import tech.molecules.structurized.prism.engine.PrismColumnType;
import tech.molecules.structurized.prism.engine.PrismOperationResult;
import tech.molecules.structurized.prism.engine.PrismRowGraph;
import tech.molecules.structurized.prism.engine.PrismRowGraphEdge;
import tech.molecules.structurized.prism.engine.PrismRowSet;
import tech.molecules.structurized.prism.engine.PrismSession;
import tech.molecules.structurized.prism.engine.ocl.OclStructureFormat;
import tech.molecules.structurized.prism.engine.ocl.OclStructureParser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class PrismMmpGraphService {
    static final String GRAPH_TYPE = "chemistry.mmp";
    static final String PLUGIN_ID = "structurized-mmp";

    PrismMmpGraphSummary mine(ManagedPrismSession session,
                              PrismRowSet sourceRowSet,
                              MinePrismMmpGraphRequest request) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(sourceRowSet, "sourceRowSet");
        Objects.requireNonNull(request, "request");
        PrismSession workspace = session.workspace();
        PrismColumn structureColumn = structureColumn(workspace, request.structureColumnId());
        PrismColumn valueColumn = valueColumn(workspace, request.valueColumnId());
        MmpMiningConfig config = config(request);
        String graphId = resolveGraphId(workspace, request.graphId());
        String label = request.label() == null || request.label().isBlank() ? graphId : request.label().trim();
        long sourceRevision = session.revision();

        ArrayList<MmpInputCompound> inputs = new ArrayList<>();
        ArrayList<PrismSkippedAnalysisRow> skipped = new ArrayList<>();
        OclStructureParser parser = new OclStructureParser();
        OclStructureFormat format = OclStructureFormat.fromMetadata(structureColumn.schema().structureFormat());
        for (String rowId : sourceRowSet.rowIds()) {
            int physicalRow = workspace.physicalRowForRowId(rowId)
                    .orElseThrow(() -> new ChemOperationException("prism_row_not_found", "Prism row " + rowId + " does not exist."));
            if (structureColumn.isMissing(physicalRow)) {
                skipped.add(new PrismSkippedAnalysisRow(rowId, "missing_structure", "Row has no structure value."));
                continue;
            }
            try {
                StereoMolecule molecule = parser.parse(structureColumn.formattedValueAt(physicalRow), null, format);
                if (molecule == null) {
                    skipped.add(new PrismSkippedAnalysisRow(rowId, "missing_structure", "Row has no usable structure value."));
                    continue;
                }
                inputs.add(new MmpInputCompound(rowId, molecule, numericValue(valueColumn, physicalRow)));
            } catch (RuntimeException exception) {
                skipped.add(new PrismSkippedAnalysisRow(rowId, "invalid_structure", exception.getMessage()));
            }
        }
        if (inputs.isEmpty()) {
            throw new ChemOperationException(
                    "no_mmp_structure_rows",
                    "Prism row set " + sourceRowSet.id() + " contains no valid structures for MMP mining."
            );
        }

        MmpMiningResult result = MmpMiner.mine(inputs, config);
        Map<String, Object> configMap = configMap(config);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sourceRowCount", sourceRowSet.rowIds().size());
        metadata.put("validStructureCount", inputs.size());
        metadata.put("skippedRowCount", skipped.size());
        metadata.put("fragmentationRecordCount", result.fragmentationRecords().size());
        metadata.put("pairCount", result.pairs().size());
        metadata.put("transformCount", result.transformStats().size());
        metadata.put("structureColumnId", structureColumn.id());
        if (valueColumn != null) metadata.put("valueColumnId", valueColumn.id());
        metadata.put("configuration", configMap);
        metadata.put("configurationHash", hash(configMap.toString()));

        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("source", "structurized_mmp_mining");
        provenance.put("sessionId", session.sessionId());
        provenance.put("sourceRevision", sourceRevision);
        provenance.put("sourceRowSetId", sourceRowSet.id());
        provenance.put("structureColumnId", structureColumn.id());
        if (valueColumn != null) provenance.put("valueColumnId", valueColumn.id());
        provenance.put("createdAt", Instant.now().toString());
        provenance.put("configuration", configMap);

        PrismRowGraph graph = new PrismRowGraph(
                graphId,
                label,
                "Matched molecular pair network mined by Structurized.",
                GRAPH_TYPE,
                PLUGIN_ID,
                1,
                true,
                sourceRowSet.id(),
                edges(result.pairs()),
                metadata,
                provenance
        );
        PrismOperationResult publication = PrismOperationResult.builder()
                .addGraph(graph)
                .provenance("graphId", graphId)
                .provenance("analysisType", GRAPH_TYPE)
                .output("graphId", graphId)
                .output("pairCount", result.pairs().size())
                .build();
        session.runAs(ManagedPrismSessionChangeOrigin.MCP, () -> workspace.applyOperationResult(publication));
        return new PrismMmpGraphSummary(
                graphSummary(session, graph),
                structureColumn.id(),
                valueColumn == null ? null : valueColumn.id(),
                sourceRowSet.rowIds().size(),
                inputs.size(),
                skipped.size(),
                result.fragmentationRecords().size(),
                result.pairs().size(),
                result.transformStats().size(),
                configMap,
                skipped
        );
    }

    static PrismGraphSummary graphSummary(ManagedPrismSession session, PrismRowGraph graph) {
        return new PrismGraphSummary(
                session.sessionId(),
                graph.id(),
                graph.title(),
                graph.description(),
                graph.graphType(),
                graph.pluginId(),
                graph.schemaVersion(),
                graph.directed(),
                graph.sourceRowSetId(),
                graph.rowIds().size(),
                graph.edges().size(),
                graph.metadata()
        );
    }

    private static List<PrismRowGraphEdge> edges(List<MmpPair> pairs) {
        ArrayList<PrismRowGraphEdge> edges = new ArrayList<>(pairs.size());
        int index = 1;
        for (MmpPair pair : pairs) {
            LinkedHashMap<String, Object> properties = new LinkedHashMap<>();
            properties.put("relationType", "matched_molecular_pair");
            properties.put("cutCount", pair.cutCount());
            properties.put("keyIdcode", pair.keyIdcode());
            properties.put("fromValueIdcode", pair.fromValueIdcode());
            properties.put("toValueIdcode", pair.toValueIdcode());
            properties.put("transformId", pair.transformId());
            if (pair.valueA() != null) properties.put("valueA", pair.valueA());
            if (pair.valueB() != null) properties.put("valueB", pair.valueB());
            if (pair.delta() != null) properties.put("delta", pair.delta());
            edges.add(new PrismRowGraphEdge(
                    "mmp-edge-" + index++,
                    pair.compoundIdA(),
                    pair.compoundIdB(),
                    pair.cutCount() + "-cut MMP",
                    properties
            ));
        }
        return edges;
    }

    private static MmpMiningConfig config(MinePrismMmpGraphRequest request) {
        MmpMiningConfig.Builder builder = MmpMiningConfig.defaults().toBuilder();
        if (request.maxCuts() != null) builder.maxCuts(request.maxCuts());
        if (request.minTransformSupport() != null) builder.minTransformSupport(request.minTransformSupport());
        if (request.maxVariableHeavyAtoms() != null) builder.maxVariableHeavyAtoms(request.maxVariableHeavyAtoms());
        if (request.maxVariableToMolHeavyAtomFraction() != null) {
            builder.maxVariableToMolHeavyAtomFraction(request.maxVariableToMolHeavyAtomFraction());
        }
        if (request.maxFragmentationRecordsPerCompound() != null) {
            builder.maxFragmentationRecordsPerCompound(request.maxFragmentationRecordsPerCompound());
        }
        if (request.maxPairsPerKey() != null) builder.maxPairsPerKey(request.maxPairsPerKey());
        try {
            return builder.build();
        } catch (IllegalArgumentException exception) {
            throw new ChemOperationException("invalid_mmp_config", exception.getMessage(), exception);
        }
    }

    private static Map<String, Object> configMap(MmpMiningConfig config) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("maxCuts", config.maxCuts());
        map.put("singleBondsOnly", config.singleBondsOnly());
        map.put("skipSmallRings", config.skipSmallRings());
        map.put("allowMacrocycleRingCuts", config.allowMacrocycleRingCuts());
        map.put("macrocycleMinRingSize", config.macrocycleMinRingSize());
        map.put("allowMixedRingChainCutSets", config.allowMixedRingChainCutSets());
        map.put("minKeyHeavyAtoms", config.minKeyHeavyAtoms());
        map.put("minVariableHeavyAtoms", config.minVariableHeavyAtoms());
        map.put("maxVariableHeavyAtoms", config.maxVariableHeavyAtoms());
        map.put("maxVariableToMolHeavyAtomFraction", config.maxVariableToMolHeavyAtomFraction());
        map.put("maxFragmentationRecordsPerCompound", config.maxFragmentationRecordsPerCompound());
        map.put("maxPairsPerKey", config.maxPairsPerKey());
        map.put("emitReverseTransforms", config.emitReverseTransforms());
        map.put("minTransformSupport", config.minTransformSupport());
        return Map.copyOf(map);
    }

    private static PrismColumn structureColumn(PrismSession workspace, String requestedColumnId) {
        if (requestedColumnId != null && !requestedColumnId.isBlank()) {
            PrismColumn column = workspace.table().column(requestedColumnId.trim());
            if (column.type() != PrismColumnType.MOLECULE && !"chemical_structure".equals(column.schema().semanticType())) {
                throw new ChemOperationException("invalid_structure_column", "Column " + column.id() + " is not a structure column.");
            }
            return column;
        }
        return workspace.table().columns().stream()
                .filter(column -> column.type() == PrismColumnType.MOLECULE
                        || "chemical_structure".equals(column.schema().semanticType())
                        || "primary_structure".equals(column.schema().role()))
                .findFirst()
                .orElseThrow(() -> new ChemOperationException("missing_structure_column", "No structure column is available in this Prism session."));
    }

    private static PrismColumn valueColumn(PrismSession workspace, String requestedColumnId) {
        if (requestedColumnId == null || requestedColumnId.isBlank()) return null;
        PrismColumn column = workspace.table().column(requestedColumnId.trim());
        if (column.type() != PrismColumnType.NUMERIC && column.type() != PrismColumnType.INTEGER) {
            throw new ChemOperationException("invalid_value_column", "Column " + column.id() + " is not numeric.");
        }
        return column;
    }

    private static Double numericValue(PrismColumn column, int physicalRow) {
        if (column == null || column.isMissing(physicalRow)) return null;
        Object value = column.valueAt(physicalRow);
        if (value instanceof Number number) return number.doubleValue();
        return null;
    }

    private static String resolveGraphId(PrismSession workspace, String requestedGraphId) {
        String base = requestedGraphId == null || requestedGraphId.isBlank() ? "mmp_graph" : requestedGraphId.trim();
        String normalized = base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.:-]+", "_").replaceAll("^_+|_+$", "");
        if (normalized.isBlank()) normalized = "mmp_graph";
        String candidate = normalized;
        int suffix = 2;
        Set<String> existing = workspace.graphs().stream().map(PrismRowGraph::id).collect(java.util.stream.Collectors.toSet());
        while (existing.contains(candidate)) candidate = normalized + "_" + suffix++;
        return candidate;
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
