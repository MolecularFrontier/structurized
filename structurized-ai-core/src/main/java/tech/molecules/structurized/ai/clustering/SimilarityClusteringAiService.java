package tech.molecules.structurized.ai.clustering;

import tech.molecules.structurized.ai.model.ChemOperationException;
import tech.molecules.structurized.ai.model.StructureRecord;
import tech.molecules.structurized.ai.model.StructureRef;
import tech.molecules.structurized.ai.repository.StoredStructure;
import tech.molecules.structurized.ai.repository.StructureRepositoryService;
import tech.molecules.structurized.clustering.ClusterCrossNeighbor;
import tech.molecules.structurized.clustering.ClusterMember;
import tech.molecules.structurized.clustering.ClusteringInputMolecule;
import tech.molecules.structurized.clustering.SimilarityCluster;
import tech.molecules.structurized.clustering.SimilarityClusteringConfig;
import tech.molecules.structurized.clustering.SimilarityClusteringEngine;
import tech.molecules.structurized.clustering.SimilarityClusteringResult;
import tech.molecules.structurized.clustering.UnclusteredMolecule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Session-scoped rough similarity clustering service for agent dataset exploration.
 */
public final class SimilarityClusteringAiService {
    private static final int PAGE_LIMIT_DEFAULT = 100;
    private static final int PAGE_LIMIT_MAX = 500;

    private final StructureRepositoryService repositories;
    private final Map<String, StoredClustering> clusterings = new LinkedHashMap<>();
    private int nextClusteringIndex = 1;

    public SimilarityClusteringAiService(StructureRepositoryService repositories) {
        this.repositories = Objects.requireNonNull(repositories, "repositories");
    }

    public synchronized SimilarityClusteringRecord clusterStructures(
            String clusteringId,
            String repositoryId,
            List<String> structureIds,
            String descriptor,
            Double threshold,
            int maxCrossNeighbors
    ) {
        String id = normalizeId(clusteringId == null || clusteringId.isBlank() ? generatedClusteringId() : clusteringId, "clustering_id");
        if (clusterings.containsKey(id)) {
            throw new ChemOperationException("duplicate_clustering", "Clustering " + id + " already exists.");
        }
        String repoId = normalizeId(repositoryId, "repository_id");
        double resolvedThreshold = threshold == null ? SimilarityClusteringConfig.DEFAULT_THRESHOLD : threshold;
        SimilarityClusteringConfig config;
        try {
            config = new SimilarityClusteringConfig(descriptor, resolvedThreshold, maxCrossNeighbors);
        } catch (IllegalArgumentException e) {
            throw new ChemOperationException("invalid_clustering_config", e.getMessage(), e);
        }

        List<StoredStructure> structures = resolveStructures(repoId, structureIds);
        List<ClusteringInputMolecule> inputs = structures.stream()
                .map(structure -> new ClusteringInputMolecule(
                        structure.record().structureId(),
                        structure.record().label(),
                        structure.snapshot().moleculeCopy()
                ))
                .toList();
        SimilarityClusteringResult result = new SimilarityClusteringEngine().cluster(inputs, config);
        StoredClustering stored = new StoredClustering(id, repoId, result);
        clusterings.put(id, stored);
        return stored.toRecord();
    }

    public synchronized SimilarityClusteringView getClustering(String clusteringId, boolean includeSingletons, int offset, int limit) {
        StoredClustering stored = clustering(clusteringId);
        int safeOffset = Math.max(0, offset);
        int safeLimit = safeLimit(limit);
        List<ClusterSummary> clusters = stored.result().clusters().stream()
                .filter(cluster -> includeSingletons || cluster.members().size() > 1)
                .map(SimilarityClusteringAiService::summary)
                .toList();
        return new SimilarityClusteringView(stored.toRecord(), page(clusters, safeOffset, safeLimit), stored.result().unclustered());
    }

    public synchronized SimilarityClusterView getCluster(String clusteringId, String clusterId) {
        StoredClustering stored = clustering(clusteringId);
        SimilarityCluster cluster = stored.result().clusters().stream()
                .filter(candidate -> candidate.clusterId().equals(clusterId))
                .findFirst()
                .orElseThrow(() -> new ChemOperationException("cluster_not_found", "Cluster " + clusterId + " is not part of clustering " + clusteringId + "."));
        return new SimilarityClusterView(stored.clusteringId(), stored.repositoryId(), cluster);
    }

    public synchronized List<SimilarityClusteringRecord> listClusterings() {
        return clusterings.values().stream().map(StoredClustering::toRecord).toList();
    }

    private List<StoredStructure> resolveStructures(String repositoryId, List<String> structureIds) {
        if (structureIds != null && !structureIds.isEmpty()) {
            return structureIds.stream()
                    .map(structureId -> repositories.getStructure(new StructureRef(repositoryId, structureId)))
                    .toList();
        }
        List<StoredStructure> result = new ArrayList<>();
        int offset = 0;
        while (true) {
            List<StructureRecord> page = repositories.listStructures(repositoryId, offset, PAGE_LIMIT_MAX);
            if (page.isEmpty()) {
                break;
            }
            for (StructureRecord record : page) {
                result.add(repositories.getStructure(record.ref()));
            }
            offset += page.size();
        }
        return List.copyOf(result);
    }

    private StoredClustering clustering(String clusteringId) {
        StoredClustering clustering = clusterings.get(normalizeId(clusteringId, "clustering_id"));
        if (clustering == null) {
            throw new ChemOperationException("clustering_not_found", "Clustering " + clusteringId + " does not exist.");
        }
        return clustering;
    }

    private String generatedClusteringId() {
        String id;
        do {
            id = "clustering_" + nextClusteringIndex++;
        } while (clusterings.containsKey(id));
        return id;
    }

    private static ClusterSummary summary(SimilarityCluster cluster) {
        double minSimilarity = cluster.members().stream()
                .mapToDouble(ClusterMember::similarityToRepresentative)
                .min()
                .orElse(1.0);
        double meanSimilarity = cluster.members().stream()
                .mapToDouble(ClusterMember::similarityToRepresentative)
                .average()
                .orElse(1.0);
        return new ClusterSummary(
                cluster.clusterId(),
                cluster.representativeStructureId(),
                cluster.representativeLabel(),
                cluster.members().size(),
                roundSimilarity(minSimilarity),
                roundSimilarity(meanSimilarity),
                cluster.members().stream()
                        .sorted(Comparator
                                .comparingDouble(ClusterMember::similarityToRepresentative).reversed()
                                .thenComparingInt(ClusterMember::inputIndex))
                        .map(MemberSummary::from)
                        .toList(),
                cluster.nearestCrossNeighbors()
        );
    }

    private static double roundSimilarity(double similarity) {
        if (Double.isNaN(similarity)) {
            return Double.NaN;
        }
        return Math.round(similarity * 1_000_000.0) / 1_000_000.0;
    }

    private static String normalizeId(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ChemOperationException("invalid_arguments", "Missing required argument: " + name);
        }
        return value.trim();
    }

    private static int safeLimit(int limit) {
        return Math.min(PAGE_LIMIT_MAX, Math.max(1, limit <= 0 ? PAGE_LIMIT_DEFAULT : limit));
    }

    private static <T> List<T> page(List<T> values, int offset, int limit) {
        int from = Math.min(Math.max(0, offset), values.size());
        int to = Math.min(from + Math.max(1, limit), values.size());
        return List.copyOf(values.subList(from, to));
    }

    private record StoredClustering(String clusteringId, String repositoryId, SimilarityClusteringResult result) {
        private SimilarityClusteringRecord toRecord() {
            return new SimilarityClusteringRecord(
                    clusteringId,
                    repositoryId,
                    result.descriptor(),
                    result.strategy(),
                    result.threshold(),
                    result.moleculeCount(),
                    result.clusterCount(),
                    result.singletonCount(),
                    result.unclusteredCount()
            );
        }
    }

    public record SimilarityClusteringRecord(
            String clusteringId,
            String repositoryId,
            String descriptor,
            String strategy,
            double threshold,
            int moleculeCount,
            int clusterCount,
            int singletonCount,
            int unclusteredCount
    ) {}

    public record SimilarityClusteringView(
            SimilarityClusteringRecord summary,
            List<ClusterSummary> clusters,
            List<UnclusteredMolecule> unclustered
    ) {}

    public record SimilarityClusterView(String clusteringId, String repositoryId, SimilarityCluster cluster) {}

    public record ClusterSummary(
            String clusterId,
            String representativeStructureId,
            String representativeLabel,
            int size,
            double minSimilarityToRepresentative,
            double meanSimilarityToRepresentative,
            List<MemberSummary> members,
            List<ClusterCrossNeighbor> nearestCrossNeighbors
    ) {}

    public record MemberSummary(String structureId, String label, double similarityToRepresentative) {
        private static MemberSummary from(ClusterMember member) {
            return new MemberSummary(member.structureId(), member.label(), member.similarityToRepresentative());
        }
    }
}
