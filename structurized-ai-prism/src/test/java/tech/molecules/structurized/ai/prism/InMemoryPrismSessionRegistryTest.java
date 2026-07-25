package tech.molecules.structurized.ai.prism;

import org.junit.jupiter.api.Test;
import tech.molecules.structurized.ai.model.ChemOperationException;
import tech.molecules.structurized.prism.engine.PrismRowSet;
import tech.molecules.structurized.prism.engine.PrismSession;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemoryPrismSessionRegistryTest {
    @Test
    void ownsWorkspaceIdentityAndPublishesRevisionedOrigins() throws Exception {
        InMemoryPrismSessionRegistry registry = new InMemoryPrismSessionRegistry();
        PrismSession workspace = exampleSession();
        ArrayList<ManagedPrismSessionChange> changes = new ArrayList<>();
        registry.subscribe(changes::add);
        ManagedPrismSession managed = registry.register(
                "demo", "Demo", examplePath(), null, workspace);

        assertSame(managed, registry.register("ignored", "Ignored", examplePath(), null, workspace));

        workspace.clearFilters();
        managed.runAs(ManagedPrismSessionChangeOrigin.MCP, () -> {
            workspace.addRowSet(new PrismRowSet(
                    "agent_hits", "Agent hits", "", Set.of("CMPD-001"), Map.of()));
        });

        assertEquals(3L, managed.revision());
        assertEquals(List.of(
                ManagedPrismSessionChangeOrigin.LOCAL_UI,
                ManagedPrismSessionChangeOrigin.MCP
        ), changes.stream().map(ManagedPrismSessionChange::origin).toList());
        assertEquals(ManagedPrismSessionChangeType.STRUCTURE, changes.getLast().type());

        assertThrows(ChemOperationException.class, () -> registry.register(
                "demo", "Duplicate", examplePath(), null, exampleSession()));
    }

    private static PrismSession exampleSession() throws Exception {
        return PrismSession.open(examplePath());
    }

    private static Path examplePath() {
        return Path.of("..", "..", "prism", "examples", "example.prismpack").toAbsolutePath().normalize();
    }
}
