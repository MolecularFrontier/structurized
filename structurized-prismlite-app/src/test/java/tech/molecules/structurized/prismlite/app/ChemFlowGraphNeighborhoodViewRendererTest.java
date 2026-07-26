package tech.molecules.structurized.prismlite.app;

import org.junit.jupiter.api.Test;
import tech.molecules.chemflow.canvas.ChemFlowCanvas;
import tech.molecules.chemflow.model.CanvasElement;
import tech.molecules.chemflow.model.ChemFlowDocument;
import tech.molecules.chemflow.model.ConnectorElement;
import tech.molecules.chemflow.model.TextElement;
import tech.molecules.structurized.prism.engine.PrismColumn;
import tech.molecules.structurized.prism.engine.PrismColumnType;
import tech.molecules.structurized.prism.engine.PrismRowGraph;
import tech.molecules.structurized.prism.engine.PrismRowGraphEdge;
import tech.molecules.structurized.prism.engine.PrismSession;
import tech.molecules.structurized.prism.engine.PrismViewRecord;
import tech.molecules.structurized.prism.engine.RowGraphNeighborhoodEdgeMode;
import tech.molecules.structurized.prism.engine.RowGraphNeighborhoodLabelMode;
import tech.molecules.structurized.prism.engine.RowGraphNeighborhoodLayoutMode;
import tech.molecules.structurized.prism.engine.RowGraphNeighborhoodViewSpec;
import tech.molecules.structurized.prismlite.swing.PrismLiteTableModel;
import tech.molecules.structurized.prismlite.swing.workspace.PrismLiteWorkspaceController;
import tech.molecules.structurized.prismlite.swing.workspace.PrismLiteWorkspaceModel;

import javax.swing.JComponent;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChemFlowGraphNeighborhoodViewRendererTest {
    @Test
    void rendersGraphNeighborhoodAsChemFlowCanvas() throws Exception {
        Fixture fixture = fixture();
        RowGraphNeighborhoodViewSpec spec = new RowGraphNeighborhoodViewSpec(
                "view", "Neighborhood", fixture.graph().id(), fixture.rowA(), fixture.structure().id(), List.of(), 12, true);

        JComponent component = render(fixture.session(), spec);

        assertNotNull(component);
        assertTrue(containsChemFlowCanvas(component));
    }

    @Test
    void centerOnlyModeHidesNeighborNeighborEdges() throws Exception {
        Fixture fixture = fixture();
        RowGraphNeighborhoodViewSpec spec = new RowGraphNeighborhoodViewSpec(
                "view", "Neighborhood", fixture.graph().id(), fixture.rowA(), fixture.structure().id(), List.of(), 12, false,
                RowGraphNeighborhoodEdgeMode.CENTER_ONLY,
                RowGraphNeighborhoodLabelMode.SELECTED_ONLY,
                RowGraphNeighborhoodLayoutMode.RADIAL_BY_DEGREE);

        ChemFlowDocument document = document(render(fixture.session(), spec));

        assertEquals(2, elementCount(document, ConnectorElement.class));
        assertEquals(1, elementCount(document, TextElement.class));
    }

    @Test
    void inducedModeIncludesNeighborNeighborEdgesAndAllLabels() throws Exception {
        Fixture fixture = fixture();
        RowGraphNeighborhoodViewSpec spec = new RowGraphNeighborhoodViewSpec(
                "view", "Neighborhood", fixture.graph().id(), fixture.rowA(), fixture.structure().id(), List.of(), 12, false,
                RowGraphNeighborhoodEdgeMode.INDUCED_NEIGHBORHOOD,
                RowGraphNeighborhoodLabelMode.ALL,
                RowGraphNeighborhoodLayoutMode.RADIAL_BY_DEGREE);

        ChemFlowDocument document = document(render(fixture.session(), spec));

        assertEquals(3, elementCount(document, ConnectorElement.class));
        assertEquals(3, elementCount(document, TextElement.class));
    }

    private static JComponent render(PrismSession session, RowGraphNeighborhoodViewSpec spec) {
        PrismViewRecord view = PrismViewRecord.of(spec);
        PrismLiteWorkspaceModel model = new PrismLiteWorkspaceModel(session);
        PrismLiteWorkspaceController controller = new PrismLiteWorkspaceController(model, new PrismLiteTableModel(session));
        return new ChemFlowGraphNeighborhoodViewRenderer().createComponent(view, model, controller, () -> { });
    }

    private static Fixture fixture() throws Exception {
        PrismSession session = PrismSession.open(examplePath());
        PrismColumn structure = session.table().columns().stream()
                .filter(column -> column.type() == PrismColumnType.MOLECULE)
                .findFirst()
                .orElseThrow();
        String rowA = session.rowIdForPhysicalRow(0);
        String rowB = session.rowIdForPhysicalRow(1);
        String rowC = session.rowIdForPhysicalRow(2);
        PrismRowGraph graph = new PrismRowGraph(
                "test-graph",
                "Test Graph",
                "",
                "chemistry.mmp",
                "test",
                1,
                true,
                null,
                List.of(
                        new PrismRowGraphEdge("edge-1", rowA, rowB, "A -> B", Map.of("transformId", "A>>B")),
                        new PrismRowGraphEdge("edge-2", rowA, rowC, "A -> C", Map.of("transformId", "A>>C")),
                        new PrismRowGraphEdge("edge-3", rowB, rowC, "B -> C", Map.of("transformId", "B>>C"))
                ),
                Map.of(),
                Map.of()
        );
        session.addGraph(graph);
        return new Fixture(session, structure, rowA, graph);
    }

    private static boolean containsChemFlowCanvas(Component component) {
        return findCanvas(component) != null;
    }

    private static ChemFlowCanvas findCanvas(Component component) {
        if (component instanceof ChemFlowCanvas canvas) {
            return canvas;
        }
        if (!(component instanceof Container container)) {
            return null;
        }
        for (Component child : container.getComponents()) {
            ChemFlowCanvas canvas = findCanvas(child);
            if (canvas != null) {
                return canvas;
            }
        }
        return null;
    }

    private static ChemFlowDocument document(Component component) throws Exception {
        ChemFlowCanvas canvas = findCanvas(component);
        assertNotNull(canvas);
        Field field = ChemFlowCanvas.class.getDeclaredField("document");
        field.setAccessible(true);
        return (ChemFlowDocument) field.get(canvas);
    }

    private static long elementCount(ChemFlowDocument document, Class<? extends CanvasElement> type) {
        return document.elementsInZOrder().stream().filter(type::isInstance).count();
    }

    private static Path examplePath() {
        return Path.of("..", "..", "prism", "examples", "example.prismpack").toAbsolutePath().normalize();
    }

    private record Fixture(PrismSession session, PrismColumn structure, String rowA, PrismRowGraph graph) {
    }
}
