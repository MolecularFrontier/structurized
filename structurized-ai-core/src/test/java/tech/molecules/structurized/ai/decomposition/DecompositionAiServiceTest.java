package tech.molecules.structurized.ai.decomposition;

import org.junit.jupiter.api.Test;
import tech.molecules.structurized.ai.model.ChemOperationException;
import tech.molecules.structurized.ai.model.RegisterStructureRequest;
import tech.molecules.structurized.ai.repository.InMemoryStructureRepositoryService;
import tech.molecules.structurized.decomposition.DecompositionConfig;
import tech.molecules.structurized.decomposition.DecompositionRule;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecompositionAiServiceTest {
    @Test
    void fragmentHistogramRanksDistinctFragmentsBySupport() {
        TestContext ctx = context();
        ctx.repositories.registerStructure(new RegisterStructureRequest("CCCO", "session", "butanol_a", "Butanol A", Map.of()));
        ctx.repositories.registerStructure(new RegisterStructureRequest("CCCO", "session", "butanol_b", "Butanol B", Map.of()));
        ctx.repositories.registerStructure(new RegisterStructureRequest("NCCO", "session", "aminoethanol", "Aminoethanol", Map.of()));
        ctx.service.createConfig("split", "Split", DecompositionConfig.of(List.of(
                DecompositionRule.of("split_root", null, "CCO", Map.of(0, "alkyl", 1, "linker", 2, "head"))
        )));
        ctx.service.evaluate("eval", "split", "session", null);

        DecompositionAiService.DecompositionFragmentHistogramView histogram = ctx.service.getFragmentHistogram(
                "eval", "root.alkyl", null, 0, 10, 2);

        assertEquals("root.alkyl", histogram.path());
        assertEquals("alkyl", histogram.label());
        assertEquals(2, histogram.totalFragments());
        assertEquals(2, histogram.rows().getFirst().support());
        assertEquals(List.of("butanol_a", "butanol_b"), histogram.rows().getFirst().exampleStructureIds());
        assertTrue(histogram.rows().getFirst().fragmentId().startsWith("frag_"));
    }

    @Test
    void ambiguousHistogramLabelRequiresPath() {
        TestContext ctx = context();
        ctx.repositories.registerStructure(new RegisterStructureRequest("CCCO", "session", "butanol", "Butanol", Map.of()));
        ctx.service.createConfig("split", "Split", DecompositionConfig.of(List.of(
                DecompositionRule.of("split_root", null, "CCO", Map.of(0, "alkyl", 1, "linker", 2, "head")),
                DecompositionRule.of("split_alkyl", "alkyl", "C", Map.of(0, "head"))
        )));
        ctx.service.evaluate("eval", "split", "session", null);

        ChemOperationException exception = assertThrows(
                ChemOperationException.class,
                () -> ctx.service.getFragmentHistogram("eval", null, "head", 0, 10, 1));

        assertEquals("ambiguous_fragment_label", exception.code());
        assertTrue(exception.getMessage().contains("root.alkyl.head"));
        assertTrue(exception.getMessage().contains("root.head"));
    }

    private static TestContext context() {
        InMemoryStructureRepositoryService repositories = new InMemoryStructureRepositoryService();
        return new TestContext(repositories, new DecompositionAiService(repositories));
    }

    private record TestContext(InMemoryStructureRepositoryService repositories, DecompositionAiService service) {}
}
