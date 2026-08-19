package tech.molecules.structurized.prismlite.app;

import com.formdev.flatlaf.FlatLightLaf;
import tech.molecules.structurized.ai.mcp.McpJsonRpcHandler;
import tech.molecules.structurized.ai.mcp.JsonlAgentExplorationRecorder;
import tech.molecules.structurized.ai.prism.InMemoryPrismBridgeService;
import tech.molecules.structurized.ai.prism.InMemoryPrismSessionRegistry;
import tech.molecules.structurized.ai.prism.ManagedPrismSession;
import tech.molecules.structurized.ai.prism.OpenPrismDatasetRequest;
import tech.molecules.structurized.ai.prism.OpenPrismPackRequest;
import tech.molecules.structurized.ai.prism.PrismSessionRegistry;
import tech.molecules.structurized.ai.repository.InMemoryStructureRepositoryService;
import tech.molecules.structurized.ai.repository.StructureRepositoryService;
import tech.molecules.structurized.ai.trace.AgentExplorationTrace;
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

        AgentExplorationTrace trace = new AgentExplorationTrace();
        PrismLiteFrame frame = PrismLiteFrame.open(
                managed.workspace(),
                options.sourcePath(),
                List.of(new SharedPrismLiteExtension(registry, bridge, trace, options.replayTrace()))
        );
        McpJsonRpcHandler handler = McpJsonRpcHandler.create(repositories, bridge, trace);
        try (JsonlAgentExplorationRecorder recorder = options.recordTrace() == null
                ? null : JsonlAgentExplorationRecorder.open(options.recordTrace(), trace)) {
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

    record LaunchOptions(Path sourcePath, String sessionId, String label, Path recordTrace, Path replayTrace) {
        static LaunchOptions parse(String[] args) {
            Path source = null;
            String sessionId = "workspace";
            Path recordTrace = null;
            Path replayTrace = null;
            for (String argument : args) {
                if (argument.startsWith("--session-id=")) {
                    sessionId = argument.substring("--session-id=".length()).trim();
                } else if (argument.startsWith("--record-agent-trace=")) {
                    recordTrace = optionPath(argument, "--record-agent-trace=");
                } else if (argument.startsWith("--replay-agent-trace=")) {
                    replayTrace = optionPath(argument, "--replay-agent-trace=");
                } else if (!argument.isBlank()) {
                    if (source != null) throw usage("Only one dataset path may be supplied.");
                    source = Path.of(argument);
                }
            }
            if (sessionId.isBlank()) throw usage("Session ID must not be blank.");
            if (recordTrace != null && replayTrace != null) throw usage("Record and replay modes are mutually exclusive.");
            if (replayTrace != null && !Files.isRegularFile(replayTrace)) throw usage("Replay trace does not exist: " + replayTrace);
            Path resolved = source == null ? defaultMoonshotPath() : source.toAbsolutePath().normalize();
            if (!Files.exists(resolved)) throw usage("Dataset does not exist: " + resolved);
            Path fileName = resolved.getFileName();
            String label = fileName == null ? sessionId : fileName.toString();
            return new LaunchOptions(resolved, sessionId, label, recordTrace, replayTrace);
        }

        private static Path optionPath(String argument, String prefix) {
            String value = argument.substring(prefix.length()).trim();
            if (value.isBlank()) throw usage("Missing path for " + prefix.substring(0, prefix.length() - 1));
            return Path.of(value).toAbsolutePath().normalize();
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
                    + " Usage: structurized-prismlite-app [--session-id=ID] [--record-agent-trace=FILE | --replay-agent-trace=FILE] [PRISMPACK_OR_PRISM_TSV_DIRECTORY]");
        }
    }
}
