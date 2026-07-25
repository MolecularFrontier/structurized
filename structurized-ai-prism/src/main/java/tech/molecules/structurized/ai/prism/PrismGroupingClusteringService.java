package tech.molecules.structurized.ai.prism;

import tech.molecules.structurized.ai.model.ChemOperationException;
import tech.molecules.structurized.ai.ocl.MolecularSnapshot;
import tech.molecules.structurized.clustering.ClusterMember;
import tech.molecules.structurized.clustering.ClusteringInputMolecule;
import tech.molecules.structurized.clustering.SimilarityCluster;
import tech.molecules.structurized.clustering.SimilarityClusteringConfig;
import tech.molecules.structurized.clustering.SimilarityClusteringEngine;
import tech.molecules.structurized.clustering.SimilarityClusteringResult;
import tech.molecules.structurized.prism.engine.PrismColumnSchema;
import tech.molecules.structurized.prism.engine.PrismColumnType;
import tech.molecules.structurized.prism.engine.PrismGroup;
import tech.molecules.structurized.prism.engine.PrismGroupMembership;
import tech.molecules.structurized.prism.engine.PrismGrouping;
import tech.molecules.structurized.prism.engine.PrismGroupingMode;
import tech.molecules.structurized.prism.engine.PrismOperationResult;
import tech.molecules.structurized.prism.engine.PrismRowSet;
import tech.molecules.structurized.prism.engine.RowIdMaterializedColumnData;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

final class PrismGroupingClusteringService {
    private static final int PAGE_LIMIT_DEFAULT = 100;
    private static final int PAGE_LIMIT_MAX = 500;

    private final PrismArtifactRegistry artifacts;

    PrismGroupingClusteringService(PrismArtifactRegistry artifacts) {
        this.artifacts = artifacts;
    }

    PrismClusteringSummary cluster(ManagedPrismSession session,
                                   PrismRowSet sourceRowSet,
                                   PrismRowSetStructureCollection collection,
                                   ClusterPrismRowSetRequest request) {
        String analysisId = resolveAnalysisId(session, request.analysisId());
        String label = request.label() == null || request.label().isBlank() ? analysisId : request.label().trim();
        boolean publishColumns = request.publishColumns() == null || request.publishColumns();
        SimilarityClusteringConfig config = config(request);
        long sourceRevision = session.revision();

        LinkedHashMap<String, PrismRowStructureEntry> structuresByRowId = new LinkedHashMap<>();
        for (PrismRowStructureEntry entry : collection.structures()) {
            structuresByRowId.put(entry.rowId(), entry);
        }

        ArrayList<PrismSkippedAnalysisRow> skippedRows = new ArrayList<>();
        for (String rowId : sourceRowSet.rowIds()) {
            if (!structuresByRowId.containsKey(rowId)) {
                skippedRows.add(new PrismSkippedAnalysisRow(
                        rowId,
                        "missing_structure",
                        "Row has no usable structure value."
                ));
            }
        }

        ArrayList<ClusteringInputMolecule> inputs = new ArrayList<>();
        LinkedHashMap<String, PrismRowStructureEntry> validStructures = new LinkedHashMap<>();
        for (PrismRowStructureEntry entry : structuresByRowId.values()) {
            try {
                MolecularSnapshot snapshot = MolecularSnapshot.fromSmiles(entry.smiles());
                inputs.add(new ClusteringInputMolecule(entry.rowId(), entry.label(), snapshot.moleculeCopy()));
                validStructures.put(entry.rowId(), entry);
            } catch (RuntimeException exception) {
                skippedRows.add(new PrismSkippedAnalysisRow(entry.rowId(), "invalid_structure", exception.getMessage()));
            }
        }
        if (inputs.isEmpty()) {
            throw new ChemOperationException(
                    "no_clusterable_prism_rows",
                    "Prism row set " + sourceRowSet.id() + " contains no valid structures to cluster."
            );
        }

        SimilarityClusteringResult result = new SimilarityClusteringEngine().cluster(inputs, config);
        List<String> publishedColumnIds = publishColumns
                ? List.of(analysisId + ".cluster_id", analysisId + ".similarity_to_representative")
                : List.of();
        PrismAnalysisSummary analysisSummary = new PrismAnalysisSummary(
                session.sessionId(),
                analysisId,
                "similarity_clustering",
                label,
                sourceRowSet.id(),
                sourceRevision,
                sourceRevision + 1,
                Instant.now().toString(),
                publishedColumnIds,
                Map.of(
                        "descriptor", result.descriptor(),
                        "strategy", result.strategy(),
                        "threshold", result.threshold(),
                        "clusterCount", result.clusterCount(),
                        "moleculeCount", result.moleculeCount()
                )
        );
        PrismClusteringSummary clusteringSummary = new PrismClusteringSummary(
                analysisSummary,
                result.descriptor(),
                result.strategy(),
                result.threshold(),
                sourceRowSet.rowIds().size(),
                result.moleculeCount(),
                skippedRows.size(),
                result.clusterCount(),
                result.singletonCount(),
                result.unclusteredCount(),
                skippedRows
        );
        PrismOperationResult publication = publication(
                session,
                sourceRowSet,
                analysisId,
                label,
                result,
                publishColumns
        );
        PrismClusteringAnalysis artifact = new PrismClusteringAnalysis(
                analysisSummary,
                clusteringSummary,
                result,
                validStructures
        );
        session.runAs(ManagedPrismSessionChangeOrigin.MCP, () -> {
            session.workspace().applyOperationResult(publication);
            artifacts.add(session.sessionId(), artifact);
        });
        return clusteringSummary;
    }

    PrismClusteringView getClustering(ManagedPrismSession session,
                                      String analysisId,
                                      boolean includeSingletons,
                                      int offset,
                                      int limit) {
        PrismClusteringAnalysis analysis = analysis(session, analysisId);
        List<SimilarityCluster> selected = analysis.result().clusters().stream()
                .filter(cluster -> includeSingletons || cluster.size() > 1)
                .toList();
        int safeOffset = Math.min(Math.max(0, offset), selected.size());
        int safeLimit = safeLimit(limit);
        int to = Math.min(safeOffset + safeLimit, selected.size());
        List<PrismClusterSummary> clusters = selected.subList(safeOffset, to).stream()
                .map(cluster -> clusterSummary(analysis, cluster))
                .toList();
        return new PrismClusteringView(
                analysis.clusteringSummary(),
                selected.size(),
                safeOffset,
                safeLimit,
                clusters,
                analysis.result().unclustered().stream()
                        .map(row -> new PrismUnclusteredRow(
                                row.inputIndex(),
                                row.structureId(),
                                row.label(),
                                row.reason()
                        ))
                        .toList()
        );
    }

    PrismClusterMembersView getClusterMembers(ManagedPrismSession session,
                                              String analysisId,
                                              String clusterId,
                                              int offset,
                                              int limit) {
        PrismClusteringAnalysis analysis = analysis(session, analysisId);
        SimilarityCluster cluster = cluster(analysis, clusterId);
        List<ClusterMember> sorted = cluster.members().stream()
                .sorted(Comparator.comparingDouble(ClusterMember::similarityToRepresentative).reversed()
                        .thenComparingInt(ClusterMember::inputIndex))
                .toList();
        int safeOffset = Math.min(Math.max(0, offset), sorted.size());
        int safeLimit = safeLimit(limit);
        int to = Math.min(safeOffset + safeLimit, sorted.size());
        return new PrismClusterMembersView(
                session.sessionId(),
                analysisId,
                cluster.clusterId(),
                sorted.size(),
                safeOffset,
                safeLimit,
                sorted.subList(safeOffset, to).stream().map(member -> member(analysis, member)).toList()
        );
    }

    PrismRowSetSummary createClusterRowSet(ManagedPrismSession session,
                                           CreatePrismClusterRowSetRequest request,
                                           String rowSetId) {
        PrismGrouping grouping = session.workspace().grouping(request.analysisId());
        String clusterId = requireText(request.clusterId(), "clusterId");
        PrismGroup group = grouping.group(clusterId);
        String name = request.name() == null || request.name().isBlank()
                ? grouping.title() + " / " + group.label()
                : request.name().trim();
        String description = request.description() == null || request.description().isBlank()
                ? "Rows in " + group.label() + " from Prism grouping " + grouping.id() + "."
                : request.description().trim();
        PrismRowSet rowSet = new PrismRowSet(
                rowSetId,
                name,
                description,
                grouping.rowsInGroup(clusterId),
                Map.of(
                        "source", "prism_grouping",
                        "groupingId", grouping.id(),
                        "groupId", clusterId,
                        "analysisId", grouping.id()
                )
        );
        session.runAs(ManagedPrismSessionChangeOrigin.MCP, () -> session.workspace().addRowSet(rowSet));
        return rowSetSummary(session, rowSet);
    }

    private static PrismOperationResult publication(ManagedPrismSession session,
                                                    PrismRowSet sourceRowSet,
                                                    String analysisId,
                                                    String label,
                                                    SimilarityClusteringResult result,
                                                    boolean publishColumns) {
        ArrayList<PrismGroup> groups = new ArrayList<>();
        ArrayList<PrismGroupMembership> memberships = new ArrayList<>();
        LinkedHashMap<String, Double> similarities = new LinkedHashMap<>();
        for (SimilarityCluster cluster : result.clusters()) {
            double minimum = cluster.members().stream()
                    .mapToDouble(ClusterMember::similarityToRepresentative)
                    .min().orElse(1.0);
            double mean = cluster.members().stream()
                    .mapToDouble(ClusterMember::similarityToRepresentative)
                    .average().orElse(1.0);
            groups.add(new PrismGroup(
                    cluster.clusterId(),
                    cluster.clusterId(),
                    "Similarity cluster with " + cluster.size() + " members.",
                    null,
                    cluster.representativeStructureId(),
                    Map.of("size", cluster.size(), "minimumSimilarity", minimum, "meanSimilarity", mean)
            ));
            for (ClusterMember member : cluster.members()) {
                similarities.put(member.structureId(), member.similarityToRepresentative());
                memberships.add(new PrismGroupMembership(
                        member.structureId(),
                        cluster.clusterId(),
                        member.similarityToRepresentative(),
                        member.structureId().equals(cluster.representativeStructureId()) ? "representative" : "member",
                        Map.of()
                ));
            }
        }
        Map<String, Object> provenance = Map.of(
                "source", "structurized_similarity_clustering",
                "sessionId", session.sessionId(),
                "analysisId", analysisId,
                "sourceRowSetId", sourceRowSet.id(),
                "descriptor", result.descriptor(),
                "threshold", result.threshold()
        );
        PrismGrouping grouping = new PrismGrouping(
                analysisId,
                label,
                "Exclusive similarity clustering generated by Structurized.",
                sourceRowSet.id(),
                PrismGroupingMode.EXCLUSIVE,
                groups,
                memberships,
                analysisId + ".cluster_id",
                provenance
        );
        PrismOperationResult.Builder publication = PrismOperationResult.builder()
                .addGrouping(grouping, publishColumns)
                .provenance("analysisId", analysisId)
                .provenance("analysisType", "similarity_clustering");
        if (publishColumns) {
            PrismColumnSchema similaritySchema = new PrismColumnSchema(
                    analysisId + ".similarity_to_representative",
                    PrismColumnType.NUMERIC,
                    label + " similarity to representative",
                    "similarity",
                    "analysis_result",
                    null,
                    null,
                    "higher_is_better",
                    null,
                    provenance
            );
            publication.addColumnByRowId(new RowIdMaterializedColumnData(
                    similaritySchema,
                    similarities,
                    provenance
            ));
        }
        return publication.build();
    }

    private static PrismClusterSummary clusterSummary(PrismClusteringAnalysis analysis,
                                                       SimilarityCluster cluster) {
        PrismRowStructureEntry representative = analysis.structure(cluster.representativeStructureId());
        double minimum = cluster.members().stream()
                .mapToDouble(ClusterMember::similarityToRepresentative)
                .min().orElse(1.0);
        double mean = cluster.members().stream()
                .mapToDouble(ClusterMember::similarityToRepresentative)
                .average().orElse(1.0);
        List<PrismClusterMember> examples = cluster.members().stream()
                .sorted(Comparator.comparingDouble(ClusterMember::similarityToRepresentative).reversed()
                        .thenComparingInt(ClusterMember::inputIndex))
                .limit(3)
                .map(member -> member(analysis, member))
                .toList();
        List<PrismClusterCrossNeighbor> neighbors = cluster.nearestCrossNeighbors().stream()
                .map(neighbor -> new PrismClusterCrossNeighbor(
                        neighbor.structureId(),
                        neighbor.label(),
                        neighbor.clusterId(),
                        neighbor.similarityToRepresentative()
                ))
                .toList();
        return new PrismClusterSummary(
                cluster.clusterId(),
                cluster.representativeStructureId(),
                cluster.representativeLabel(),
                representative == null ? null : representative.smiles(),
                cluster.size(),
                minimum,
                mean,
                examples,
                neighbors
        );
    }

    private static PrismClusterMember member(PrismClusteringAnalysis analysis, ClusterMember member) {
        PrismRowStructureEntry entry = analysis.structure(member.structureId());
        return new PrismClusterMember(
                member.structureId(),
                entry == null ? null : entry.subjectId(),
                entry == null ? null : entry.structureId(),
                member.label(),
                entry == null ? null : entry.smiles(),
                member.similarityToRepresentative()
        );
    }

    private PrismClusteringAnalysis analysis(ManagedPrismSession session, String analysisId) {
        return artifacts.require(
                session.sessionId(),
                requireText(analysisId, "analysisId"),
                PrismClusteringAnalysis.class
        );
    }

    private static SimilarityCluster cluster(PrismClusteringAnalysis analysis, String clusterId) {
        String normalizedClusterId = requireText(clusterId, "clusterId");
        return analysis.result().clusters().stream()
                .filter(cluster -> cluster.clusterId().equals(normalizedClusterId))
                .findFirst()
                .orElseThrow(() -> new ChemOperationException(
                        "prism_cluster_not_found",
                        "Cluster " + normalizedClusterId + " does not exist in Prism analysis "
                                + analysis.summary().analysisId() + "."
                ));
    }

    private static SimilarityClusteringConfig config(ClusterPrismRowSetRequest request) {
        double threshold = request.threshold() == null
                ? SimilarityClusteringConfig.DEFAULT_THRESHOLD
                : request.threshold();
        int maxCrossNeighbors = request.maxCrossNeighbors() == null
                ? SimilarityClusteringConfig.DEFAULT_MAX_CROSS_NEIGHBORS
                : request.maxCrossNeighbors();
        try {
            return new SimilarityClusteringConfig(request.descriptor(), threshold, maxCrossNeighbors);
        } catch (IllegalArgumentException exception) {
            throw new ChemOperationException("invalid_clustering_config", exception.getMessage(), exception);
        }
    }

    private String resolveAnalysisId(ManagedPrismSession session, String requestedId) {
        if (requestedId != null && !requestedId.isBlank()) {
            String id = requestedId.trim();
            if (containsResult(session, id)) {
                throw new ChemOperationException(
                        "duplicate_prism_analysis",
                        "Prism analysis " + id + " already exists."
                );
            }
            return id;
        }
        int index = 1;
        String candidate;
        do {
            candidate = "clustering_" + index++;
        } while (containsResult(session, candidate));
        return candidate;
    }

    private boolean containsResult(ManagedPrismSession session, String analysisId) {
        return artifacts.contains(session.sessionId(), analysisId)
                || session.workspace().groupings().stream().anyMatch(grouping -> grouping.id().equals(analysisId))
                || session.workspace().table().findColumn(analysisId + ".cluster_id").isPresent();
    }

    private static PrismRowSetSummary rowSetSummary(ManagedPrismSession session, PrismRowSet rowSet) {
        return new PrismRowSetSummary(
                session.sessionId(),
                rowSet.id(),
                rowSet.name(),
                rowSet.description(),
                rowSet.rowIds().size(),
                rowSet.provenance()
        );
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ChemOperationException("invalid_arguments", name + " must not be blank.");
        }
        return value.trim();
    }

    private static int safeLimit(int limit) {
        return Math.min(PAGE_LIMIT_MAX, Math.max(1, limit <= 0 ? PAGE_LIMIT_DEFAULT : limit));
    }
}
