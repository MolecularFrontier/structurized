package tech.molecules.structurized.prismlite.app;

import tech.molecules.structurized.ai.prism.ManagedPrismSession;
import tech.molecules.structurized.ai.prism.PrismSessionRegistry;
import tech.molecules.structurized.prism.engine.PrismRowSet;
import tech.molecules.structurized.prism.engine.PrismSession;
import tech.molecules.structurized.prismlite.swing.PrismLiteSwingContext;
import tech.molecules.structurized.prismlite.swing.PrismLiteSwingExtension;
import tech.molecules.structurized.prismlite.swing.workspace.molecule.PrismMoleculeWorkspacePanel;

import javax.swing.SwingUtilities;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;

public final class SharedPrismLiteExtension implements PrismLiteSwingExtension {
    private final PrismSessionRegistry registry;

    public SharedPrismLiteExtension(PrismSessionRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public void configureSession(PrismSession session, Path sourcePath) {
        if (registry.findByWorkspace(session).isPresent()) return;
        ensureAllRows(session);
        Path source = sourcePath == null ? Path.of("prismlite-session") : sourcePath.toAbsolutePath().normalize();
        String sessionId = nextSessionId(source);
        registry.register(sessionId, label(source), source, null, session);
    }

    @Override
    public void configureSwing(PrismLiteSwingContext context) {
        ManagedPrismSession managed = registry.findByWorkspace(context.session()).orElseThrow();
        context.frame().setTitle(context.frame().getTitle() + " [" + managed.sessionId() + "]");
        PrismMoleculeWorkspacePanel moleculePanel = new PrismMoleculeWorkspacePanel(
                managed.moleculeWorkspace(),
                context.workspace().model(),
                context.workspace()::focusColumnInspector
        );
        context.workspace().addApplicationTab("molecules", "Molecules", moleculePanel);
        SharedSessionRefreshBinding binding = new SharedSessionRefreshBinding(
                managed,
                () -> {
                    if (context.frame().isDisplayable()) context.refresh().run();
                },
                SwingUtilities::invokeLater
        );
        context.frame().addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                binding.close();
                moleculePanel.close();
            }
        });
    }

    private String nextSessionId(Path sourcePath) {
        String base = label(sourcePath).toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (base.isBlank()) base = "workspace";
        String candidate = base;
        int suffix = 2;
        while (registry.find(candidate).isPresent()) candidate = base + "_" + suffix++;
        return candidate;
    }

    private static String label(Path sourcePath) {
        Path fileName = sourcePath.getFileName();
        return fileName == null ? sourcePath.toString() : fileName.toString();
    }

    private static void ensureAllRows(PrismSession session) {
        if (session.rowSets().stream().anyMatch(rowSet -> rowSet.id().equals("all"))) return;
        LinkedHashSet<String> rowIds = new LinkedHashSet<>();
        for (int row = 0; row < session.totalRowCount(); row++) {
            rowIds.add(session.rowIdForPhysicalRow(row));
        }
        session.addRowSet(new PrismRowSet(
                "all", "All rows", "All rows in the managed Prism session.", rowIds,
                Map.of("source", "managed_prism_session")
        ));
    }
}
