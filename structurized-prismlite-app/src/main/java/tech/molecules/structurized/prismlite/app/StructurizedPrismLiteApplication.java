package tech.molecules.structurized.prismlite.app;

import com.formdev.flatlaf.FlatLightLaf;
import tech.molecules.structurized.ai.mcp.McpJsonRpcHandler;
import tech.molecules.structurized.ai.prism.InMemoryPrismBridgeService;
import tech.molecules.structurized.ai.prism.InMemoryPrismSessionRegistry;
import tech.molecules.structurized.ai.prism.ManagedPrismSession;
import tech.molecules.structurized.ai.prism.OpenPrismDatasetRequest;
import tech.molecules.structurized.ai.prism.OpenPrismPackRequest;
import tech.molecules.structurized.ai.prism.PrismSessionRegistry;
import tech.molecules.structurized.ai.repository.InMemoryStructureRepositoryService;
import tech.molecules.structurized.ai.repository.StructureRepositoryService;
import tech.molecules.structurized.prismlite.swing.PrismLiteFrame;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class StructurizedPrismLiteApplication {
    private StructurizedPrismLiteApplication() {
    }

    public static void main(String[] args) throws Exception {
        LaunchOptions options = LaunchOptions.parse(args);
        FlatLightLaf.setup();

        StructureRepositoryService repositories = new InMemoryStructureRepositoryService();
        PrismSessionRegistry registry = new InMemoryPrismSessionRegistry(new SwingManagedPrismSessionExecutor());
        InMemoryPrismBridgeService bridge = new InMemoryPrismBridgeService(repositories, registry);
        openInitialSession(bridge, options);
        ManagedPrismSession managed = registry.require(options.sessionId());

        PrismLiteFrame frame = PrismLiteFrame.open(
                managed.workspace(),
                options.sourcePath(),
                List.of(new SharedPrismLiteExtension(registry, bridge))
        );
        McpJsonRpcHandler handler = McpJsonRpcHandler.create(repositories, bridge);
        try {
            handler.runStdio(System.in, System.out);
        } finally {
            if (frame.isDisplayable()) SwingUtilities.invokeLater(frame::dispose);
        }
    }

    private static void openInitialSession(InMemoryPrismBridgeService bridge, LaunchOptions options) {
        Path source = options.sourcePath();
        if (Files.isRegularFile(source.resolve("subjects.prism.tsv"))) {
            bridge.openDataset(new OpenPrismDatasetRequest(source, options.sessionId(), options.label()));
        } else {
            bridge.openPack(new OpenPrismPackRequest(source, options.sessionId(), options.label()));
        }
    }

    record LaunchOptions(Path sourcePath, String sessionId, String label) {
        static LaunchOptions parse(String[] args) {
            Path source = null;
            String sessionId = "workspace";
            for (String argument : args) {
                if (argument.startsWith("--session-id=")) {
                    sessionId = argument.substring("--session-id=".length()).trim();
                } else if (!argument.isBlank()) {
                    if (source != null) throw usage("Only one dataset path may be supplied.");
                    source = Path.of(argument);
                }
            }
            if (sessionId.isBlank()) throw usage("Session ID must not be blank.");
            Path resolved = source == null ? defaultMoonshotPath() : source.toAbsolutePath().normalize();
            if (!Files.exists(resolved)) throw usage("Dataset does not exist: " + resolved);
            Path fileName = resolved.getFileName();
            String label = fileName == null ? sessionId : fileName.toString();
            return new LaunchOptions(resolved, sessionId, label);
        }

        private static Path defaultMoonshotPath() {
            Path cwd = Path.of("").toAbsolutePath().normalize();
            List<Path> candidates = List.of(
                    cwd.resolve("examples/moonshot-medchem.prismpack"),
                    cwd.resolve("../prism/examples/moonshot-medchem.prismpack").normalize(),
                    cwd.resolve("../../prism/examples/moonshot-medchem.prismpack").normalize()
            );
            return candidates.stream().filter(Files::exists).findFirst()
                    .orElseThrow(() -> usage("No dataset supplied and the Moonshot example was not found."));
        }

        private static IllegalArgumentException usage(String message) {
            return new IllegalArgumentException(message
                    + " Usage: structurized-prismlite-app [--session-id=ID] [PRISMPACK_OR_PRISM_TSV_DIRECTORY]");
        }
    }
}
