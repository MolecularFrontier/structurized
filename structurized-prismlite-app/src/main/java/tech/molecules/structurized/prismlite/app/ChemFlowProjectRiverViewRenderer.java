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
    private static final Map<String, RiverCameraState> CAMERA_STATES = new ConcurrentHashMap<>();
    private static final String[] PALETTE = {
            "#d9e8ff", "#dff3df", "#ffe6cc", "#f2ddff", "#d8f4f1", "#ffe0e5",
            "#e8e3d5", "#e0e7ff", "#fff2b8", "#e3f0c4", "#ffd9f0", "#dcecff"
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
        nodeScale.addChangeListener(event -> {
            value.setText(nodeScale.getValue() + "%");
            if (nodeScale.getValueIsAdjusting()) return;
            ChemFlowProjectRiverViewSpec updated = new ChemFlowProjectRiverViewSpec(
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
                    nodeScale.getValue() / 100.0
            );
            updateView(view, model, updated);
            refresh.run();
        });
        toolbar.add(new JLabel("Node size "));
        toolbar.add(nodeScale);
        toolbar.add(value);
        panel.add(toolbar, BorderLayout.CENTER);
        return panel;
    }

    private static void updateView(PrismViewRecord view, PrismLiteWorkspaceModel model, ChemFlowProjectRiverViewSpec updated) {
        Map<String, Object> provenance = new LinkedHashMap<>(view.provenance());
        provenance.put("updatedAt", Instant.now().toString());
        model.session().updateView(new PrismViewRecord(
                updated.viewId(), updated.viewType(), updated.title(), updated, view.createdAt(), provenance));
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
                    PALETTE[Math.floorMod(position.rootIndex(), PALETTE.length)]
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
        return new RiverDocument(document, rowByElement, elementByRow.size(), renderedEdges, layout.rootCount(), layout.laneCount(), bounds);
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
        panel.add(new JLabel(graph.title()
                + "  |  row set " + rowSet.name()
                + "  |  " + river.renderedRowCount() + " rows"
                + "  |  " + river.renderedEdgeCount() + " parent edges"
                + "  |  " + river.rootCount() + " roots"
                + "  |  " + river.laneCount() + " lanes"
                + "  |  batch " + spec.timeBatchSize()
                + "  |  node " + Math.round(spec.nodeScale() * 100.0) + "%"), BorderLayout.WEST);
        return panel;
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
            Rectangle2D bounds
    ) {}

    private record RiverCameraState(CanvasCamera.CameraState camera, double nodeScale) {}
}

