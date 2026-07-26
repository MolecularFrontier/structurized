package tech.molecules.structurized.prismlite.app;

import com.actelion.research.chem.StereoMolecule;
import tech.molecules.chemflow.canvas.CanvasHit;
import tech.molecules.chemflow.canvas.ChemFlowCanvas;
import tech.molecules.chemflow.canvas.ElementHitTesterRegistry;
import tech.molecules.chemflow.canvas.ElementRendererRegistry;
import tech.molecules.chemflow.model.ChemFlowDocument;
import tech.molecules.chemflow.model.CommandHistory;
import tech.molecules.chemflow.model.ConnectorElement;
import tech.molecules.chemflow.model.ElementId;
import tech.molecules.chemflow.model.ElementTransform;
import tech.molecules.chemflow.model.GraphLayoutNode;
import tech.molecules.chemflow.model.MoleculeElement;
import tech.molecules.chemflow.model.RadialGraphLayout;
import tech.molecules.chemflow.model.Size2D;
import tech.molecules.chemflow.model.TextElement;
import tech.molecules.chemflow.ocl.OclCanvasSupport;
import tech.molecules.chemflow.ocl.OclMoleculeCodec;
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
import tech.molecules.structurized.prismlite.swing.workspace.PrismLiteWorkspaceController;
import tech.molecules.structurized.prismlite.swing.workspace.PrismLiteWorkspaceModel;
import tech.molecules.structurized.prismlite.swing.workspace.chem.MoleculeRenderUtil;
import tech.molecules.structurized.prismlite.swing.workspace.chem.StructureCoordinateResolver;
import tech.molecules.structurized.prismlite.swing.workspace.views.PrismSwingViewRenderer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;

final class ChemFlowGraphNeighborhoodViewRenderer implements PrismSwingViewRenderer {
    private static final Size2D CENTER_MOLECULE_SIZE = new Size2D(280, 210);
    private static final Size2D NEIGHBOR_MOLECULE_SIZE = new Size2D(205, 150);
    private static final Size2D LABEL_SIZE = new Size2D(230, 48);
    private static final int MAX_RENDERED_EDGES = 240;

    @Override
    public String viewType() {
        return RowGraphNeighborhoodViewSpec.VIEW_TYPE;
    }

    @Override
    public JComponent createComponent(
            PrismViewRecord view,
            PrismLiteWorkspaceModel model,
            PrismLiteWorkspaceController controller,
            Runnable refresh
    ) {
        if (!(view.specification() instanceof RowGraphNeighborhoodViewSpec spec)) {
            return message("Unsupported graph-neighborhood specification.");
        }
        PrismSession session = model.session();
        PrismRowGraph graph;
        try {
            graph = session.graph(spec.graphId());
        } catch (RuntimeException exception) {
            return message("Unknown graph: " + spec.graphId());
        }
        if (!graph.rowIds().contains(spec.centerRowId())) {
            return message("The center row is not part of this graph: " + spec.centerRowId());
        }
        PrismColumn structureColumn = session.table().column(spec.structureColumnId());
        if (structureColumn.type() != PrismColumnType.MOLECULE) {
            return message("Structure column is not a molecule column: " + spec.structureColumnId());
        }

        GraphDocument graphDocument = buildDocument(session, graph, spec, structureColumn);
        if (graphDocument.rowByElementId().isEmpty()) {
            return message("No renderable structures in this graph neighborhood.");
        }

        ElementRendererRegistry renderers = ChemFlowCanvas.defaultRenderers();
        ElementHitTesterRegistry hitTesters = ChemFlowCanvas.defaultHitTesters();
        OclCanvasSupport.install(renderers, hitTesters);
        ChemFlowCanvas canvas = new ChemFlowCanvas(graphDocument.document(), new CommandHistory(), renderers, hitTesters);
        canvas.setPreferredSize(new Dimension(1180, 820));
        selectSessionRowsInCanvas(session, canvas, graphDocument);
        canvas.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                CanvasHit hit = canvas.hitTest(event.getPoint(), false).orElse(null);
                if (hit == null) {
                    return;
                }
                String rowId = graphDocument.rowByElementId().get(hit.elementId());
                if (rowId != null) {
                    selectRow(session, model, rowId, event.getModifiersEx(), refresh);
                }
            }
        });
        SwingUtilities.invokeLater(() -> {
            canvas.camera().reset(Math.max(1, canvas.getWidth()), Math.max(1, canvas.getHeight()));
            canvas.repaint();
        });

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(canvas, BorderLayout.CENTER);
        panel.add(summaryBar(spec, graph, graphDocument), BorderLayout.SOUTH);
        return panel;
    }

    @Override
    public JComponent createConfigurationComponent(
            PrismViewRecord view,
            PrismLiteWorkspaceModel model,
            PrismLiteWorkspaceController controller,
            Runnable refresh
    ) {
        if (!(view.specification() instanceof RowGraphNeighborhoodViewSpec spec)) {
            return null;
        }
        JPanel panel = new JPanel(new BorderLayout(6, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));
        JTextField title = new JTextField(spec.title(), 18);
        JTextField maxNeighbors = new JTextField(String.valueOf(spec.maxNeighbors()), 5);
        JComboBox<RowGraphNeighborhoodEdgeMode> edgeMode = new JComboBox<>(RowGraphNeighborhoodEdgeMode.values());
        edgeMode.setSelectedItem(spec.edgeMode());
        JComboBox<RowGraphNeighborhoodLabelMode> labelMode = new JComboBox<>(RowGraphNeighborhoodLabelMode.values());
        labelMode.setSelectedItem(spec.labelMode());
        JComboBox<RowGraphNeighborhoodLayoutMode> layoutMode = new JComboBox<>(RowGraphNeighborhoodLayoutMode.values());
        layoutMode.setSelectedItem(spec.layoutMode());
        JCheckBox edgeLabels = new JCheckBox("Edge labels", spec.showEdgeLabels());
        JButton focusSelected = new JButton("Focus Selected Row");
        focusSelected.addActionListener(event -> focusSelectedRow(view, spec, model, refresh));
        JButton apply = new JButton("Apply");
        apply.addActionListener(event -> {
            RowGraphNeighborhoodViewSpec updated = new RowGraphNeighborhoodViewSpec(
                    spec.viewId(),
                    title.getText(),
                    spec.graphId(),
                    spec.centerRowId(),
                    spec.structureColumnId(),
                    spec.labelColumnIds(),
                    parseInt(maxNeighbors.getText(), spec.maxNeighbors()),
                    edgeLabels.isSelected(),
                    (RowGraphNeighborhoodEdgeMode) edgeMode.getSelectedItem(),
                    (RowGraphNeighborhoodLabelMode) labelMode.getSelectedItem(),
                    (RowGraphNeighborhoodLayoutMode) layoutMode.getSelectedItem()
            );
            updateView(view, model, updated);
            refresh.run();
        });
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.add(new JLabel("Title "));
        toolbar.add(title);
        toolbar.add(new JLabel("  Max "));
        toolbar.add(maxNeighbors);
        toolbar.add(new JLabel("  Edges "));
        toolbar.add(edgeMode);
        toolbar.add(new JLabel("  Labels "));
        toolbar.add(labelMode);
        toolbar.add(edgeLabels);
        toolbar.add(focusSelected);
        toolbar.add(apply);
        panel.add(toolbar, BorderLayout.CENTER);
        return panel;
    }

    private static void focusSelectedRow(
            PrismViewRecord view,
            RowGraphNeighborhoodViewSpec spec,
            PrismLiteWorkspaceModel model,
            Runnable refresh
    ) {
        String rowId = model.focusedRowId();
        if (rowId == null || !model.session().graph(spec.graphId()).rowIds().contains(rowId)) {
            return;
        }
        RowGraphNeighborhoodViewSpec updated = new RowGraphNeighborhoodViewSpec(
                spec.viewId(),
                spec.title(),
                spec.graphId(),
                rowId,
                spec.structureColumnId(),
                spec.labelColumnIds(),
                spec.maxNeighbors(),
                spec.showEdgeLabels(),
                spec.edgeMode(),
                spec.labelMode(),
                spec.layoutMode()
        );
        updateView(view, model, updated);
        refresh.run();
    }

    private static void updateView(PrismViewRecord view, PrismLiteWorkspaceModel model, RowGraphNeighborhoodViewSpec updated) {
        Map<String, Object> provenance = new LinkedHashMap<>(view.provenance());
        provenance.put("updatedAt", Instant.now().toString());
        model.session().updateView(new PrismViewRecord(
                updated.viewId(), updated.viewType(), updated.title(), updated, view.createdAt(), provenance));
    }

    private static GraphDocument buildDocument(
            PrismSession session,
            PrismRowGraph graph,
            RowGraphNeighborhoodViewSpec spec,
            PrismColumn structureColumn
    ) {
        OclMoleculeCodec codec = new OclMoleculeCodec();
        List<String> neighbors = graph.neighborRowIds(spec.centerRowId()).stream()
                .sorted(neighborComparator(session, graph, spec))
                .toList();
        LinkedHashSet<String> rowIds = new LinkedHashSet<>();
        rowIds.add(spec.centerRowId());
        neighbors.stream().limit(spec.maxNeighbors()).forEach(rowIds::add);

        LinkedHashMap<String, MoleculeElement> molecules = new LinkedHashMap<>();
        ArrayList<GraphLayoutNode> layoutNodes = new ArrayList<>();
        for (String rowId : rowIds) {
            OptionalInt physicalRow = session.physicalRowForRowId(rowId);
            if (physicalRow.isEmpty()) {
                continue;
            }
            StereoMolecule molecule = molecule(session, structureColumn, physicalRow.getAsInt());
            if (molecule == null) {
                continue;
            }
            Size2D size = rowId.equals(spec.centerRowId()) ? CENTER_MOLECULE_SIZE : NEIGHBOR_MOLECULE_SIZE;
            ElementId elementId = moleculeElementId(rowId);
            molecules.put(rowId, MoleculeElement.create(
                    elementId, new ElementTransform(0, 0), size, codec.encode(molecule)));
            layoutNodes.add(new GraphLayoutNode(rowId, size));
        }
        ChemFlowDocument document = new ChemFlowDocument();
        if (!molecules.containsKey(spec.centerRowId())) {
            return new GraphDocument(document, Map.of(), 0, 0, neighbors.size(), 0, 0, 0);
        }
        Map<String, ElementTransform> positions = RadialGraphLayout.layout(spec.centerRowId(), layoutNodes, radiusFor(layoutNodes.size()));
        LinkedHashMap<String, ElementId> rowElementIds = new LinkedHashMap<>();
        for (Map.Entry<String, MoleculeElement> entry : molecules.entrySet()) {
            String rowId = entry.getKey();
            MoleculeElement element = entry.getValue().withTransform(positions.get(rowId));
            molecules.put(rowId, element);
            rowElementIds.put(rowId, element.id());
        }

        List<PrismRowGraphEdge> candidateEdges = candidateEdges(graph, spec, rowElementIds.keySet());
        int renderedEdgeCount = 0;
        for (PrismRowGraphEdge edge : candidateEdges) {
            if (renderedEdgeCount >= MAX_RENDERED_EDGES) {
                break;
            }
            ConnectorElement connector = connector(edge, rowElementIds, molecules, spec.showEdgeLabels());
            document.addElement(connector);
            renderedEdgeCount++;
        }
        LinkedHashMap<ElementId, String> rowByElement = new LinkedHashMap<>();
        for (Map.Entry<String, MoleculeElement> entry : molecules.entrySet()) {
            String rowId = entry.getKey();
            MoleculeElement molecule = entry.getValue();
            document.addElement(molecule);
            rowByElement.put(molecule.id(), rowId);
            if (shouldRenderLabel(session, spec, rowId)) {
                document.addElement(labelElement(session, spec, rowId, molecule));
            }
        }
        int renderedNeighborCount = Math.max(0, molecules.size() - 1);
        int hiddenNeighborCount = Math.max(0, neighbors.size() - renderedNeighborCount);
        int hiddenEdgeCount = Math.max(0, candidateEdges.size() - renderedEdgeCount);
        return new GraphDocument(
                document,
                rowByElement,
                molecules.size(),
                renderedEdgeCount,
                neighbors.size(),
                hiddenNeighborCount,
                candidateEdges.size(),
                hiddenEdgeCount
        );
    }

    private static Comparator<String> neighborComparator(PrismSession session, PrismRowGraph graph, RowGraphNeighborhoodViewSpec spec) {
        PrismColumn valueColumn = firstNumericLabelColumn(session, spec);
        OptionalDouble centerValue = valueColumn == null
                ? OptionalDouble.empty()
                : valueForRow(session, valueColumn, spec.centerRowId());
        return (left, right) -> {
            if (valueColumn != null && centerValue.isPresent()) {
                OptionalDouble leftValue = valueForRow(session, valueColumn, left);
                OptionalDouble rightValue = valueForRow(session, valueColumn, right);
                if (leftValue.isPresent() && rightValue.isPresent()) {
                    int byDelta = Double.compare(
                            Math.abs(leftValue.getAsDouble() - centerValue.getAsDouble()),
                            Math.abs(rightValue.getAsDouble() - centerValue.getAsDouble())
                    );
                    if (byDelta != 0) {
                        return byDelta;
                    }
                } else if (leftValue.isPresent() != rightValue.isPresent()) {
                    return leftValue.isPresent() ? -1 : 1;
                }
            }
            int byDegree = Integer.compare(graph.degree(right), graph.degree(left));
            return byDegree != 0 ? byDegree : left.compareTo(right);
        };
    }

    private static PrismColumn firstNumericLabelColumn(PrismSession session, RowGraphNeighborhoodViewSpec spec) {
        if (spec.labelColumnIds().isEmpty()) {
            return null;
        }
        PrismColumn column = session.table().column(spec.labelColumnIds().getFirst());
        return column.type() == PrismColumnType.NUMERIC || column.type() == PrismColumnType.INTEGER ? column : null;
    }

    private static OptionalDouble valueForRow(PrismSession session, PrismColumn column, String rowId) {
        OptionalInt physicalRow = session.physicalRowForRowId(rowId);
        if (physicalRow.isEmpty() || column.isMissing(physicalRow.getAsInt())) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(column.doubleValueAt(physicalRow.getAsInt()));
    }

    private static List<PrismRowGraphEdge> candidateEdges(
            PrismRowGraph graph,
            RowGraphNeighborhoodViewSpec spec,
            java.util.Set<String> renderedRows
    ) {
        ArrayList<PrismRowGraphEdge> edges = new ArrayList<>();
        for (PrismRowGraphEdge edge : graph.edges()) {
            if (!renderedRows.contains(edge.sourceRowId()) || !renderedRows.contains(edge.targetRowId())) {
                continue;
            }
            if (spec.edgeMode() == RowGraphNeighborhoodEdgeMode.CENTER_ONLY
                    && !connects(edge, spec.centerRowId())) {
                continue;
            }
            edges.add(edge);
        }
        return List.copyOf(edges);
    }

    private static boolean connects(PrismRowGraphEdge edge, String rowId) {
        return edge.sourceRowId().equals(rowId) || edge.targetRowId().equals(rowId);
    }

    private static boolean shouldRenderLabel(PrismSession session, RowGraphNeighborhoodViewSpec spec, String rowId) {
        return switch (spec.labelMode()) {
            case NONE -> false;
            case ALL -> true;
            case SELECTED_ONLY -> rowId.equals(spec.centerRowId()) || selected(session, rowId);
        };
    }

    private static boolean selected(PrismSession session, String rowId) {
        OptionalInt physicalRow = session.physicalRowForRowId(rowId);
        return physicalRow.isPresent() && session.viewState().selectionModel().isSelected(physicalRow.getAsInt());
    }

    private static StereoMolecule molecule(PrismSession session, PrismColumn structureColumn, int physicalRow) {
        String coordinates = StructureCoordinateResolver.coordinateValue(session.table(), structureColumn, physicalRow);
        return MoleculeRenderUtil.parse(structureColumn, structureColumn.formattedValueAt(physicalRow), coordinates);
    }

    private static ConnectorElement connector(
            PrismRowGraphEdge edge,
            Map<String, ElementId> rowElementIds,
            Map<String, MoleculeElement> molecules,
            boolean showLabel
    ) {
        MoleculeElement source = molecules.get(edge.sourceRowId());
        MoleculeElement target = molecules.get(edge.targetRowId());
        Rectangle2D bounds = unionBounds(source, target);
        return ConnectorElement.create(
                new ElementId("edge:" + edge.id()),
                rowElementIds.get(edge.sourceRowId()),
                rowElementIds.get(edge.targetRowId()),
                new ElementTransform(bounds.getX(), bounds.getY()),
                new Size2D(bounds.getWidth(), bounds.getHeight()),
                showLabel ? edgeLabel(edge) : ""
        );
    }

    private static Rectangle2D unionBounds(MoleculeElement source, MoleculeElement target) {
        double minX = Math.min(source.transform().x(), target.transform().x());
        double minY = Math.min(source.transform().y(), target.transform().y());
        double maxX = Math.max(source.transform().x() + source.size().width(), target.transform().x() + target.size().width());
        double maxY = Math.max(source.transform().y() + source.size().height(), target.transform().y() + target.size().height());
        double padding = 24.0;
        return new Rectangle2D.Double(minX - padding, minY - padding, maxX - minX + 2 * padding, maxY - minY + 2 * padding);
    }

    private static String edgeLabel(PrismRowGraphEdge edge) {
        Object transform = edge.properties().get("transformId");
        if (transform != null && !String.valueOf(transform).isBlank()) {
            return String.valueOf(transform);
        }
        return edge.label();
    }

    private static TextElement labelElement(PrismSession session, RowGraphNeighborhoodViewSpec spec, String rowId, MoleculeElement molecule) {
        StringBuilder text = new StringBuilder(rowId);
        OptionalInt physicalRow = session.physicalRowForRowId(rowId);
        if (physicalRow.isPresent()) {
            for (String columnId : spec.labelColumnIds()) {
                PrismColumn column = session.table().column(columnId);
                if (!column.isMissing(physicalRow.getAsInt())) {
                    text.append("\n").append(column.schema().displayName()).append(": ")
                            .append(column.formattedValueAt(physicalRow.getAsInt()));
                }
            }
        }
        return new TextElement(
                new ElementId("label:" + rowId),
                new ElementTransform(molecule.transform().x(), molecule.transform().y() + molecule.size().height() + 8),
                LABEL_SIZE,
                text.toString(),
                true,
                true
        );
    }

    private static void selectSessionRowsInCanvas(PrismSession session, ChemFlowCanvas canvas, GraphDocument graphDocument) {
        for (Map.Entry<ElementId, String> entry : graphDocument.rowByElementId().entrySet()) {
            OptionalInt physicalRow = session.physicalRowForRowId(entry.getValue());
            if (physicalRow.isPresent() && session.viewState().selectionModel().isSelected(physicalRow.getAsInt())) {
                canvas.selection().selectOnly(entry.getKey());
                return;
            }
        }
    }

    private static void selectRow(PrismSession session, PrismLiteWorkspaceModel model, String rowId, int modifiersEx, Runnable refresh) {
        OptionalInt physicalRow = session.physicalRowForRowId(rowId);
        if (physicalRow.isEmpty()) {
            return;
        }
        if (!additiveSelection(modifiersEx)) {
            session.viewState().selectionModel().clear();
        }
        session.viewState().selectionModel().setSelected(physicalRow.getAsInt(), true);
        model.setFocusedPhysicalRow(physicalRow.getAsInt());
        refresh.run();
    }

    private static boolean additiveSelection(int modifiersEx) {
        return (modifiersEx & (InputEvent.SHIFT_DOWN_MASK | InputEvent.CTRL_DOWN_MASK | InputEvent.META_DOWN_MASK)) != 0;
    }

    private static double radiusFor(int nodeCount) {
        return nodeCount <= 8 ? 330.0 : nodeCount <= 18 ? 390.0 : 440.0;
    }

    private static ElementId moleculeElementId(String rowId) {
        return new ElementId("row:" + rowId);
    }

    private static JPanel summaryBar(RowGraphNeighborhoodViewSpec spec, PrismRowGraph graph, GraphDocument graphDocument) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        String hiddenNeighbors = graphDocument.hiddenNeighborCount() > 0
                ? "  |  " + graphDocument.hiddenNeighborCount() + " neighbors hidden"
                : "";
        String hiddenEdges = graphDocument.hiddenEdgeCount() > 0
                ? "  |  " + graphDocument.hiddenEdgeCount() + " edges hidden"
                : "";
        panel.add(new JLabel(graph.title() + "  |  center " + spec.centerRowId()
                + "  |  " + graphDocument.renderedRowCount() + " structures"
                + "  |  " + graphDocument.renderedEdgeCount() + "/" + graphDocument.totalEdgeCount() + " edges"
                + "  |  " + spec.edgeMode() + "  |  " + spec.labelMode()
                + hiddenNeighbors + hiddenEdges), BorderLayout.WEST);
        return panel;
    }

    private static JPanel message(String text) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(new JLabel(text), BorderLayout.CENTER);
        return panel;
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(String.valueOf(text).trim());
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private record GraphDocument(
            ChemFlowDocument document,
            Map<ElementId, String> rowByElementId,
            int renderedRowCount,
            int renderedEdgeCount,
            int totalNeighborCount,
            int hiddenNeighborCount,
            int totalEdgeCount,
            int hiddenEdgeCount
    ) {
        private GraphDocument {
            rowByElementId = Map.copyOf(rowByElementId);
        }
    }
}
