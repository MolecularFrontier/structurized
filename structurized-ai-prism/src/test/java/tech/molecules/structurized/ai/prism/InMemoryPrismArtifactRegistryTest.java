package tech.molecules.structurized.ai.prism;

import org.junit.jupiter.api.Test;
import tech.molecules.structurized.ai.model.ChemOperationException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryPrismArtifactRegistryTest {
    @Test
    void scopesArtifactsBySessionAndPreservesTypedAccess() {
        InMemoryPrismArtifactRegistry registry = new InMemoryPrismArtifactRegistry();
        TestAnalysis first = analysis("session-a", "cluster-1");
        TestAnalysis second = analysis("session-b", "cluster-1");

        registry.add("session-a", first);
        registry.add("session-b", second);

        assertTrue(registry.contains("session-a", "cluster-1"));
        assertFalse(registry.contains("session-a", "missing"));
        assertSame(first, registry.require("session-a", "cluster-1", TestAnalysis.class));
        assertSame(second, registry.require("session-b", "cluster-1", TestAnalysis.class));
        assertEquals(List.of(first.summary()), registry.summaries("session-a"));
        assertThrows(ChemOperationException.class, () -> registry.add("session-a", first));
        assertThrows(ChemOperationException.class,
                () -> registry.require("session-a", "missing", TestAnalysis.class));
        assertThrows(IllegalArgumentException.class, () -> registry.add("session-b", first));
    }

    private static TestAnalysis analysis(String sessionId, String analysisId) {
        return new TestAnalysis(new PrismAnalysisSummary(
                sessionId,
                analysisId,
                "test",
                analysisId,
                "all",
                1,
                2,
                "2026-07-25T00:00:00Z",
                List.of(),
                Map.of()
        ));
    }

    private record TestAnalysis(PrismAnalysisSummary summary) implements PrismAnalysis {
    }
}
