package tech.molecules.structurized.prismlite.app;

import tech.molecules.chemflow.canvas.CanvasCamera;
import tech.molecules.chemflow.canvas.CanvasHit;
import tech.molecules.chemflow.canvas.ChemFlowCanvas;
import tech.molecules.chemflow.canvas.ElementHitTesterRegistry;
import tech.molecules.chemflow.canvas.ElementRendererRegistry;
import tech.molecules.chemflow.model.ChemFlowDocument;
import tech.molecules.chemflow.model.CommandHistory;
import tech.molecules.chemflow.model.ConnectorElement;
import tech.molecules.chemflow.model.ElementId;
import tech.molecules.chemflow.model.ElementTransform;
import tech.molecules.chemflow.model.ProjectNodeElement;
import tech.molecules.chemflow.model.Size2D;
import tech.molecules.structurized.prism.engine.PrismColumn;
import tech.molecules.structurized.prism.engine.PrismColumnType;
import tech.molecules.structurized.prism.engine.PrismRowGraph;
import tech.molecules.structurized.prism.engine.PrismRowGraphEdge;
import tech.molecules.structurized.prism.engine.PrismRowSet;
import tech.molecules.structurized.prism.engine.PrismSession;
import tech.molecules.structurized.prism.engine.PrismViewRecord;
import tech.molecules.structurized.prismlite.swing.workspace.PrismLiteWorkspaceController;
import tech.molecules.structurized.prismlite.swing.workspace.PrismLiteWorkspaceModel;
import tech.molecules.structurized.prismlite.swing.workspace.views.PrismSwingViewRenderer;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class ChemFlowProjectRiverViewRenderer implements PrismSwingViewRenderer {
    private static final Size2D BASE_NODE_SIZE = new Size2D(76, 38);
    private static final double FIT_PADDING = 42.0;
    private static final double IN_BATCH_COLLISION_OFFSET = 10.0;
    private static final double COLLISION_OVERLAP_FRACTION = 0.65;
    private static final double UNTANGLE_BASE_OFFSET = 6.0;
    private static final double UNTANGLE_MAX_OFFSET = 24.0;
    private static final int LEGEND_LIMIT = 8;
    private static final String MISSING_COLOR = "#e5e7eb";
    private static final String OTHER_COLOR = "#cbd5e1";
    private static final Map<String, RiverCameraState> CAMERA_STATES = new ConcurrentHashMap<>();
    private static final String[] PALETTE = {
            "#d9e8ff", "#dff3df", "#ffe6cc", "#f2ddff", "#d8f4f1", "#ffe0e5",
            "#e8e3d5", "#e0e7ff", "#fff2b8", "#e3f0c4", "#ffd9f0", "#dcecff"
    };
    private static final String[] NUMERIC_PALETTE = {
            "#eff6ff", "#bfdbfe", "#93c5fd", "#60a5fa", "#2563eb"
    };

    @Override
    public String viewType() {
        return ChemFlowProjectRiverViewSpec.VIEW_TYPE;
    }

    @Override
    public JComponent createComponent(
            PrismViewRecord view,
            PrismLiteWorkspaceModel model,
            PrismLiteWorkspaceController controller,
            Runnable refresh
    ) {
        if (!(view.specification() instanceof ChemFlowProjectRiverViewSpec spec)) {
            return message("Unsupported project-river specification.");
        }
        PrismSession session = model.session();
        PrismRowGraph graph;
        PrismRowSet rowSet;
        try {
            graph = session.graph(spec.graphId());
            rowSet = session.rowSet(spec.rowSetId());
        } catch (RuntimeException exception) {
            return message(exception.getMessage());
        }
        if (spec.dateColumnId() != null && session.table().findColumn(spec.dateColumnId()).isEmpty()) {
            return message("Unknown date/order column: " + spec.dateColumnId());
        }
        if (spec.colorColumnId() != null && session.table().findColumn(spec.colorColumnId()).isEmpty()) {
            return message("Unknown color column: " + spec.colorColumnId());
        }

        RiverDocument river = buildDocument(session, graph, rowSet, spec);
        if (river.rowByElementId().isEmpty()) {
            return message("No rows from row set " + rowSet.id() + " are renderable in graph " + graph.id() + ".");
        }

        ElementRendererRegistry renderers = ChemFlowCanvas.defaultRenderers();
        ElementHitTesterRegistry hitTesters = ChemFlowCanvas.defaultHitTesters();
        ChemFlowCanvas canvas = new ChemFlowCanvas(river.document(), new CommandHistory(), renderers, hitTesters);
        canvas.setPreferredSize(new Dimension(1180, 820));
        selectSessionRowsInCanvas(session, canvas, river);
        installCameraPersistence(canvas, spec);
        canvas.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event)) return;
                CanvasHit hit = canvas.hitTest(event.getPoint(), false).orElse(null);
                if (hit == null) return;
                String rowId = river.rowByElementId().get(hit.elementId());
                if (rowId != null) {
                    selectRow(session, model, rowId, event.getModifiersEx());
                    canvas.repaint();
                }
            }
        });
        restoreOrFitCamera(canvas, spec, river);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(canvas, BorderLayout.CENTER);
        panel.add(summaryBar(spec, graph, rowSet, river), BorderLayout.SOUTH);
        return panel;
    }

    @Override
    public JComponent createConfigurationComponent(
            PrismViewRecord view,
            PrismLiteWorkspaceModel model,
            PrismLiteWorkspaceController controller,
            Runnable refresh
    ) {
        if (!(view.specification() instanceof ChemFlowProjectRiverViewSpec spec)) {
            return null;
        }
        JPanel panel = new JPanel(new BorderLayout(6, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        JSlider nodeScale = new JSlider(45, 400, (int) Math.round(spec.nodeScale() * 100.0));
        nodeScale.setMajorTickSpacing(25);
        nodeScale.setPaintTicks(true);
        JLabel value = new JLabel(nodeScale.getValue() + "%");
        JComboBox<ProjectRiverNodeColorMode> colorMode = new JComboBox<>(ProjectRiverNodeColorMode.values());
        colorMode.setSelectedItem(spec.nodeColorMode());
        JComboBox<ColorColumnChoice> colorColumn = new JComboBox<>(colorColumnChoices(model.session(), spec));
        selectConfiguredColorColumn(colorColumn, spec.colorColumnId());
        colorColumn.setEnabled(spec.nodeColorMode().usesColumn());
        nodeScale.addChangeListener(event -> {
            value.setText(nodeScale.getValue() + "%");
            if (nodeScale.getValueIsAdjusting()) return;
            updateView(view, model, specWithVisuals(spec,
                    nodeScale.getValue() / 100.0,
                    (ProjectRiverNodeColorMode) colorMode.getSelectedItem(),
                    selectedColorColumnId(colorColumn)));
            refresh.run();
        });
        colorMode.addActionListener(event -> {
            ProjectRiverNodeColorMode mode = (ProjectRiverNodeColorMode) colorMode.getSelectedItem();
            colorColumn.setEnabled(mode != null && mode.usesColumn());
            updateView(view, model, specWithVisuals(spec,
                    nodeScale.getValue() / 100.0,
                    mode,
                    selectedColorColumnId(colorColumn)));
            refresh.run();
        });
        colorColumn.addActionListener(event -> {
            if (!colorColumn.isEnabled()) return;
            updateView(view, model, specWithVisuals(spec,
                    nodeScale.getValue() / 100.0,
                    (ProjectRiverNodeColorMode) colorMode.getSelectedItem(),
                    selectedColorColumnId(colorColumn)));
            refresh.run();
        });
        toolbar.add(new JLabel("Node size "));
        toolbar.add(nodeScale);
        toolbar.add(value);
        toolbar.addSeparator();
        toolbar.add(new JLabel("Color by "));
        toolbar.add(colorMode);
        toolbar.add(new JLabel(" Column "));
        toolbar.add(colorColumn);
        panel.add(toolbar, BorderLayout.CENTER);
        return panel;
    }

    private static void updateView(PrismViewRecord view, PrismLiteWorkspaceModel model, ChemFlowProjectRiverViewSpec updated) {
        Map<String, Object> provenance = new LinkedHashMap<>(view.provenance());
        provenance.put("updatedAt", Instant.now().toString());
        model.session().updateView(new PrismViewRecord(
                updated.viewId(), updated.viewType(), updated.title(), updated, view.createdAt(), provenance));
    }

    private static ChemFlowProjectRiverViewSpec specWithVisuals(ChemFlowProjectRiverViewSpec spec,
                                                               double nodeScale,
                                                               ProjectRiverNodeColorMode colorMode,
                                                               String colorColumnId) {
        ProjectRiverNodeColorMode mode = colorMode == null ? ProjectRiverNodeColorMode.ROOT_LINEAGE : colorMode;
        return new ChemFlowProjectRiverViewSpec(
                spec.viewId(),
                spec.title(),
                spec.graphId(),
                spec.rowSetId(),
                spec.structureColumnId(),
                spec.dateColumnId(),
                spec.labelColumnIds(),
                spec.minParentScore(),
                spec.xSpacing(),
                spec.laneSpacing(),
                spec.timeBatchSize(),
                nodeScale,
                mode,
                mode.usesColumn() ? colorColumnId : null
        );
    }

    private static ColorColumnChoice[] colorColumnChoices(PrismSession session, ChemFlowProjectRiverViewSpec spec) {
        ArrayList<ColorColumnChoice> choices = new ArrayList<>();
        choices.add(new ColorColumnChoice(null));
        for (PrismColumn column : session.table().columns()) {
            if (column.id().equals(spec.structureColumnId())) continue;
            choices.add(new ColorColumnChoice(column));
        }
        return choices.toArray(ColorColumnChoice[]::new);
    }

    private static void selectConfiguredColorColumn(JComboBox<ColorColumnChoice> colorColumn, String columnId) {
        if (columnId == null) return;
        for (int index = 0; index < colorColumn.getItemCount(); index++) {
            ColorColumnChoice choice = colorColumn.getItemAt(index);
            if (choice.column() != null && choice.column().id().equals(columnId)) {
                colorColumn.setSelectedIndex(index);
                return;
            }
        }
    }

    private static String selectedColorColumnId(JComboBox<ColorColumnChoice> colorColumn) {
        Object selected = colorColumn.getSelectedItem();
        return selected instanceof ColorColumnChoice choice && choice.column() != null ? choice.column().id() : null;
    }


    private static void restoreOrFitCamera(ChemFlowCanvas canvas, ChemFlowProjectRiverViewSpec spec, RiverDocument river) {
        SwingUtilities.invokeLater(() -> {
            int width = Math.max(1, canvas.getWidth());
            int height = Math.max(1, canvas.getHeight());
            RiverCameraState saved = CAMERA_STATES.get(spec.viewId());
            if (saved == null) {
                canvas.camera().fit(river.bounds(), width, height, FIT_PADDING);
            } else {
                Point2D screenCenter = new Point2D.Double(width / 2.0, height / 2.0);
                CanvasCamera previous = new CanvasCamera();
                previous.restore(saved.camera());
                Point2D oldCenter = previous.screenToWorld(screenCenter);
                canvas.camera().restore(saved.camera());
                double ratio = saved.nodeScale() <= 0.0 ? 1.0 : spec.nodeScale() / saved.nodeScale();
                if (Double.isFinite(ratio) && ratio > 0.0 && Math.abs(ratio - 1.0) > 1.0e-9) {
                    canvas.camera().focusWorldPointAtScreen(
                            new Point2D.Double(oldCenter.getX() * ratio, oldCenter.getY() * ratio),
                            screenCenter);
                }
            }
            saveCamera(canvas, spec);
            canvas.repaint();
        });
    }

    private static void installCameraPersistence(ChemFlowCanvas canvas, ChemFlowProjectRiverViewSpec spec) {
        MouseAdapter cameraSaver = new MouseAdapter() {
            @Override
            public void mouseDragged(MouseEvent event) {
                saveCamera(canvas, spec);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                saveCamera(canvas, spec);
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent event) {
                saveCamera(canvas, spec);
            }
        };
        canvas.addMouseMotionListener(cameraSaver);
        canvas.addMouseListener(cameraSaver);
        canvas.addMouseWheelListener(cameraSaver);
    }

    private static void saveCamera(ChemFlowCanvas canvas, ChemFlowProjectRiverViewSpec spec) {
        CAMERA_STATES.put(spec.viewId(), new RiverCameraState(canvas.camera().snapshot(), spec.nodeScale()));
    }

    private static RiverDocument buildDocument(PrismSession session,
                                               PrismRowGraph graph,
                                               PrismRowSet rowSet,
                                               ChemFlowProjectRiverViewSpec spec) {
        List<RiverRow> rows = orderedRows(session, graph, rowSet, spec.dateColumnId());
        RiverLayout layout = layoutForest(graph, rows, spec);
        RiverColorAssignment colors = colorAssignment(session, graph, rows, layout, spec);
        Map<String, ElementId> elementByRow = new LinkedHashMap<>();
        Map<ElementId, String> rowByElement = new LinkedHashMap<>();
        ChemFlowDocument document = new ChemFlowDocument();
        Size2D nodeSize = nodeSize(spec);
        double maxX = 0.0;
        double maxY = 0.0;

        for (RiverRow row : rows) {
            RiverPosition position = layout.positions().get(row.rowId());
            if (position == null) continue;
            ElementId elementId = nodeElementId(row.rowId());
            String detail = nodeDetail(session, spec, row);
            ProjectNodeElement node = ProjectNodeElement.create(
                    elementId,
                    new ElementTransform(position.x(), position.y()),
                    nodeSize,
                    row.rowId(),
                    detail,
                    colors.colorFor(row.rowId())
            );
            document.addElement(node);
            elementByRow.put(row.rowId(), elementId);
            rowByElement.put(elementId, row.rowId());
            maxX = Math.max(maxX, position.x() + nodeSize.width());
            maxY = Math.max(maxY, position.y() + nodeSize.height());
        }

        int renderedEdges = 0;
        for (Map.Entry<String, ParentChoice> entry : layout.parentByRow().entrySet()) {
            ParentChoice parent = entry.getValue();
            if (parent == null) continue;
            ElementId source = elementByRow.get(parent.parentRowId());
            ElementId target = elementByRow.get(entry.getKey());
            if (source == null || target == null) continue;
            document.insertElement(connector(document, source, target, entry.getKey()), renderedEdges);
            renderedEdges++;
        }
        Rectangle2D bounds = new Rectangle2D.Double(0, 0, Math.max(1.0, maxX), Math.max(1.0, maxY));
        return new RiverDocument(document, rowByElement, elementByRow.size(), renderedEdges, layout.rootCount(), layout.laneCount(), bounds, colors);
    }

    private static RiverColorAssignment colorAssignment(PrismSession session,
                                                       PrismRowGraph graph,
                                                       List<RiverRow> rows,
                                                       RiverLayout layout,
                                                       ChemFlowProjectRiverViewSpec spec) {
        return switch (spec.nodeColorMode()) {
            case GRAPH_COMPONENT -> graphComponentColors(graph, rows);
            case NUMERIC_COLUMN -> numericColumnColors(session, rows, spec.colorColumnId());
            case CATEGORICAL_COLUMN -> categoricalColumnColors(session, rows, spec.colorColumnId());
            case ROOT_LINEAGE -> rootLineageColors(rows, layout);
        };
    }

    private static RiverColorAssignment rootLineageColors(List<RiverRow> rows, RiverLayout layout) {
        Map<String, String> colors = new LinkedHashMap<>();
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (RiverRow row : rows) {
            RiverPosition position = layout.positions().get(row.rowId());
            if (position == null) continue;
            colors.put(row.rowId(), PALETTE[Math.floorMod(position.rootIndex(), PALETTE.length)]);
            counts.merge(position.rootIndex(), 1, Integer::sum);
        }
        List<LegendEntry> legend = counts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .limit(LEGEND_LIMIT)
                .map(entry -> new LegendEntry(PALETTE[Math.floorMod(entry.getKey(), PALETTE.length)],
                        "Root " + (entry.getKey() + 1) + " (" + entry.getValue() + ")"))
                .toList();
        return new RiverColorAssignment(colors, "root lineage | " + counts.size() + " roots", legend);
    }

    private static RiverColorAssignment graphComponentColors(PrismRowGraph graph, List<RiverRow> rows) {
        List<Set<String>> components = graphComponents(graph, rows);
        Map<String, String> colors = new LinkedHashMap<>();
        for (int index = 0; index < components.size(); index++) {
            String color = PALETTE[Math.floorMod(index, PALETTE.length)];
            for (String rowId : components.get(index)) colors.put(rowId, color);
        }
        List<LegendEntry> legend = new ArrayList<>();
        for (int index = 0; index < Math.min(LEGEND_LIMIT, components.size()); index++) {
            legend.add(new LegendEntry(PALETTE[Math.floorMod(index, PALETTE.length)],
                    "Component " + (index + 1) + " (" + components.get(index).size() + ")"));
        }
        return new RiverColorAssignment(colors, "graph component | " + components.size() + " components", legend);
    }

    private static List<Set<String>> graphComponents(PrismRowGraph graph, List<RiverRow> rows) {
        Set<String> rowIds = rows.stream().map(RiverRow::rowId).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<String, List<String>> adjacency = new HashMap<>();
        for (String rowId : rowIds) adjacency.put(rowId, new ArrayList<>());
        for (PrismRowGraphEdge edge : graph.edges()) {
            if (!rowIds.contains(edge.sourceRowId()) || !rowIds.contains(edge.targetRowId())) continue;
            adjacency.get(edge.sourceRowId()).add(edge.targetRowId());
            adjacency.get(edge.targetRowId()).add(edge.sourceRowId());
        }
        for (List<String> neighbors : adjacency.values()) neighbors.sort(String::compareTo);
        List<Set<String>> components = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        for (String rowId : rowIds.stream().sorted().toList()) {
            if (visited.contains(rowId)) continue;
            LinkedHashSet<String> component = new LinkedHashSet<>();
            ArrayList<String> queue = new ArrayList<>();
            queue.add(rowId);
            visited.add(rowId);
            for (int index = 0; index < queue.size(); index++) {
                String current = queue.get(index);
                component.add(current);
                for (String other : adjacency.getOrDefault(current, List.of())) {
                    if (visited.add(other)) queue.add(other);
                }
            }
            components.add(component);
        }
        components.sort(Comparator.<Set<String>>comparingInt(Set::size).reversed()
                .thenComparing(component -> component.iterator().next()));
        return components;
    }

    private static RiverColorAssignment numericColumnColors(PrismSession session, List<RiverRow> rows, String columnId) {
        PrismColumn column = columnId == null ? null : session.table().findColumn(columnId).orElse(null);
        if (column == null || !isNumericColumn(column)) {
            return neutralColors(rows, "numeric column | choose a numeric column");
        }
        Map<String, Double> values = new LinkedHashMap<>();
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (RiverRow row : rows) {
            Double value = numericValue(column, row.physicalRow());
            if (value == null || !Double.isFinite(value)) continue;
            values.put(row.rowId(), value);
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        if (values.isEmpty()) return neutralColors(rows, columnLabel(column) + " | no numeric values");
        Map<String, String> colors = new LinkedHashMap<>();
        for (RiverRow row : rows) {
            Double value = values.get(row.rowId());
            colors.put(row.rowId(), value == null ? MISSING_COLOR : numericColor(value, min, max));
        }
        List<LegendEntry> legend = numericLegend(min, max);
        return new RiverColorAssignment(colors, columnLabel(column) + " | low -> high", legend);
    }

    private static RiverColorAssignment categoricalColumnColors(PrismSession session, List<RiverRow> rows, String columnId) {
        PrismColumn column = columnId == null ? null : session.table().findColumn(columnId).orElse(null);
        if (column == null) return neutralColors(rows, "categorical column | choose a column");
        Map<String, Integer> counts = new HashMap<>();
        for (RiverRow row : rows) {
            String value = categoricalValue(column, row.physicalRow());
            if (value != null) counts.merge(value, 1, Integer::sum);
        }
        if (counts.isEmpty()) return neutralColors(rows, columnLabel(column) + " | no values");
        List<Map.Entry<String, Integer>> ranked = counts.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                        .thenComparing(Map.Entry::getKey))
                .toList();
        Map<String, String> valueColors = new HashMap<>();
        int categoryLimit = Math.min(PALETTE.length, ranked.size());
        for (int index = 0; index < categoryLimit; index++) {
            valueColors.put(ranked.get(index).getKey(), PALETTE[index]);
        }
        Map<String, String> colors = new LinkedHashMap<>();
        int otherCount = 0;
        for (RiverRow row : rows) {
            String value = categoricalValue(column, row.physicalRow());
            if (value == null) {
                colors.put(row.rowId(), MISSING_COLOR);
            } else if (valueColors.containsKey(value)) {
                colors.put(row.rowId(), valueColors.get(value));
            } else {
                colors.put(row.rowId(), OTHER_COLOR);
                otherCount++;
            }
        }
        List<LegendEntry> legend = new ArrayList<>();
        for (int index = 0; index < Math.min(LEGEND_LIMIT, categoryLimit); index++) {
            Map.Entry<String, Integer> entry = ranked.get(index);
            legend.add(new LegendEntry(PALETTE[index], entry.getKey() + " (" + entry.getValue() + ")"));
        }
        if (otherCount > 0 && legend.size() < LEGEND_LIMIT) legend.add(new LegendEntry(OTHER_COLOR, "Other (" + otherCount + ")"));
        return new RiverColorAssignment(colors, columnLabel(column) + " | " + counts.size() + " values", legend);
    }

    private static RiverColorAssignment neutralColors(List<RiverRow> rows, String summary) {
        Map<String, String> colors = new LinkedHashMap<>();
        for (RiverRow row : rows) colors.put(row.rowId(), MISSING_COLOR);
        return new RiverColorAssignment(colors, summary, List.of(new LegendEntry(MISSING_COLOR, "Missing/unavailable")));
    }

    private static String numericColor(double value, double min, double max) {
        if (max <= min) return NUMERIC_PALETTE[NUMERIC_PALETTE.length / 2];
        double fraction = Math.max(0.0, Math.min(0.999999, (value - min) / (max - min)));
        int index = (int) Math.floor(fraction * NUMERIC_PALETTE.length);
        return NUMERIC_PALETTE[Math.max(0, Math.min(NUMERIC_PALETTE.length - 1, index))];
    }

    private static List<LegendEntry> numericLegend(double min, double max) {
        if (max <= min) {
            return List.of(new LegendEntry(NUMERIC_PALETTE[NUMERIC_PALETTE.length / 2], formatNumber(min)));
        }
        ArrayList<LegendEntry> legend = new ArrayList<>();
        double width = (max - min) / NUMERIC_PALETTE.length;
        for (int index = 0; index < NUMERIC_PALETTE.length; index++) {
            double start = min + width * index;
            double end = index == NUMERIC_PALETTE.length - 1 ? max : start + width;
            legend.add(new LegendEntry(NUMERIC_PALETTE[index], formatNumber(start) + "-" + formatNumber(end)));
        }
        return legend;
    }

    private static boolean isNumericColumn(PrismColumn column) {
        return column.type() == PrismColumnType.NUMERIC || column.type() == PrismColumnType.INTEGER;
    }

    private static Double numericValue(PrismColumn column, int row) {
        if (column.isMissing(row)) return null;
        if (isNumericColumn(column)) return column.doubleValueAt(row);
        Object value = column.valueAt(row);
        if (value instanceof Number number) return number.doubleValue();
        try {
            return Double.parseDouble(column.formattedValueAt(row));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String categoricalValue(PrismColumn column, int row) {
        if (column.isMissing(row)) return null;
        String value = column.formattedValueAt(row);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String columnLabel(PrismColumn column) {
        return column.schema().displayName() == null || column.schema().displayName().isBlank()
                ? column.id()
                : column.schema().displayName();
    }

    private static String formatNumber(double value) {
        if (Math.abs(value) >= 100.0 || Math.abs(value) < 0.01 && value != 0.0) return String.format(java.util.Locale.ROOT, "%.2g", value);
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static RiverLayout layoutForest(PrismRowGraph graph, List<RiverRow> rows, ChemFlowProjectRiverViewSpec spec) {
        Map<String, List<PrismRowGraphEdge>> incident = incidentEdges(graph);
        Map<String, Integer> orderByRow = new LinkedHashMap<>();
        Map<String, RiverRow> rowById = new LinkedHashMap<>();
        Map<String, ParentChoice> parentByRow = new LinkedHashMap<>();
        Map<String, List<String>> childrenByParent = new HashMap<>();
        List<String> roots = new ArrayList<>();

        for (int index = 0; index < rows.size(); index++) {
            RiverRow row = rows.get(index);
            rowById.put(row.rowId(), row);
            ParentChoice parent = chooseParent(row.rowId(), incident.getOrDefault(row.rowId(), List.of()), orderByRow, spec.minParentScore());
            parentByRow.put(row.rowId(), parent);
            if (parent == null) {
                roots.add(row.rowId());
            } else {
                childrenByParent.computeIfAbsent(parent.parentRowId(), ignored -> new ArrayList<>()).add(row.rowId());
            }
            orderByRow.put(row.rowId(), index);
        }
        for (List<String> children : childrenByParent.values()) {
            children.sort(Comparator.comparingInt(orderByRow::get));
        }

        Map<String, Double> ySlotByRow = new HashMap<>();
        Map<String, Integer> rootIndexByRow = new HashMap<>();
        double[] nextSlot = {0.0};
        for (int rootIndex = 0; rootIndex < roots.size(); rootIndex++) {
            assignSubtreeSlots(roots.get(rootIndex), childrenByParent, ySlotByRow, rootIndexByRow, rootIndex, nextSlot);
        }

        Map<BatchSlotKey, Integer> duplicateCountBySlot = new HashMap<>();
        Map<String, RiverPosition> positions = new LinkedHashMap<>();
        double maxSlot = 0.0;
        for (RiverRow row : rows) {
            Integer order = orderByRow.get(row.rowId());
            Double slot = ySlotByRow.get(row.rowId());
            if (order == null || slot == null) continue;
            int batch = order / spec.timeBatchSize();
            BatchSlotKey key = new BatchSlotKey(batch, Math.round(slot * 1000.0));
            int duplicate = duplicateCountBySlot.merge(key, 1, Integer::sum) - 1;
            double x = batch * xSpacing(spec) + duplicate * IN_BATCH_COLLISION_OFFSET * spec.nodeScale();
            double y = slot * laneSpacing(spec);
            int rootIndex = rootIndexByRow.getOrDefault(row.rowId(), 0);
            positions.put(row.rowId(), new RiverPosition(x, y, rootIndex));
            maxSlot = Math.max(maxSlot, slot);
        }
        int laneCount = positions.isEmpty() ? 0 : (int) Math.ceil(maxSlot + 1.0);
        Map<String, RiverPosition> untangled = untangleOverlappingPositions(positions, nodeSize(spec), spec.nodeScale());
        return new RiverLayout(untangled, parentByRow, roots.size(), laneCount);
    }

    static Map<String, RiverPosition> untangleOverlappingPositions(Map<String, RiverPosition> positions, Size2D nodeSize, double nodeScale) {
        if (positions.size() < 2) return positions;
        List<String> rowIds = positions.keySet().stream().sorted().toList();
        List<Set<String>> groups = overlappingGroups(rowIds, positions, nodeSize);
        if (groups.isEmpty()) return positions;
        Map<String, RiverPosition> adjusted = new LinkedHashMap<>(positions);
        for (Set<String> group : groups) {
            List<String> sorted = group.stream().sorted().toList();
            double centerX = sorted.stream().map(adjusted::get).mapToDouble(RiverPosition::x).average().orElse(0.0);
            double centerY = sorted.stream().map(adjusted::get).mapToDouble(RiverPosition::y).average().orElse(0.0);
            double step = UNTANGLE_BASE_OFFSET * Math.max(0.45, nodeScale);
            double max = UNTANGLE_MAX_OFFSET * Math.max(0.45, nodeScale);
            for (int index = 0; index < sorted.size(); index++) {
                String rowId = sorted.get(index);
                RiverPosition original = adjusted.get(rowId);
                double radius = Math.min(max, step * (1.0 + index / 8.0));
                double angle = index * Math.PI * (3.0 - Math.sqrt(5.0));
                double dx = index == 0 ? 0.0 : Math.cos(angle) * radius;
                double dy = index == 0 ? 0.0 : Math.sin(angle) * radius;
                adjusted.put(rowId, new RiverPosition(centerX + dx, centerY + dy, original.rootIndex()));
            }
        }
        return adjusted;
    }

    private static List<Set<String>> overlappingGroups(List<String> rowIds, Map<String, RiverPosition> positions, Size2D nodeSize) {
        List<Set<String>> groups = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        for (String rowId : rowIds) {
            if (visited.contains(rowId)) continue;
            LinkedHashSet<String> group = new LinkedHashSet<>();
            ArrayList<String> queue = new ArrayList<>();
            queue.add(rowId);
            visited.add(rowId);
            for (int index = 0; index < queue.size(); index++) {
                String current = queue.get(index);
                group.add(current);
                for (String otherId : rowIds) {
                    if (visited.contains(otherId) || current.equals(otherId)) continue;
                    if (mostlyOverlaps(positions.get(current), positions.get(otherId), nodeSize)) {
                        visited.add(otherId);
                        queue.add(otherId);
                    }
                }
            }
            if (group.size() > 1) groups.add(group);
        }
        return groups;
    }

    private static boolean mostlyOverlaps(RiverPosition left, RiverPosition right, Size2D nodeSize) {
        if (left == null || right == null) return false;
        double overlapWidth = Math.min(left.x() + nodeSize.width(), right.x() + nodeSize.width()) - Math.max(left.x(), right.x());
        double overlapHeight = Math.min(left.y() + nodeSize.height(), right.y() + nodeSize.height()) - Math.max(left.y(), right.y());
        if (overlapWidth <= 0.0 || overlapHeight <= 0.0) return false;
        double overlap = overlapWidth * overlapHeight;
        double nodeArea = nodeSize.width() * nodeSize.height();
        return nodeArea > 0.0 && overlap / nodeArea >= COLLISION_OVERLAP_FRACTION;
    }

    private static Band assignSubtreeSlots(String rowId,
                                           Map<String, List<String>> childrenByParent,
                                           Map<String, Double> ySlotByRow,
                                           Map<String, Integer> rootIndexByRow,
                                           int rootIndex,
                                           double[] nextSlot) {
        rootIndexByRow.put(rowId, rootIndex);
        List<String> children = childrenByParent.getOrDefault(rowId, List.of());
        if (children.isEmpty()) {
            double slot = nextSlot[0]++;
            ySlotByRow.put(rowId, slot);
            return new Band(slot, slot, slot);
        }
        if (children.size() == 1) {
            Band child = assignSubtreeSlots(children.getFirst(), childrenByParent, ySlotByRow, rootIndexByRow, rootIndex, nextSlot);
            ySlotByRow.put(rowId, child.center());
            return child;
        }
        Band first = null;
        Band last = null;
        for (String childId : children) {
            Band child = assignSubtreeSlots(childId, childrenByParent, ySlotByRow, rootIndexByRow, rootIndex, nextSlot);
            if (first == null) first = child;
            last = child;
        }
        double min = first == null ? nextSlot[0] : first.min();
        double max = last == null ? min : last.max();
        double center = (min + max) / 2.0;
        ySlotByRow.put(rowId, center);
        return new Band(min, max, center);
    }

    private static List<RiverRow> orderedRows(PrismSession session, PrismRowGraph graph, PrismRowSet rowSet, String dateColumnId) {
        PrismColumn dateColumn = dateColumnId == null ? null : session.table().column(dateColumnId);
        ArrayList<RiverRow> rows = new ArrayList<>();
        for (String rowId : rowSet.rowIds()) {
            if (!graph.rowIds().contains(rowId)) continue;
            OptionalInt physical = session.physicalRowForRowId(rowId);
            if (physical.isEmpty()) continue;
            rows.add(new RiverRow(rowId, physical.getAsInt(), orderKey(dateColumn, physical.getAsInt()), orderText(dateColumn, physical.getAsInt())));
        }
        rows.sort(Comparator
                .comparing(RiverRow::orderKey)
                .thenComparingInt(RiverRow::physicalRow)
                .thenComparing(RiverRow::rowId));
        return List.copyOf(rows);
    }

    private static String orderKey(PrismColumn column, int row) {
        if (column == null || column.isMissing(row)) return String.format("row:%09d", row);
        String formatted = column.formattedValueAt(row);
        String normalized = normalizeDate(formatted);
        return normalized == null ? "value:" + formatted : "date:" + normalized;
    }

    private static String orderText(PrismColumn column, int row) {
        if (column == null || column.isMissing(row)) return "row " + (row + 1);
        return column.formattedValueAt(row);
    }

    private static String normalizeDate(String value) {
        if (value == null || value.isBlank()) return null;
        String text = value.trim();
        try {
            return Instant.parse(text).toString();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDate.parse(text).toString();
            } catch (DateTimeParseException ignoredAgain) {
                return null;
            }
        }
    }

    private static Map<String, List<PrismRowGraphEdge>> incidentEdges(PrismRowGraph graph) {
        Map<String, List<PrismRowGraphEdge>> incident = new HashMap<>();
        for (PrismRowGraphEdge edge : graph.edges()) {
            incident.computeIfAbsent(edge.sourceRowId(), ignored -> new ArrayList<>()).add(edge);
            incident.computeIfAbsent(edge.targetRowId(), ignored -> new ArrayList<>()).add(edge);
        }
        return incident;
    }

    private static ParentChoice chooseParent(String rowId,
                                             List<PrismRowGraphEdge> candidates,
                                             Map<String, Integer> processedOrder,
                                             double minParentScore) {
        ParentChoice best = null;
        for (PrismRowGraphEdge edge : candidates) {
            String other = edge.sourceRowId().equals(rowId) ? edge.targetRowId() : edge.sourceRowId();
            Integer order = processedOrder.get(other);
            if (order == null) continue;
            double score = edgeScore(edge);
            if (score < minParentScore) continue;
            ParentChoice choice = new ParentChoice(other, edge, score, order);
            if (best == null || compareParent(choice, best) < 0) best = choice;
        }
        return best;
    }

    private static int compareParent(ParentChoice left, ParentChoice right) {
        int byScore = Double.compare(right.score(), left.score());
        if (byScore != 0) return byScore;
        int byRecency = Integer.compare(right.processedOrder(), left.processedOrder());
        if (byRecency != 0) return byRecency;
        return left.parentRowId().compareTo(right.parentRowId());
    }

    private static double edgeScore(PrismRowGraphEdge edge) {
        Object similarity = edge.properties().get("similarity");
        if (similarity instanceof Number number) return number.doubleValue();
        if (similarity instanceof String text) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return 1.0;
            }
        }
        return 1.0;
    }

    private static Size2D nodeSize(ChemFlowProjectRiverViewSpec spec) {
        return new Size2D(BASE_NODE_SIZE.width() * spec.nodeScale(), BASE_NODE_SIZE.height() * spec.nodeScale());
    }

    private static double xSpacing(ChemFlowProjectRiverViewSpec spec) {
        return Math.max(spec.xSpacing() * spec.nodeScale(), nodeSize(spec).width() + 24.0 * spec.nodeScale());
    }

    private static double laneSpacing(ChemFlowProjectRiverViewSpec spec) {
        return Math.max(spec.laneSpacing() * spec.nodeScale(), nodeSize(spec).height() + 10.0 * spec.nodeScale());
    }

    private static String nodeDetail(PrismSession session, ChemFlowProjectRiverViewSpec spec, RiverRow row) {
        StringBuilder detail = new StringBuilder(row.orderText());
        for (String columnId : spec.labelColumnIds()) {
            PrismColumn column = session.table().column(columnId);
            if (!column.isMissing(row.physicalRow())) {
                if (!detail.isEmpty()) detail.append(" | ");
                detail.append(column.formattedValueAt(row.physicalRow()));
            }
        }
        return detail.toString();
    }

    private static ConnectorElement connector(ChemFlowDocument document, ElementId source, ElementId target, String rowId) {
        ProjectNodeElement sourceElement = (ProjectNodeElement) document.requireElement(source);
        ProjectNodeElement targetElement = (ProjectNodeElement) document.requireElement(target);
        Rectangle2D bounds = unionBounds(sourceElement, targetElement);
        return ConnectorElement.create(
                new ElementId("river-edge:" + safeId(rowId)),
                source,
                target,
                new ElementTransform(bounds.getX(), bounds.getY()),
                new Size2D(bounds.getWidth(), bounds.getHeight()),
                ""
        );
    }

    private static Rectangle2D unionBounds(ProjectNodeElement source, ProjectNodeElement target) {
        double minX = Math.min(source.transform().x(), target.transform().x());
        double minY = Math.min(source.transform().y(), target.transform().y());
        double maxX = Math.max(source.transform().x() + source.size().width(), target.transform().x() + target.size().width());
        double maxY = Math.max(source.transform().y() + source.size().height(), target.transform().y() + target.size().height());
        double padding = 8.0;
        return new Rectangle2D.Double(minX - padding, minY - padding, maxX - minX + 2.0 * padding, maxY - minY + 2.0 * padding);
    }

    private static void selectSessionRowsInCanvas(PrismSession session, ChemFlowCanvas canvas, RiverDocument river) {
        for (Map.Entry<ElementId, String> entry : river.rowByElementId().entrySet()) {
            OptionalInt physicalRow = session.physicalRowForRowId(entry.getValue());
            if (physicalRow.isPresent() && session.viewState().selectionModel().isSelected(physicalRow.getAsInt())) {
                canvas.selection().selectOnly(entry.getKey());
                return;
            }
        }
    }

    private static void selectRow(PrismSession session, PrismLiteWorkspaceModel model, String rowId, int modifiersEx) {
        OptionalInt physicalRow = session.physicalRowForRowId(rowId);
        if (physicalRow.isEmpty()) return;
        if (!additiveSelection(modifiersEx)) session.viewState().selectionModel().clear();
        session.viewState().selectionModel().setSelected(physicalRow.getAsInt(), true);
        model.setFocusedPhysicalRow(physicalRow.getAsInt());
    }

    private static boolean additiveSelection(int modifiersEx) {
        return (modifiersEx & (InputEvent.SHIFT_DOWN_MASK | InputEvent.CTRL_DOWN_MASK | InputEvent.META_DOWN_MASK)) != 0;
    }

    private static ElementId nodeElementId(String rowId) {
        return new ElementId("river-row:" + safeId(rowId));
    }

    private static String safeId(String value) {
        String safe = String.valueOf(value).trim().replaceAll("[^A-Za-z0-9_.:-]+", "_");
        return safe.isBlank() ? "item" : safe;
    }

    private static JPanel summaryBar(ChemFlowProjectRiverViewSpec spec, PrismRowGraph graph, PrismRowSet rowSet, RiverDocument river) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        panel.add(new JLabel(summaryHtml(spec, graph, rowSet, river)), BorderLayout.WEST);
        return panel;
    }

    private static String summaryHtml(ChemFlowProjectRiverViewSpec spec, PrismRowGraph graph, PrismRowSet rowSet, RiverDocument river) {
        StringBuilder builder = new StringBuilder("<html>");
        builder.append(escapeHtml(graph.title()))
                .append(" &nbsp;|&nbsp; row set ").append(escapeHtml(rowSet.name()))
                .append(" &nbsp;|&nbsp; ").append(river.renderedRowCount()).append(" rows")
                .append(" &nbsp;|&nbsp; ").append(river.renderedEdgeCount()).append(" parent edges")
                .append(" &nbsp;|&nbsp; ").append(river.rootCount()).append(" roots")
                .append(" &nbsp;|&nbsp; ").append(river.laneCount()).append(" lanes")
                .append(" &nbsp;|&nbsp; batch ").append(spec.timeBatchSize())
                .append(" &nbsp;|&nbsp; node ").append(Math.round(spec.nodeScale() * 100.0)).append("%")
                .append(" &nbsp;|&nbsp; color: ").append(escapeHtml(river.colorAssignment().summary()));
        if (!river.colorAssignment().legend().isEmpty()) {
            builder.append(" &nbsp;|&nbsp; ");
            for (int index = 0; index < river.colorAssignment().legend().size(); index++) {
                if (index > 0) builder.append(" &nbsp; ");
                LegendEntry entry = river.colorAssignment().legend().get(index);
                builder.append("<span style='background-color:").append(escapeHtml(entry.color()))
                        .append(";'>&nbsp;&nbsp;&nbsp;</span> ")
                        .append(escapeHtml(entry.label()));
            }
        }
        return builder.append("</html>").toString();
    }

    private static String escapeHtml(String value) {
        if (value == null) return "";
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            switch (ch) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                default -> escaped.append(ch);
            }
        }
        return escaped.toString();
    }

    private static JPanel message(String text) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(new JLabel(text), BorderLayout.CENTER);
        return panel;
    }

    private record RiverRow(String rowId, int physicalRow, String orderKey, String orderText) {}

    private record ParentChoice(String parentRowId, PrismRowGraphEdge edge, double score, int processedOrder) {}

    record RiverPosition(double x, double y, int rootIndex) {}

    private record RiverLayout(Map<String, RiverPosition> positions,
                               Map<String, ParentChoice> parentByRow,
                               int rootCount,
                               int laneCount) {}

    private record Band(double min, double max, double center) {}

    private record BatchSlotKey(int batch, long ySlotKey) {}

    private record RiverDocument(
            ChemFlowDocument document,
            Map<ElementId, String> rowByElementId,
            int renderedRowCount,
            int renderedEdgeCount,
            int rootCount,
            int laneCount,
            Rectangle2D bounds,
            RiverColorAssignment colorAssignment
    ) {}

    private record RiverColorAssignment(Map<String, String> colorsByRow, String summary, List<LegendEntry> legend) {
        private String colorFor(String rowId) {
            return colorsByRow.getOrDefault(rowId, MISSING_COLOR);
        }
    }

    private record LegendEntry(String color, String label) {}

    private record ColorColumnChoice(PrismColumn column) {
        @Override
        public String toString() {
            return column == null ? "-" : column.schema().displayName() + " [" + column.type() + "]";
        }
    }

    private record RiverCameraState(CanvasCamera.CameraState camera, double nodeScale) {}
}

