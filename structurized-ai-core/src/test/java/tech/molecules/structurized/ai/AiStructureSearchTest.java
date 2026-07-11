package tech.molecules.structurized.ai;

import org.junit.jupiter.api.Test;
import tech.molecules.structurized.ai.model.ChemOperationException;
import tech.molecules.structurized.ai.model.CreateRepositoryRequest;
import tech.molecules.structurized.ai.model.ExactStructureSearchMatch;
import tech.molecules.structurized.ai.model.ExactStructureSearchRequest;
import tech.molecules.structurized.ai.model.ExactStructureSearchResult;
import tech.molecules.structurized.ai.model.RegisterStructureRequest;
import tech.molecules.structurized.ai.model.SubstructureSearchRequest;
import tech.molecules.structurized.ai.model.SubstructureSearchResult;
import tech.molecules.structurized.ai.repository.InMemoryStructureRepositoryService;
import tech.molecules.structurized.ai.repository.StructureRepositoryService;
import tech.molecules.structurized.ai.search.OclStructureSearchService;
import tech.molecules.structurized.ai.search.StructureSearchService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiStructureSearchTest {

    @Test
    void exactSearchFindsWholeRecordByCanonicalIdentity() {
        TestContext ctx = context();
        ctx.repositories.registerStructure(new RegisterStructureRequest("CCO", "session", "ethanol", "Ethanol", Map.of()));
        ctx.repositories.registerStructure(new RegisterStructureRequest("CCN", "session", "ethylamine", "Ethylamine", Map.of()));

        ExactStructureSearchResult result = ctx.search.searchExactStructure(new ExactStructureSearchRequest("OCC"));

        assertEquals("whole_record", result.scope().componentScope());
        assertEquals(2, result.scope().structuresSearched());
        assertEquals(1, result.summary().matchingStructures());
        assertEquals(List.of(new ExactStructureSearchMatch("session", "ethanol", "Ethanol", null)), result.matches());
        assertTrue(result.identityDefinition().contains("no tautomer"));
    }

    @Test
    void exactSearchDoesNotNormalizeChargeOrProtonation() {
        TestContext ctx = context();
        ctx.repositories.registerStructure(new RegisterStructureRequest("C[NH3+]", "session", "methylammonium", "Methylammonium", Map.of()));

        ExactStructureSearchResult result = ctx.search.searchExactStructure(new ExactStructureSearchRequest("CN"));

        assertEquals(0, result.summary().matchingStructures());
        assertEquals(List.of(), result.matches());
    }

    @Test
    void exactSearchCanFindAnyComponentInsideDisconnectedRecord() {
        TestContext ctx = context();
        ctx.repositories.registerStructure(new RegisterStructureRequest("CC.O", "session", "mixture", "Mixture", Map.of()));

        ExactStructureSearchResult result = ctx.search.searchExactStructure(
                new ExactStructureSearchRequest("O", null, "any_component")
        );

        assertEquals(1, result.summary().matchingStructures());
        assertEquals("c2", result.matches().getFirst().componentId());
    }

    @Test
    void exactLargestScopeComparesOnlyLargestComponents() {
        TestContext ctx = context();
        ctx.repositories.registerStructure(new RegisterStructureRequest("CC.O", "session", "mixture", "Mixture", Map.of()));

        ExactStructureSearchResult oxygen = ctx.search.searchExactStructure(
                new ExactStructureSearchRequest("O", null, "largest")
        );
        ExactStructureSearchResult ethane = ctx.search.searchExactStructure(
                new ExactStructureSearchRequest("CC", null, "largest")
        );

        assertEquals(0, oxygen.summary().matchingStructures());
        assertEquals(1, ethane.summary().matchingStructures());
        assertEquals("c1", ethane.matches().getFirst().componentId());
    }

    @Test
    void substructureSearchFindsSmilesQueryAndReportsMappings() {
        TestContext ctx = context();
        ctx.repositories.registerStructure(new RegisterStructureRequest("c1ccncc1", "session", "pyridine", "Pyridine", Map.of()));
        ctx.repositories.registerStructure(new RegisterStructureRequest("c1ccccc1", "session", "benzene", "Benzene", Map.of()));

        SubstructureSearchResult result = ctx.search.searchSubstructure(new SubstructureSearchRequest("c1ccncc1"));

        assertEquals("smiles", result.query().type());
        assertEquals(2, result.scope().structuresSearched());
        assertEquals(1, result.summary().matchingStructures());
        assertEquals("pyridine", result.matches().getFirst().structureId());
        assertEquals("c1", result.matches().getFirst().componentId());
        assertEquals(1, result.matches().getFirst().matchCount());
        assertEquals(6, result.matches().getFirst().atomMappings().getFirst().targetAtomIds().size());
        assertEquals("a1", result.matches().getFirst().atomMappings().getFirst().queryToTarget().get("q1"));
    }

    @Test
    void substructureSearchSupportsSmartsQueries() {
        TestContext ctx = context();
        ctx.repositories.registerStructure(new RegisterStructureRequest("CCN", "session", "ethylamine", "Ethylamine", Map.of()));
        ctx.repositories.registerStructure(new RegisterStructureRequest("CCO", "session", "ethanol", "Ethanol", Map.of()));

        SubstructureSearchResult result = ctx.search.searchSubstructure(
                new SubstructureSearchRequest("[NX3;H2]", "smarts", null, "all", 100, 1, true)
        );

        assertEquals("smarts", result.query().type());
        assertEquals(1, result.summary().matchingStructures());
        assertEquals("ethylamine", result.matches().getFirst().structureId());
        assertEquals(List.of("a3"), result.matches().getFirst().atomMappings().getFirst().targetAtomIds());
    }

    @Test
    void invalidSmartsReturnsQueryParseError() {
        TestContext ctx = context();
        ctx.repositories.registerStructure(new RegisterStructureRequest("CCN"));

        ChemOperationException exception = assertThrows(
                ChemOperationException.class,
                () -> ctx.search.searchSubstructure(new SubstructureSearchRequest("[NX3", "smarts", null, "all", 100, 1, true))
        );

        assertEquals("query_parse_error", exception.code());
    }

    @Test
    void substructureComponentScopeCanRestrictToLargestComponent() {
        TestContext ctx = context();
        ctx.repositories.registerStructure(new RegisterStructureRequest("CC.O", "session", "mixture", "Mixture", Map.of()));

        SubstructureSearchResult all = ctx.search.searchSubstructure(
                new SubstructureSearchRequest("O", "smiles", null, "all", 100, 1, true)
        );
        SubstructureSearchResult largest = ctx.search.searchSubstructure(
                new SubstructureSearchRequest("O", "smiles", null, "largest", 100, 1, true)
        );

        assertEquals(1, all.summary().matchingStructures());
        assertEquals("c2", all.matches().getFirst().componentId());
        assertEquals(0, largest.summary().matchingStructures());
    }

    @Test
    void repositoryScopeLimitsSearch() {
        TestContext ctx = context();
        ctx.repositories.registerStructure(new RegisterStructureRequest("CCO", "session", "ethanol", "Ethanol", Map.of()));
        ctx.repositories.createRepository(new CreateRepositoryRequest("reference", "Reference", null, true));
        ctx.repositories.registerStructure(new RegisterStructureRequest("c1ccncc1", "reference", "pyridine", "Pyridine", Map.of()));

        SubstructureSearchResult result = ctx.search.searchSubstructure(
                new SubstructureSearchRequest("c1ccncc1", "smiles", List.of("reference"), "all", 100, 1, true)
        );

        assertEquals(List.of("reference"), result.scope().repositoryIds());
        assertEquals(1, result.scope().structuresSearched());
        assertEquals("pyridine", result.matches().getFirst().structureId());
    }

    @Test
    void substructureSearchDeduplicatesAndLimitsAtomMappings() {
        TestContext ctx = context();
        ctx.repositories.registerStructure(new RegisterStructureRequest("CCNCC", "session", "diethylamine", "Diethylamine", Map.of()));

        SubstructureSearchResult result = ctx.search.searchSubstructure(
                new SubstructureSearchRequest("C", "smiles", null, "all", 100, 2, true)
        );

        assertEquals(1, result.summary().matchingStructures());
        assertEquals(4, result.matches().getFirst().matchCount());
        assertEquals(2, result.matches().getFirst().atomMappings().size());
    }

    @Test
    void substructureSearchTruncatesResultStructuresDeterministically() {
        TestContext ctx = context();
        ctx.repositories.registerStructure(new RegisterStructureRequest("CCO", "session", "ethanol", "Ethanol", Map.of()));
        ctx.repositories.registerStructure(new RegisterStructureRequest("CCN", "session", "ethylamine", "Ethylamine", Map.of()));

        SubstructureSearchResult result = ctx.search.searchSubstructure(
                new SubstructureSearchRequest("CC", "smiles", null, "all", 1, 1, false)
        );

        assertTrue(result.summary().truncated());
        assertEquals(2, result.summary().matchingStructures());
        assertEquals(1, result.summary().returnedStructures());
        assertEquals("ethanol", result.matches().getFirst().structureId());
        assertEquals(List.of(), result.matches().getFirst().atomMappings());
    }

    @Test
    void invalidScopesAndUnknownRepositoriesFailExplicitly() {
        TestContext ctx = context();

        ChemOperationException scope = assertThrows(
                ChemOperationException.class,
                () -> ctx.search.searchSubstructure(new SubstructureSearchRequest("CC", "smiles", null, "whole_record", 100, 1, true))
        );
        ChemOperationException repository = assertThrows(
                ChemOperationException.class,
                () -> ctx.search.searchExactStructure(new ExactStructureSearchRequest("CC", List.of("missing"), "whole_record"))
        );

        assertEquals("invalid_component_scope", scope.code());
        assertEquals("repository_not_found", repository.code());
    }

    private static TestContext context() {
        StructureRepositoryService repositories = new InMemoryStructureRepositoryService();
        return new TestContext(repositories, new OclStructureSearchService(repositories));
    }

    private record TestContext(StructureRepositoryService repositories, StructureSearchService search) {}
}
