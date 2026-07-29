package tech.molecules.structurized.prismlite.app;

import org.junit.jupiter.api.Test;
import tech.molecules.structurized.ai.prism.InMemoryPrismBridgeService;
import tech.molecules.structurized.ai.prism.InMemoryPrismSessionRegistry;
import tech.molecules.structurized.ai.prism.PrismBridgeService;
import tech.molecules.structurized.ai.repository.InMemoryStructureRepositoryService;
import tech.molecules.structurized.prism.engine.PrismRowSet;
import tech.molecules.structurized.prism.engine.PrismSession;
import tech.molecules.structurized.prismlite.swing.workspace.PrismLiteWorkspaceModel;

import javax.swing.JButton;
import javax.swing.JComboBox;
import java.awt.Component;
import java.awt.Container;
import java.net.URL;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PrismMmpGraphPanelTest {
    @Test
    void graphPanelOffersSimilarityMiningAndProjectRiverActions() throws Exception {
        PrismSession session = PrismSession.open(examplePath());
        ensureAllRows(session);
        InMemoryPrismSessionRegistry registry = new InMemoryPrismSessionRegistry();
        registry.register("test", "Test", examplePath(), null, session);
        PrismBridgeService bridge = new InMemoryPrismBridgeService(new InMemoryStructureRepositoryService(), registry);

        PrismMmpGraphPanel panel = new PrismMmpGraphPanel(
                bridge,
                "test",
                new PrismLiteWorkspaceModel(session),
                () -> { },
                ignored -> { }
        );

        assertTrue(hasComboItem(panel, "Similarity"));
        assertTrue(hasButton(panel, "Mine Graph"));
        assertTrue(hasButton(panel, "Open Project River"));
    }

    private static void ensureAllRows(PrismSession session) {
        if (session.rowSets().stream().anyMatch(rowSet -> rowSet.id().equals("all"))) return;
        LinkedHashSet<String> rowIds = new LinkedHashSet<>();
        IntStream.range(0, session.totalRowCount()).mapToObj(session::rowIdForPhysicalRow).forEach(rowIds::add);
        session.addRowSet(new PrismRowSet("all", "All rows", "All rows.", rowIds, java.util.Map.of()));
    }

    private static boolean hasComboItem(Component component, String itemText) {
        for (JComboBox<?> combo : components(component, JComboBox.class)) {
            for (int i = 0; i < combo.getItemCount(); i++) {
                if (itemText.equals(String.valueOf(combo.getItemAt(i)))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasButton(Component component, String text) {
        return components(component, JButton.class).stream().anyMatch(button -> text.equals(button.getText()));
    }

    private static <T extends Component> List<T> components(Component component, Class<T> type) {
        java.util.ArrayList<T> matches = new java.util.ArrayList<>();
        collect(component, type, matches);
        return List.copyOf(matches);
    }

    private static <T extends Component> void collect(Component component, Class<T> type, java.util.List<T> matches) {
        if (type.isInstance(component)) matches.add(type.cast(component));
        if (!(component instanceof Container container)) return;
        for (Component child : container.getComponents()) {
            collect(child, type, matches);
        }
    }

    private static Path examplePath() throws Exception {
        URL resource = PrismMmpGraphPanelTest.class.getClassLoader()
                .getResource("prism-fixtures/example.prismpack/prism-pack.json");
        if (resource == null) throw new IllegalStateException("Missing PrismPack test fixture");
        return Path.of(resource.toURI()).getParent();
    }
}
