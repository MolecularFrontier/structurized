package tech.molecules.structurized.ai.clustering;

import org.junit.jupiter.api.Test;
import tech.molecules.structurized.ai.model.ChemOperationException;
import tech.molecules.structurized.ai.model.RegisterStructureRequest;
import tech.molecules.structurized.ai.repository.InMemoryStructureRepositoryService;
import tech.molecules.structurized.ai.repository.StructureRepositoryService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimilarityClusteringAiServiceTest {
    @Test
    void clustersRepositoryAndStoresResult() {
        TestContext ctx = context();
        ctx.repositories.registerStructure(new RegisterStructureRequest("c1ccccc1", "session", "benzene_a", "Benzene A", Map.of()));
        ctx.repositories.registerStructure(new RegisterStructureRequest("CCO", "session", "ethanol", "Ethanol", Map.of()));
        ctx.repositories.registerStructure(new RegisterStructureRequest("c1ccccc1", "session", "benzene_b", "Benzene B", Map.of()));

        SimilarityClusteringAiService.SimilarityClusteringRecord record = ctx.service.clusterStructures(
                "rough1", "session", null, "skelspheres", 1.0, 2);
        SimilarityClusteringAiService.SimilarityClusteringView view = ctx.service.getClustering("rough1", true, 0, 10);
        SimilarityClusteringAiService.SimilarityClusterView cluster = ctx.service.getCluster("rough1", "cluster_1");

        assertEquals("rough1", record.clusteringId());
        assertEquals(3, record.moleculeCount());
        assertEquals(2, record.clusterCount());
        assertEquals("benzene_a", view.clusters().getFirst().representativeStructureId());
        assertEquals(List.of("benzene_a", "benzene_b"), cluster.cluster().exampleMembers().stream().map(member -> member.structureId()).toList());
        assertEquals("c1ccccc1", cluster.cluster().representativeSmiles());

        SimilarityClusteringAiService.ClusterMembersView members = ctx.service.getClusterMembers("rough1", "cluster_1", 1, 1);
        assertEquals(2, members.totalMembers());
        assertEquals(List.of("benzene_b"), members.members().stream().map(member -> member.structureId()).toList());
    }

    @Test
    void supportsSelectedStructuresAndSingletonFiltering() {
        TestContext ctx = context();
        ctx.repositories.registerStructure(new RegisterStructureRequest("c1ccccc1", "session", "benzene_a", "Benzene A", Map.of()));
        ctx.repositories.registerStructure(new RegisterStructureRequest("CCO", "session", "ethanol", "Ethanol", Map.of()));
        ctx.repositories.registerStructure(new RegisterStructureRequest("c1ccccc1", "session", "benzene_b", "Benzene B", Map.of()));

        ctx.service.clusterStructures("selected", "session", List.of("benzene_a", "benzene_b"), null, 1.0, 1);
        SimilarityClusteringAiService.SimilarityClusteringView withoutSingletons = ctx.service.getClustering("selected", false, 0, 10);

        assertEquals(1, withoutSingletons.summary().clusterCount());
        assertEquals(1, withoutSingletons.clusters().size());
        assertEquals(2, withoutSingletons.clusters().getFirst().size());
    }

    @Test
    void invalidConfigFailsExplicitly() {
        TestContext ctx = context();
        ChemOperationException exception = assertThrows(
                ChemOperationException.class,
                () -> ctx.service.clusterStructures("bad", "session", null, "ffp512", 0.8, 5)
        );
        assertEquals("invalid_clustering_config", exception.code());
    }

    @Test
    void missingClusterFailsExplicitly() {
        TestContext ctx = context();
        ctx.repositories.registerStructure(new RegisterStructureRequest("CCO", "session", "ethanol", "Ethanol", Map.of()));
        ctx.service.clusterStructures("rough", "session", null, null, null, 5);

        ChemOperationException exception = assertThrows(ChemOperationException.class, () -> ctx.service.getCluster("rough", "cluster_9"));

        assertEquals("cluster_not_found", exception.code());
        assertTrue(ctx.service.listClusterings().stream().anyMatch(record -> record.clusteringId().equals("rough")));
    }

    private static TestContext context() {
        StructureRepositoryService repositories = new InMemoryStructureRepositoryService();
        return new TestContext(repositories, new SimilarityClusteringAiService(repositories));
    }

    private record TestContext(StructureRepositoryService repositories, SimilarityClusteringAiService service) {}
}
