package tech.molecules.structurized.clustering;

import com.actelion.research.chem.Molecule;
import com.actelion.research.chem.StereoMolecule;
import com.actelion.research.chem.descriptor.DescriptorHandlerSkeletonSpheres;
import tech.molecules.structurized.OpenChemLibUtil;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class SimilarityClusteringEngine {
    public SimilarityClusteringResult cluster(List<ClusteringInputMolecule> molecules, SimilarityClusteringConfig cfg) {
        Objects.requireNonNull(molecules, "molecules");
        if (cfg == null) {
            cfg = new SimilarityClusteringConfig();
        }

        DescriptorHandlerSkeletonSpheres descriptorHandler = DescriptorHandlerSkeletonSpheres.getDefaultInstance();
        List<PreparedMolecule> prepared = prepare(molecules, descriptorHandler);
        double[][] similarities = computeSimilarities(prepared, descriptorHandler);
        List<UnclusteredMolecule> unclustered = prepared.stream()
                .filter(molecule -> molecule.descriptor == null)
                .map(molecule -> new UnclusteredMolecule(molecule.index, molecule.input.structureId(), molecule.input.label(), "DESCRIPTOR_FAILED"))
                .toList();

        BitSet unassigned = new BitSet(prepared.size());
        for (PreparedMolecule molecule : prepared) {
            if (molecule.descriptor != null) {
                unassigned.set(molecule.index);
            }
        }

        List<MutableCluster> mutableClusters = new ArrayList<>();
        while (!unassigned.isEmpty()) {
            LeaderScore leader = selectLeader(prepared, similarities, unassigned, cfg.threshold());
            if (leader == null) {
                break;
            }
            String clusterId = "cluster_" + (mutableClusters.size() + 1);
            List<Integer> members = new ArrayList<>();
            for (int index = unassigned.nextSetBit(0); index >= 0; index = unassigned.nextSetBit(index + 1)) {
                if (index == leader.index || similarities[leader.index][index] >= cfg.threshold()) {
                    members.add(index);
                }
            }
            for (int member : members) {
                unassigned.clear(member);
            }
            members.sort(Comparator
                    .comparingInt((Integer index) -> index == leader.index ? 0 : 1)
                    .thenComparingDouble((Integer index) -> -similarities[leader.index][index])
                    .thenComparingInt(Integer::intValue));
            mutableClusters.add(new MutableCluster(clusterId, leader.index, List.copyOf(members)));
        }

        Map<Integer, String> clusterIdByInputIndex = new HashMap<>();
        for (MutableCluster cluster : mutableClusters) {
            for (int member : cluster.memberIndices) {
                clusterIdByInputIndex.put(member, cluster.clusterId);
            }
        }

        List<SimilarityCluster> clusters = new ArrayList<>();
        for (MutableCluster cluster : mutableClusters) {
            PreparedMolecule representative = prepared.get(cluster.representativeIndex);
            List<ClusterMember> members = cluster.memberIndices.stream()
                    .map(index -> member(prepared.get(index), similarities[cluster.representativeIndex][index]))
                    .toList();
            clusters.add(new SimilarityCluster(
                    cluster.clusterId,
                    cluster.representativeIndex,
                    representative.input.structureId(),
                    representative.input.label(),
                    members,
                    crossNeighbors(prepared, similarities, cluster, clusterIdByInputIndex, cfg.maxCrossNeighbors())
            ));
        }

        int singletonCount = (int) clusters.stream().filter(cluster -> cluster.members().size() == 1).count();
        return new SimilarityClusteringResult(
                cfg.descriptor(),
                SimilarityClusteringConfig.STRATEGY_GREEDY_LEADERS,
                cfg.threshold(),
                molecules.size(),
                clusters.size(),
                singletonCount,
                unclustered.size(),
                clusters,
                unclustered
        );
    }

    private static List<PreparedMolecule> prepare(
            List<ClusteringInputMolecule> molecules,
            DescriptorHandlerSkeletonSpheres descriptorHandler
    ) {
        List<PreparedMolecule> prepared = new ArrayList<>(molecules.size());
        for (int index = 0; index < molecules.size(); index++) {
            ClusteringInputMolecule input = molecules.get(index);
            StereoMolecule molecule = new StereoMolecule(input.molecule());
            molecule.ensureHelperArrays(Molecule.cHelperSymmetrySimple);
            byte[] descriptor = null;
            try {
                descriptor = descriptorHandler.createDescriptor(molecule);
                if (descriptorHandler.calculationFailed(descriptor)) {
                    descriptor = null;
                }
            } catch (RuntimeException ignored) {
                descriptor = null;
            }
            prepared.add(new PreparedMolecule(index, input, descriptor, OpenChemLibUtil.heavyAtomCount(molecule)));
        }
        return List.copyOf(prepared);
    }

    private static double[][] computeSimilarities(
            List<PreparedMolecule> prepared,
            DescriptorHandlerSkeletonSpheres descriptorHandler
    ) {
        double[][] similarities = new double[prepared.size()][prepared.size()];
        for (int i = 0; i < prepared.size(); i++) {
            for (int j = i; j < prepared.size(); j++) {
                double similarity;
                if (prepared.get(i).descriptor == null || prepared.get(j).descriptor == null) {
                    similarity = Double.NaN;
                } else if (i == j) {
                    similarity = 1.0;
                } else {
                    similarity = descriptorHandler.getSimilarity(prepared.get(i).descriptor, prepared.get(j).descriptor);
                }
                similarities[i][j] = similarity;
                similarities[j][i] = similarity;
            }
        }
        return similarities;
    }

    private static LeaderScore selectLeader(
            List<PreparedMolecule> prepared,
            double[][] similarities,
            BitSet unassigned,
            double threshold
    ) {
        LeaderScore best = null;
        for (int index = unassigned.nextSetBit(0); index >= 0; index = unassigned.nextSetBit(index + 1)) {
            int neighborCount = 0;
            double similaritySum = 0.0;
            for (int other = unassigned.nextSetBit(0); other >= 0; other = unassigned.nextSetBit(other + 1)) {
                if (other == index) {
                    continue;
                }
                double similarity = similarities[index][other];
                if (!Double.isNaN(similarity) && similarity >= threshold) {
                    neighborCount++;
                    similaritySum += similarity;
                }
            }
            double meanSimilarity = neighborCount == 0 ? 0.0 : similaritySum / neighborCount;
            LeaderScore score = new LeaderScore(index, neighborCount, meanSimilarity, prepared.get(index).heavyAtomCount);
            if (best == null || LEADER_ORDER.compare(score, best) < 0) {
                best = score;
            }
        }
        return best;
    }

    private static List<ClusterCrossNeighbor> crossNeighbors(
            List<PreparedMolecule> prepared,
            double[][] similarities,
            MutableCluster cluster,
            Map<Integer, String> clusterIdByInputIndex,
            int maxCrossNeighbors
    ) {
        if (maxCrossNeighbors == 0) {
            return List.of();
        }
        BitSet members = new BitSet(prepared.size());
        for (int member : cluster.memberIndices) {
            members.set(member);
        }
        List<CrossNeighborCandidate> candidates = new ArrayList<>();
        for (PreparedMolecule molecule : prepared) {
            if (molecule.descriptor == null || members.get(molecule.index)) {
                continue;
            }
            String neighborClusterId = clusterIdByInputIndex.get(molecule.index);
            if (neighborClusterId == null) {
                continue;
            }
            double similarity = similarities[cluster.representativeIndex][molecule.index];
            if (Double.isNaN(similarity)) {
                continue;
            }
            candidates.add(new CrossNeighborCandidate(molecule.index, neighborClusterId, similarity));
        }
        candidates.sort(Comparator
                .comparingDouble((CrossNeighborCandidate candidate) -> candidate.similarity).reversed()
                .thenComparing(candidate -> candidate.clusterId)
                .thenComparingInt(candidate -> candidate.index));
        List<ClusterCrossNeighbor> result = new ArrayList<>(Math.min(maxCrossNeighbors, candidates.size()));
        for (int i = 0; i < candidates.size() && i < maxCrossNeighbors; i++) {
            CrossNeighborCandidate candidate = candidates.get(i);
            PreparedMolecule molecule = prepared.get(candidate.index);
            result.add(new ClusterCrossNeighbor(
                    molecule.input.structureId(),
                    molecule.input.label(),
                    candidate.clusterId,
                    roundSimilarity(candidate.similarity)
            ));
        }
        return List.copyOf(result);
    }

    private static ClusterMember member(PreparedMolecule molecule, double similarity) {
        return new ClusterMember(
                molecule.index,
                molecule.input.structureId(),
                molecule.input.label(),
                roundSimilarity(similarity)
        );
    }

    private static double roundSimilarity(double similarity) {
        if (Double.isNaN(similarity)) {
            return Double.NaN;
        }
        return Math.round(similarity * 1_000_000.0) / 1_000_000.0;
    }

    private static final Comparator<LeaderScore> LEADER_ORDER = Comparator
            .comparingInt((LeaderScore score) -> score.neighborCount).reversed()
            .thenComparingDouble((LeaderScore score) -> score.meanSimilarity).reversed()
            .thenComparingInt((LeaderScore score) -> score.heavyAtomCount).reversed()
            .thenComparingInt(score -> score.index);

    private record PreparedMolecule(int index, ClusteringInputMolecule input, byte[] descriptor, int heavyAtomCount) {}

    private record LeaderScore(int index, int neighborCount, double meanSimilarity, int heavyAtomCount) {}

    private record MutableCluster(String clusterId, int representativeIndex, List<Integer> memberIndices) {}

    private record CrossNeighborCandidate(int index, String clusterId, double similarity) {}
}
