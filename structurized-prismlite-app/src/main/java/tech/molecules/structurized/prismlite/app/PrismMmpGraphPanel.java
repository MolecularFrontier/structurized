package tech.molecules.structurized.prismlite.app;

import tech.molecules.structurized.ai.model.ChemOperationException;
import tech.molecules.structurized.ai.prism.CreatePrismGraphNeighborhoodRowSetRequest;
import tech.molecules.structurized.ai.prism.MinePrismMmpGraphRequest;
import tech.molecules.structurized.ai.prism.PrismBridgeService;
import tech.molecules.structurized.ai.prism.PrismColumnSummary;
import tech.molecules.structurized.ai.prism.PrismGraphNeighborhood;
import tech.molecules.structurized.ai.prism.PrismGraphSummary;
import tech.molecules.structurized.ai.prism.PrismMmpGraphSummary;
import tech.molecules.structurized.ai.prism.PrismRowSetSummary;
import tech.molecules.structurized.prism.engine.PrismViewRecord;
import tech.molecules.structurized.prism.engine.RowGraphNeighborhoodViewSpec;
import tech.molecules.structurized.prismlite.swing.workspace.PrismLiteWorkspaceModel;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

final class PrismMmpGraphPanel extends JPanel {
    private final PrismBridgeService bridge;
    private final String sessionId;
    private final PrismLiteWorkspaceModel workspaceModel;
    private final Runnable refreshWorkspace;
    private final Consumer<String> focusView;
    private final DefaultComboBoxModel<RowSetItem> rowSets = new DefaultComboBoxModel<>();
    private final DefaultComboBoxModel<ColumnItem> structureColumns = new DefaultComboBoxModel<>();
    private final DefaultComboBoxModel<ColumnItem> valueColumns = new DefaultComboBoxModel<>();
    private final DefaultListModel<GraphItem> graphModel = new DefaultListModel<>();
    private final JComboBox<RowSetItem> rowSetSelector = new JComboBox<>(rowSets);
    private final JComboBox<ColumnItem> structureColumnSelector = new JComboBox<>(structureColumns);
    private final JComboBox<ColumnItem> valueColumnSelector = new JComboBox<>(valueColumns);
    private final JList<GraphItem> graphs = new JList<>(graphModel);
    private final JTextField graphId = new JTextField();
    private final JTextField label = new JTextField();
    private final JTextField maxCuts = new JTextField("1");
    private final JTextField minSupport = new JTextField("1");
    private final JTextField maxVariableAtoms = new JTextField("16");
    private final JTextField maxVariableFraction = new JTextField("0.3");
    private final JTextArea details = new JTextArea();
    private final JLabel status = new JLabel(" ");

    PrismMmpGraphPanel(PrismBridgeService bridge,
                       String sessionId,
                       PrismLiteWorkspaceModel workspaceModel,
                       Runnable refreshWorkspace,
                       Consumer<String> focusView) {
        super(new BorderLayout(6, 6));
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.workspaceModel = Objects.requireNonNull(workspaceModel, "workspaceModel");
        this.refreshWorkspace = refreshWorkspace == null ? () -> { } : refreshWorkspace;
        this.focusView = focusView == null ? ignored -> { } : focusView;
        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        graphs.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) showSelectedGraph();
        });

        JPanel form = form();
        JScrollPane graphScroll = new JScrollPane(graphs);
        graphScroll.setPreferredSize(new Dimension(260, 320));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, graphScroll, new JScrollPane(details));
        split.setResizeWeight(0.28);
        add(form, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
        refreshLists();
    }

    private JPanel form() {
        JPanel panel = new JPanel(new BorderLayout(6, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        JPanel grid = new JPanel(new java.awt.GridLayout(0, 4, 6, 4));
        grid.add(new JLabel("Rows"));
        grid.add(rowSetSelector);
        grid.add(new JLabel("Structure"));
        grid.add(structureColumnSelector);
        grid.add(new JLabel("Value"));
        grid.add(valueColumnSelector);
        grid.add(new JLabel("Graph ID"));
        grid.add(graphId);
        grid.add(new JLabel("Label"));
        grid.add(label);
        grid.add(new JLabel("Max cuts"));
        grid.add(maxCuts);
        grid.add(new JLabel("Min support"));
        grid.add(minSupport);
        grid.add(new JLabel("Max variable atoms"));
        grid.add(maxVariableAtoms);
        grid.add(new JLabel("Max variable fraction"));
        grid.add(maxVariableFraction);
        panel.add(grid, BorderLayout.CENTER);

        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(event -> refreshLists());
        JButton mine = new JButton("Mine MMP Network");
        mine.addActionListener(event -> mineGraph());
        JButton inspect = new JButton("Inspect Focused Row");
        inspect.addActionListener(event -> inspectFocusedRow());
        JButton rowSet = new JButton("Create Row Set");
        rowSet.addActionListener(event -> createNeighborhoodRowSet());
        JButton chemFlow = new JButton("Open ChemFlow Neighborhood");
        chemFlow.addActionListener(event -> openChemFlowNeighborhood());
        toolbar.add(refresh);
        toolbar.add(mine);
        toolbar.add(Box.createHorizontalGlue());
        toolbar.add(inspect);
        toolbar.add(rowSet);
        toolbar.add(chemFlow);
        panel.add(toolbar, BorderLayout.SOUTH);
        return panel;
    }

    private void refreshLists() {
        try {
            rowSets.removeAllElements();
            for (PrismRowSetSummary rowSet : bridge.listRowSets(sessionId)) {
                rowSets.addElement(new RowSetItem(rowSet));
            }
            structureColumns.removeAllElements();
            valueColumns.removeAllElements();
            valueColumns.addElement(new ColumnItem(null));
            for (PrismColumnSummary column : bridge.listColumns(sessionId)) {
                if (isStructureColumn(column)) structureColumns.addElement(new ColumnItem(column));
                if ("NUMERIC".equals(column.type()) || "INTEGER".equals(column.type())) {
                    valueColumns.addElement(new ColumnItem(column));
                }
            }
            refreshGraphs();
            status.setText("Ready");
        } catch (RuntimeException exception) {
            showError("Could not refresh MMP panel", exception);
        }
    }

    private void refreshGraphs() {
        graphModel.clear();
        for (PrismGraphSummary graph : bridge.listGraphs(sessionId)) {
            graphModel.addElement(new GraphItem(graph));
        }
        if (!graphModel.isEmpty() && graphs.getSelectedIndex() < 0) {
            graphs.setSelectedIndex(0);
        }
    }

    private void mineGraph() {
        RowSetItem rowSet = (RowSetItem) rowSetSelector.getSelectedItem();
        ColumnItem structure = (ColumnItem) structureColumnSelector.getSelectedItem();
        ColumnItem value = (ColumnItem) valueColumnSelector.getSelectedItem();
        if (rowSet == null || structure == null || structure.column() == null) {
            status.setText("Choose rows and a structure column.");
            return;
        }
        status.setText("Mining MMP network...");
        new SwingWorker<PrismMmpGraphSummary, Void>() {
            @Override
            protected PrismMmpGraphSummary doInBackground() {
                return bridge.mineMmpGraph(new MinePrismMmpGraphRequest(
                        sessionId,
                        rowSet.rowSet().rowSetId(),
                        structure.column().columnId(),
                        value == null || value.column() == null ? null : value.column().columnId(),
                        blankToNull(graphId.getText()),
                        blankToNull(label.getText()),
                        intOrNull(maxCuts.getText()),
                        intOrNull(minSupport.getText()),
                        intOrNull(maxVariableAtoms.getText()),
                        doubleOrNull(maxVariableFraction.getText()),
                        null,
                        null
                ));
            }

            @Override
            protected void done() {
                try {
                    PrismMmpGraphSummary result = get();
                    refreshGraphs();
                    selectGraph(result.graph().graphId());
                    refreshWorkspace.run();
                    status.setText("Mined " + result.pairCount() + " MMP edges.");
                } catch (Exception exception) {
                    showError("MMP mining failed", exception);
                }
            }
        }.execute();
    }

    private void showSelectedGraph() {
        GraphItem item = graphs.getSelectedValue();
        if (item == null) {
            details.setText("");
            return;
        }
        PrismGraphSummary graph = bridge.summarizeGraph(sessionId, item.graph().graphId());
        details.setText(formatGraph(graph));
    }

    private void inspectFocusedRow() {
        GraphItem graph = graphs.getSelectedValue();
        String rowId = workspaceModel.focusedRowId();
        if (graph == null || rowId == null) {
            status.setText("Select a graph and focus a table row.");
            return;
        }
        try {
            PrismGraphNeighborhood neighborhood = bridge.inspectGraphNeighborhood(sessionId, graph.graph().graphId(), rowId, 50);
            details.setText(formatNeighborhood(neighborhood));
            status.setText("Found " + neighborhood.neighborCount() + " neighbors for " + rowId + ".");
        } catch (RuntimeException exception) {
            showError("Could not inspect graph neighborhood", exception);
        }
    }

    private void createNeighborhoodRowSet() {
        GraphItem graph = graphs.getSelectedValue();
        String rowId = workspaceModel.focusedRowId();
        if (graph == null || rowId == null) {
            status.setText("Select a graph and focus a table row.");
            return;
        }
        try {
            PrismRowSetSummary rowSet = bridge.createGraphNeighborhoodRowSet(new CreatePrismGraphNeighborhoodRowSetRequest(
                    sessionId, graph.graph().graphId(), rowId, true, null, null, null));
            refreshLists();
            refreshWorkspace.run();
            status.setText("Created row set " + rowSet.rowSetId() + " with " + rowSet.rowCount() + " rows.");
        } catch (RuntimeException exception) {
            showError("Could not create graph neighborhood row set", exception);
        }
    }

    private void openChemFlowNeighborhood() {
        GraphItem graph = graphs.getSelectedValue();
        String rowId = workspaceModel.focusedRowId();
        ColumnItem structure = (ColumnItem) structureColumnSelector.getSelectedItem();
        if (graph == null || rowId == null || structure == null || structure.column() == null) {
            status.setText("Select a graph, structure column, and focused table row.");
            return;
        }
        try {
            String viewId = neighborhoodViewId(graph.graph().graphId(), rowId);
            ColumnItem value = (ColumnItem) valueColumnSelector.getSelectedItem();
            List<String> labelColumns = value == null || value.column() == null
                    ? List.of()
                    : List.of(value.column().columnId());
            RowGraphNeighborhoodViewSpec spec = new RowGraphNeighborhoodViewSpec(
                    viewId,
                    graph.graph().title() + " around " + rowId,
                    graph.graph().graphId(),
                    rowId,
                    structure.column().columnId(),
                    labelColumns,
                    18,
                    false
            );
            PrismViewRecord record = new PrismViewRecord(
                    spec.viewId(),
                    spec.viewType(),
                    spec.title(),
                    spec,
                    Instant.now(),
                    Map.of("source", "mmp_graph_panel", "graphId", graph.graph().graphId(), "centerRowId", rowId)
            );
            boolean exists = workspaceModel.session().views().stream().anyMatch(view -> view.id().equals(viewId));
            if (exists) {
                workspaceModel.session().updateView(record);
            } else {
                workspaceModel.session().addView(record);
            }
            refreshWorkspace.run();
            focusView.accept(viewId);
            status.setText("Opened ChemFlow neighborhood for " + rowId + ".");
        } catch (RuntimeException exception) {
            showError("Could not open ChemFlow neighborhood", exception);
        }
    }

    private void selectGraph(String graphId) {
        for (int i = 0; i < graphModel.size(); i++) {
            if (graphModel.get(i).graph().graphId().equals(graphId)) {
                graphs.setSelectedIndex(i);
                return;
            }
        }
    }

    private static boolean isStructureColumn(PrismColumnSummary column) {
        return "MOLECULE".equals(column.type())
                || "chemical_structure".equals(column.semanticType())
                || "primary_structure".equals(column.role());
    }

    private static String neighborhoodViewId(String graphId, String rowId) {
        return "graph-neighborhood:" + safeId(graphId) + ":" + safeId(rowId);
    }

    private static String safeId(String value) {
        String safe = String.valueOf(value).trim().replaceAll("[^A-Za-z0-9_.:-]+", "_");
        return safe.isBlank() ? "item" : safe;
    }

    private static String formatGraph(PrismGraphSummary graph) {
        return graph.title() + "\n"
                + "ID: " + graph.graphId() + "\n"
                + "Type: " + graph.graphType() + "\n"
                + "Rows: " + graph.nodeCount() + "\n"
                + "Edges: " + graph.edgeCount() + "\n"
                + "Source row set: " + graph.sourceRowSetId() + "\n\n"
                + graph.metadata();
    }

    private static String formatNeighborhood(PrismGraphNeighborhood neighborhood) {
        StringBuilder builder = new StringBuilder();
        builder.append(neighborhood.graph().title()).append('\n');
        builder.append("Center: ").append(neighborhood.center().rowId()).append('\n');
        builder.append("Neighbors: ").append(neighborhood.neighborCount()).append('\n');
        builder.append("Incident edges: ").append(neighborhood.edgeCount()).append("\n\n");
        neighborhood.neighbors().forEach(neighbor -> {
            builder.append(neighbor.row().rowId())
                    .append("  degree=").append(neighbor.degree())
                    .append("  edges=").append(neighbor.edges().size())
                    .append('\n');
            neighbor.edges().stream().limit(3).forEach(edge -> builder
                    .append("  ").append(edge.sourceRowId()).append(" -> ").append(edge.targetRowId())
                    .append("  ").append(edge.properties().getOrDefault("transformId", edge.label()))
                    .append('\n'));
        });
        return builder.toString();
    }

    private void showError(String title, Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && !(cause instanceof ChemOperationException)) {
            cause = cause.getCause();
        }
        status.setText(cause.getMessage());
        JOptionPane.showMessageDialog(this, cause.getMessage(), title, JOptionPane.ERROR_MESSAGE);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Integer intOrNull(String value) {
        String text = blankToNull(value);
        return text == null ? null : Integer.valueOf(text);
    }

    private static Double doubleOrNull(String value) {
        String text = blankToNull(value);
        return text == null ? null : Double.valueOf(text);
    }

    private record RowSetItem(PrismRowSetSummary rowSet) {
        @Override
        public String toString() {
            return rowSet.name() + " (" + rowSet.rowCount() + ")";
        }
    }

    private record ColumnItem(PrismColumnSummary column) {
        @Override
        public String toString() {
            if (column == null) return "None";
            return column.displayName() + " [" + column.columnId() + "]";
        }
    }

    private record GraphItem(PrismGraphSummary graph) {
        @Override
        public String toString() {
            return graph.title() + " (" + graph.edgeCount() + " edges)";
        }
    }
}
