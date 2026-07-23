package tech.molecules.structurized.clustering;

import com.actelion.research.chem.SmilesParser;
import com.actelion.research.chem.StereoMolecule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimilarityClusteringEngineTest {
    @Test
    void clustersIdenticalStructuresAtStrictThreshold() throws Exception {
        SimilarityClusteringResult result = new SimilarityClusteringEngine().cluster(List.of(
                input("benzene_a", "c1ccccc1"),
                input("ethanol", "CCO"),
                input("benzene_b", "c1ccccc1")
        ), new SimilarityClusteringConfig("skelspheres", 1.0, 2));

        assertEquals("skelspheres", result.descriptor());
        assertEquals("greedy_leaders", result.strategy());
        assertEquals(3, result.moleculeCount());
        assertEquals(2, result.clusterCount());
        assertEquals(1, result.singletonCount());
        assertEquals(0, result.unclusteredCount());
        assertEquals("cluster_1", result.clusters().getFirst().clusterId());
        assertEquals(List.of("benzene_a", "benzene_b"), result.clusters().getFirst().members().stream().map(ClusterMember::structureId).toList());
        assertEquals(1.0, result.clusters().getFirst().members().getLast().similarityToRepresentative());
    }

    @Test
    void emptyAndSingleMoleculeInputsAreValid() throws Exception {
        SimilarityClusteringEngine engine = new SimilarityClusteringEngine();

        SimilarityClusteringResult empty = engine.cluster(List.of(), new SimilarityClusteringConfig());
        SimilarityClusteringResult single = engine.cluster(List.of(input("ethanol", "CCO")), new SimilarityClusteringConfig());

        assertEquals(0, empty.clusterCount());
        assertEquals(1, single.clusterCount());
        assertEquals(1, single.singletonCount());
        assertEquals("ethanol", single.clusters().getFirst().representativeStructureId());
    }

    @Test
    void rejectsInvalidConfig() {
        assertThrows(IllegalArgumentException.class, () -> new SimilarityClusteringConfig("ffp512", 0.8, 5));
        assertThrows(IllegalArgumentException.class, () -> new SimilarityClusteringConfig("skelspheres", 1.2, 5));
        assertThrows(IllegalArgumentException.class, () -> new SimilarityClusteringConfig("skelspheres", 0.8, -1));
    }

    @Test
    void zeroThresholdPutsAllDescriptorSuccessfulMoleculesInOneCluster() throws Exception {
        SimilarityClusteringResult result = new SimilarityClusteringEngine().cluster(List.of(
                input("benzene", "c1ccccc1"),
                input("ethanol", "CCO"),
                input("cyclohexane", "C1CCCCC1")
        ), new SimilarityClusteringConfig("skelspheres", 0.0, 1));

        assertEquals(1, result.clusterCount());
        assertEquals(3, result.clusters().getFirst().members().size());
        assertTrue(result.clusters().getFirst().nearestCrossNeighbors().isEmpty());
    }

    private static ClusteringInputMolecule input(String id, String smiles) throws Exception {
        StereoMolecule molecule = new StereoMolecule();
        new SmilesParser().parse(molecule, smiles);
        return new ClusteringInputMolecule(id, id, molecule);
    }
}
