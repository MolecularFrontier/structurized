package tech.molecules.structurized.prismlite.app;

import org.junit.jupiter.api.Test;
import tech.molecules.chemflow.canvas.ChemFlowCanvas;
import tech.molecules.chemflow.model.CanvasElement;
import tech.molecules.chemflow.model.ChemFlowDocument;
import tech.molecules.chemflow.model.ConnectorElement;
import tech.molecules.chemflow.model.ProjectNodeElement;
import tech.molecules.chemflow.model.Size2D;
import tech.molecules.structurized.prism.engine.MaterializedColumnData;
import tech.molecules.structurized.prism.engine.PrismColumn;
import tech.molecules.structurized.prism.engine.PrismColumnSchema;
import tech.molecules.structurized.prism.engine.PrismColumnType;
import tech.molecules.structurized.prism.engine.PrismRowGraph;
import tech.molecules.structurized.prism.engine.PrismRowGraphEdge;
import tech.molecules.structurized.prism.engine.PrismRowSet;
import tech.molecules.structurized.prism.engine.PrismSession;
import tech.molecules.structurized.prism.engine.PrismViewRecord;
import tech.molecules.structurized.prismlite.swing.PrismLiteTableModel;
import tech.molecules.structurized.prismlite.swing.workspace.PrismLiteWorkspaceController;
import tech.molecules.structurized.prismlite.swing.workspace.PrismLiteWorkspaceModel;

import javax.swing.JComponent;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChemFlowProjectRiverViewRendererTest {
    @Test
    void rendersSiblingsInSeparateVerticalBandsWithinOneBatch() throws Exception {
        Fixture fixture = fixture(List.of(
                new EdgeSpec("edge-1", 0, 1, 0.9),
                new EdgeSpec("edge-2", 0, 2, 0.9)
        ));
        ChemFlowDocument document = document(render(fixture.session(), spec(fixture)));

        assertEquals(3, elementCount(document, ProjectNodeElement.class));
        assertEquals(2, elementCount(document, ConnectorElement.class));

        ProjectNodeElement root = node(document, fixture.rowA());
        ProjectNodeElement siblingA = node(document, fixture.rowB());
        ProjectNodeElement siblingB = node(document, fixture.rowC());

        assertEquals(root.transform().x(), siblingA.transform().x(), 0.001);
        assertEquals(siblingA.transform().x(), siblingB.transform().x(), 0.001);
        assertTrue(siblingA.transform().y() < siblingB.transform().y());
        assertTrue(root.transform().y() > siblingA.transform().y());
        assertTrue(root.transform().y() < siblingB.transform().y());
    }

    @Test
    void keepsSingleChildChainsOnOneVerticalBand() throws Exception {
        Fixture fixture = fixture(List.of(
                new EdgeSpec("edge-1", 0, 1, 0.9),
                new EdgeSpec("edge-2", 1, 2, 0.9)
        ));
        ChemFlowDocument document = document(render(fixture.session(), spec(fixture)));

        ProjectNodeElement root = node(document, fixture.rowA());
        ProjectNodeElement child = node(document, fixture.rowB());
        ProjectNodeElement grandchild = node(document, fixture.rowC());

        double minY = Math.min(root.transform().y(), Math.min(child.transform().y(), grandchild.transform().y()));
        double maxY = Math.max(root.transform().y(), Math.max(child.transform().y(), grandchild.transform().y()));
        assertTrue(maxY - minY < 32.0);
        assertEquals(3, Set.of(
                positionKey(root.transform().x(), root.transform().y()),
                positionKey(child.transform().x(), child.transform().y()),
                positionKey(grandchild.transform().x(), grandchild.transform().y())
        ).size());
    }

    @Test
    void clampsProjectRiverNodeScale() throws Exception {
        Fixture fixture = fixture(List.of());

        assertEquals(1.0, spec(fixture).nodeScale(), 0.001);
        assertEquals(0.45, scaledSpec(fixture, 0.1).nodeScale(), 0.001);
        assertEquals(4.0, scaledSpec(fixture, 6.0).nodeScale(), 0.001);
    }

    @Test
    void scalesProjectRiverNodesAndSpacing() throws Exception {
        Fixture fixture = fixture(List.of(
                new EdgeSpec("edge-1", 0, 1, 0.9),
                new EdgeSpec("edge-2", 0, 2, 0.9)
        ));
        ChemFlowDocument document = document(render(fixture.session(), scaledSpec(fixture, 2.0)));

        ProjectNodeElement root = node(document, fixture.rowA());
        ProjectNodeElement siblingA = node(document, fixture.rowB());
        ProjectNodeElement siblingB = node(document, fixture.rowC());

        assertEquals(152.0, root.size().width(), 0.001);
        assertEquals(76.0, root.size().height(), 0.001);
        assertEquals(96.0, siblingB.transform().y() - siblingA.transform().y(), 0.001);
        assertTrue(root.transform().y() > siblingA.transform().y());
        assertTrue(root.transform().y() < siblingB.transform().y());
    }

    @Test
    void untanglesMostlyOverlappingRiverNodesDeterministically() {
        Map<String, ChemFlowProjectRiverViewRenderer.RiverPosition> positions = new LinkedHashMap<>();
        positions.put("row-a", new ChemFlowProjectRiverViewRenderer.RiverPosition(0.0, 0.0, 0));
        positions.put("row-b", new ChemFlowProjectRiverViewRenderer.RiverPosition(2.0, 2.0, 0));
        positions.put("row-c", new ChemFlowProjectRiverViewRenderer.RiverPosition(4.0, 4.0, 0));

        Map<String, ChemFlowProjectRiverViewRenderer.RiverPosition> first =
                ChemFlowProjectRiverViewRenderer.untangleOverlappingPositions(positions, new Size2D(76.0, 38.0), 1.0);
        Map<String, ChemFlowProjectRiverViewRenderer.RiverPosition> second =
                ChemFlowProjectRiverViewRenderer.untangleOverlappingPositions(positions, new Size2D(76.0, 38.0), 1.0);

        assertEquals(3, positionKeys(first).size());
        assertEquals(first, second);
    }

    @Test
    void defaultsProjectRiverColorModeToRootLineage() throws Exception {
        Fixture fixture = fixture(List.of());
        ChemFlowProjectRiverViewSpec base = spec(fixture);
        ChemFlowProjectRiverViewSpec withColumnColor = colorSpec(fixture, ProjectRiverNodeColorMode.CATEGORICAL_COLUMN, "__river_series");

        assertEquals(ProjectRiverNodeColorMode.ROOT_LINEAGE, base.nodeColorMode());
        assertEquals(null, base.colorColumnId());
        assertTrue(withColumnColor.referencedColumnIds().contains("__river_series"));
    }

    @Test
    void colorsProjectRiverNodesByGraphComponent() throws Exception {
        Fixture fixture = fixture(List.of(
                new EdgeSpec("edge-1", 0, 1, 0.9),
                new EdgeSpec("edge-2", 2, 2, 1.0)
        ));
        ChemFlowDocument document = document(render(fixture.session(), colorSpec(fixture, ProjectRiverNodeColorMode.GRAPH_COMPONENT, null)));

        ProjectNodeElement first = node(document, fixture.rowA());
        ProjectNodeElement second = node(document, fixture.rowB());
        ProjectNodeElement isolated = node(document, fixture.rowC());

        assertEquals(first.fillColor(), second.fillColor());
        assertTrue(!first.fillColor().equals(isolated.fillColor()));
    }

    @Test
    void colorsProjectRiverNodesByNumericColumn() throws Exception {
        Fixture fixture = fixture(List.of(
                new EdgeSpec("edge-1", 0, 1, 0.9),
                new EdgeSpec("edge-2", 1, 2, 0.9)
        ));
        ChemFlowDocument document = document(render(fixture.session(), colorSpec(fixture, ProjectRiverNodeColorMode.NUMERIC_COLUMN, "__river_score")));

        ProjectNodeElement low = node(document, fixture.rowA());
        ProjectNodeElement high = node(document, fixture.rowB());
        ProjectNodeElement missing = node(document, fixture.rowC());

        assertTrue(!low.fillColor().equals(high.fillColor()));
        assertEquals("#e5e7eb", missing.fillColor());
    }

    @Test
    void colorsProjectRiverNodesByCategoricalColumn() throws Exception {
        Fixture fixture = fixture(List.of(
                new EdgeSpec("edge-1", 0, 1, 0.9),
                new EdgeSpec("edge-2", 1, 2, 0.9)
        ));
        ChemFlowDocument document = document(render(fixture.session(), colorSpec(fixture, ProjectRiverNodeColorMode.CATEGORICAL_COLUMN, "__river_series")));

        ProjectNodeElement hotA = node(document, fixture.rowA());
        ProjectNodeElement hotB = node(document, fixture.rowB());
        ProjectNodeElement cold = node(document, fixture.rowC());

        assertEquals(hotA.fillColor(), hotB.fillColor());
        assertTrue(!hotA.fillColor().equals(cold.fillColor()));
    }

    private static ChemFlowProjectRiverViewSpec spec(Fixture fixture) {
        return new ChemFlowProjectRiverViewSpec(
                "river",
                "Project River",
                fixture.graph().id(),
                fixture.rowSet().id(),
                fixture.structure().id(),
                null,
                List.of(),
                0.0,
                150.0,
                48.0,
                25
        );
    }

    private static ChemFlowProjectRiverViewSpec scaledSpec(Fixture fixture, double nodeScale) {
        return new ChemFlowProjectRiverViewSpec(
                "river",
                "Project River",
                fixture.graph().id(),
                fixture.rowSet().id(),
                fixture.structure().id(),
                null,
                List.of(),
                0.0,
                150.0,
                48.0,
                25,
                nodeScale
        );
    }

    private static ChemFlowProjectRiverViewSpec colorSpec(Fixture fixture, ProjectRiverNodeColorMode colorMode, String colorColumnId) {
        return new ChemFlowProjectRiverViewSpec(
                "river",
                "Project River",
                fixture.graph().id(),
                fixture.rowSet().id(),
                fixture.structure().id(),
                null,
                List.of(),
                0.0,
                150.0,
                48.0,
                25,
                1.0,
                colorMode,
                colorColumnId
        );
    }

    private static JComponent render(PrismSession session, ChemFlowProjectRiverViewSpec spec) {
        PrismViewRecord view = PrismViewRecord.of(spec);
        PrismLiteWorkspaceModel model = new PrismLiteWorkspaceModel(session);
        PrismLiteWorkspaceController controller = new PrismLiteWorkspaceController(model, new PrismLiteTableModel(session));
        return new ChemFlowProjectRiverViewRenderer().createComponent(view, model, controller, () -> { });
    }

    private static Fixture fixture(List<EdgeSpec> edgeSpecs) throws Exception {
        PrismSession session = PrismSession.open(examplePath());
        addColorTestColumns(session);
        PrismColumn structure = session.table().columns().stream()
                .filter(column -> column.type() == PrismColumnType.MOLECULE)
                .findFirst()
                .orElseThrow();
        String rowA = session.rowIdForPhysicalRow(0);
        String rowB = session.rowIdForPhysicalRow(1);
        String rowC = session.rowIdForPhysicalRow(2);
        List<String> rows = List.of(rowA, rowB, rowC);
        PrismRowSet rowSet = new PrismRowSet(
                "test_rows",
                "Test rows",
                "Test project river rows.",
                new LinkedHashSet<>(rows),
                Map.of()
        );
        PrismRowGraph graph = new PrismRowGraph(
                "test-graph",
                "Test Graph",
                "",
                "chemistry.similarity",
                "test",
                1,
                false,
                rowSet.id(),
                edgeSpecs.stream()
                        .map(edge -> new PrismRowGraphEdge(
                                edge.edgeId(),
                                rows.get(edge.sourceIndex()),
                                rows.get(edge.targetIndex()),
                                edge.edgeId(),
                                Map.of("similarity", edge.similarity())))
                        .toList(),
                Map.of(),
                Map.of()
        );
        session.addRowSet(rowSet);
        session.addGraph(graph);
        return new Fixture(session, structure, rowSet, graph, rowA, rowB, rowC);
    }

    private static void addColorTestColumns(PrismSession session) {
        List<Double> scores = new java.util.ArrayList<>();
        List<String> series = new java.util.ArrayList<>();
        for (int row = 0; row < session.totalRowCount(); row++) {
            if (row == 0) {
                scores.add(1.0);
            } else if (row == 1) {
                scores.add(9.0);
            } else {
                scores.add(null);
            }
            series.add(row == 0 || row == 1 ? "hot" : row == 2 ? "cold" : null);
        }
        session.addMaterializedColumn(new MaterializedColumnData(
                new PrismColumnSchema("__river_score", PrismColumnType.NUMERIC, "Score", null, null, null, null, null, null, Map.of()),
                scores,
                Map.of("source", "test")
        ), true);
        session.addMaterializedColumn(new MaterializedColumnData(
                new PrismColumnSchema("__river_series", PrismColumnType.CATEGORICAL, "Series", null, null, null, null, null, null, Map.of()),
                series,
                Map.of("source", "test")
        ), true);
    }

    private static boolean containsChemFlowCanvas(Component component) {
        return findCanvas(component) != null;
    }

    private static ChemFlowCanvas findCanvas(Component component) {
        if (component instanceof ChemFlowCanvas canvas) return canvas;
        if (!(component instanceof Container container)) return null;
        for (Component child : container.getComponents()) {
            ChemFlowCanvas canvas = findCanvas(child);
            if (canvas != null) return canvas;
        }
        return null;
    }

    private static ChemFlowDocument document(Component component) throws Exception {
        assertTrue(containsChemFlowCanvas(component));
        ChemFlowCanvas canvas = findCanvas(component);
        assertNotNull(canvas);
        Field field = ChemFlowCanvas.class.getDeclaredField("document");
        field.setAccessible(true);
        return (ChemFlowDocument) field.get(canvas);
    }

    private static ProjectNodeElement node(ChemFlowDocument document, String rowId) {
        return document.elementsInZOrder().stream()
                .filter(ProjectNodeElement.class::isInstance)
                .map(ProjectNodeElement.class::cast)
                .filter(node -> node.id().value().equals("river-row:" + safeId(rowId)))
                .findFirst()
                .orElseThrow();
    }

    private static String safeId(String value) {
        String safe = String.valueOf(value).trim().replaceAll("[^A-Za-z0-9_.:-]+", "_");
        return safe.isBlank() ? "item" : safe;
    }

    private static long elementCount(ChemFlowDocument document, Class<? extends CanvasElement> type) {
        return document.elementsInZOrder().stream().filter(type::isInstance).count();
    }

    private static Set<String> positionKeys(Map<String, ChemFlowProjectRiverViewRenderer.RiverPosition> positions) {
        return positions.values().stream()
                .map(position -> positionKey(position.x(), position.y()))
                .collect(Collectors.toSet());
    }

    private static String positionKey(double x, double y) {
        return Math.round(x * 1000.0) + ":" + Math.round(y * 1000.0);
    }

    private static Path examplePath() throws Exception {
        URL resource = ChemFlowProjectRiverViewRendererTest.class.getClassLoader()
                .getResource("prism-fixtures/example.prismpack/prism-pack.json");
        if (resource == null) throw new IllegalStateException("Missing PrismPack test fixture");
        return Path.of(resource.toURI()).getParent();
    }

    private record EdgeSpec(String edgeId, int sourceIndex, int targetIndex, double similarity) {}

    private record Fixture(PrismSession session,
                           PrismColumn structure,
                           PrismRowSet rowSet,
                           PrismRowGraph graph,
                           String rowA,
                           String rowB,
                           String rowC) {
    }
}
