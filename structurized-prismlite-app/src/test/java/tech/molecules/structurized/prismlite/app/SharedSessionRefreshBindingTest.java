package tech.molecules.structurized.prismlite.app;

import org.junit.jupiter.api.Test;
import tech.molecules.structurized.ai.mcp.McpJsonRpcHandler;
import tech.molecules.structurized.ai.prism.InMemoryPrismBridgeService;
import tech.molecules.structurized.ai.prism.InMemoryPrismSessionRegistry;
import tech.molecules.structurized.ai.prism.ManagedPrismSession;
import tech.molecules.structurized.ai.prism.ManagedPrismSessionChangeOrigin;
import tech.molecules.structurized.ai.prism.OpenPrismPackRequest;
import tech.molecules.structurized.ai.repository.InMemoryStructureRepositoryService;
import tech.molecules.structurized.prism.engine.PrismSession;

import javax.swing.SwingUtilities;
import java.net.URL;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedSessionRefreshBindingTest {
    @Test
    void refreshesForExternalChangesButNotLocalUiChanges() throws Exception {
        InMemoryPrismSessionRegistry registry = new InMemoryPrismSessionRegistry();
        PrismSession workspace = PrismSession.open(examplePath());
        ManagedPrismSession managed = registry.register("demo", "Demo", examplePath(), null, workspace);
        AtomicInteger refreshes = new AtomicInteger();
        SharedSessionRefreshBinding binding = new SharedSessionRefreshBinding(
                managed, refreshes::incrementAndGet, Runnable::run);

        workspace.clearFilters();
        managed.runAs(ManagedPrismSessionChangeOrigin.MCP, () -> {
            workspace.clearFilters();
        });
        managed.runAs(ManagedPrismSessionChangeOrigin.MCP, () ->
                managed.moleculeWorkspace().createList("ideas", "Ideas"));

        assertEquals(1, refreshes.get());
        binding.close();
        managed.runAs(ManagedPrismSessionChangeOrigin.MCP, workspace::clearFilters);
        assertEquals(1, refreshes.get());
    }

    @Test
    void desktopExecutorCommitsOnSwingEventThread() {
        AtomicBoolean ranOnEdt = new AtomicBoolean();
        String result = new SwingManagedPrismSessionExecutor().execute(() -> {
            ranOnEdt.set(SwingUtilities.isEventDispatchThread());
            return "done";
        });

        assertEquals("done", result);
        assertTrue(ranOnEdt.get());
    }

    @Test
    void injectedMcpHandlerMutatesTheRegisteredWorkspace() throws Exception {
        InMemoryStructureRepositoryService repositories = new InMemoryStructureRepositoryService();
        InMemoryPrismSessionRegistry registry = new InMemoryPrismSessionRegistry();
        InMemoryPrismBridgeService bridge = new InMemoryPrismBridgeService(repositories, registry);
        bridge.openPack(new OpenPrismPackRequest(examplePath(), "workspace", "Workspace"));
        ManagedPrismSession managed = registry.require("workspace");
        McpJsonRpcHandler handler = McpJsonRpcHandler.create(repositories, bridge);

        String response = handler.handleJson("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
                  "name":"create_prism_column_row_set",
                  "arguments":{"session_id":"workspace","source_row_set_id":"all",
                    "row_set_id":"potent","column_id":"pIC50","filter_type":"numeric_range","minimum":6.5}
                }}
                """);

        assertNotNull(response);
        assertTrue(response.contains("\"rowSetId\":\"potent\""));
        assertEquals(2, managed.workspace().rowSet("potent").rowIds().size());
        assertEquals(2L, managed.revision());
    }

    private static Path examplePath() throws Exception {
        URL resource = SharedSessionRefreshBindingTest.class.getClassLoader()
                .getResource("prism-fixtures/example.prismpack/prism-pack.json");
        if (resource == null) {
            throw new IllegalStateException("Missing PrismPack test fixture");
        }
        return Path.of(resource.toURI()).getParent();
    }
}
