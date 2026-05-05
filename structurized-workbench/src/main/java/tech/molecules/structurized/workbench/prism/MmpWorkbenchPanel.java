package tech.molecules.structurized.workbench.prism;

import tech.molecules.structurized.analytics.mmp.MmpEndpointStatsComputationResult;
import tech.molecules.structurized.analytics.mmp.MmpEndpointStatsComputationService;
import tech.molecules.structurized.analytics.mmp.MmpEndpointStatsConfig;
import tech.molecules.structurized.analytics.mmp.MmpEndpointStatsRun;
import tech.molecules.structurized.analytics.mmp.MmpUniverseMode;
import tech.molecules.structurized.analytics.mmp.SqliteMmpAnalyticsRepository;
import tech.molecules.structurized.mmp.MmpMiningConfig;
import tech.molecules.structurized.mmp.MmpPair;
import tech.molecules.structurized.mmp.MmpTransformStats;
import tech.molecules.structurized.prism.model.EndpointDataType;
import tech.molecules.structurized.prism.model.EndpointDefinition;
import tech.molecules.structurized.prism.provider.SubjectSet;
import tech.molecules.structurized.workbench.model.PrismStructureProvider;
import tech.molecules.structurized.workbench.model.PrismWorkbenchModel;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Workbench panel for computing and browsing PRISM-backed MMP endpoint statistics.
 */
public final class MmpWorkbenchPanel extends JPanel {
    private static final String MEASURED_SET_TYPE = "ASSAY_MEASURED";
    private static final String MEASURED_SET_SCOPE = "ASSAYS";

    private final EndpointSetupTableModel endpointModel = new EndpointSetupTableModel();
    private final JTable endpointTable = new JTable(endpointModel);
    private final RunTableModel runModel = new RunTableModel();
    private final JTable runTable = new JTable(runModel);
    private final TransformStatsTableModel statsModel = new TransformStatsTableModel();
    private final JTable statsTable = new JTable(statsModel);
    private final ExamplePairTableModel pairModel = new ExamplePairTableModel();
    private final JTable pairTable = new JTable(pairModel);
    private final JTextField databaseField = new JTextField(34);
    private final JTextArea preflightArea = new JTextArea(7, 60);
    private final JLabel statusLabel = new JLabel("Load a PRISM repository to compute MMP endpoint statistics.");
    private final JButton runButton = new JButton("Run MMP Stats");
    private final JButton refreshRunsButton = new JButton("Refresh Runs");
    private final JSpinner maxCutsSpinner = new JSpinner(new SpinnerNumberModel(2, 1, 2, 1));
    private final JSpinner minSupportSpinner = new JSpinner(new SpinnerNumberModel(2, 1, 1000, 1));

    private PrismWorkbenchModel model;
    private PrismStructureProvider structureProvider;
    private Path databasePath;

    public MmpWorkbenchPanel() {
        super(new BorderLayout(8, 8));
        add(buildSetupPanel(), BorderLayout.NORTH);
        add(buildResultsPanel(), BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

        endpointModel.addTableModelListener(event -> refreshPreflight());
        runButton.addActionListener(event -> runComputation());
        refreshRunsButton.addActionListener(event -> refreshPersistedRuns());
        runTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                loadSelectedRunStats();
            }
        });
        statsTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                loadSelectedExamplePairs();
            }
        });
    }

    public void setModel(PrismWorkbenchModel model, PrismStructureProvider structureProvider) {
        this.model = model;
        this.structureProvider = structureProvider;
        endpointModel.setRows(buildEndpointRows(model));
        updateSubjectSetEditor();
        statsModel.setStats(List.of());
        pairModel.setPairs(List.of());
        refreshPreflight();
    }

    public void setDatabasePath(Path databasePath) {
        this.databasePath = databasePath;
        databaseField.setText(databasePath == null ? "" : databasePath.toString());
        refreshPreflight();
        refreshPersistedRuns();
    }

    public String getPreflightText() {
        return preflightArea.getText();
    }

    public boolean isRunEnabled() {
        return runButton.isEnabled();
    }

    private JPanel buildSetupPanel() {
        endpointTable.setAutoCreateRowSorter(true);
        endpointTable.setFillsViewportHeight(true);
        if (endpointTable.getRowSorter() instanceof TableRowSorter<?> sorter) {
            @SuppressWarnings("unchecked")
            TableRowSorter<EndpointSetupTableModel> typed = (TableRowSorter<EndpointSetupTableModel>) sorter;
            typed.setSortKeys(List.of(new RowSorter.SortKey(1, SortOrder.ASCENDING)));
        }

        JPanel databasePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        databaseField.setEditable(false);
        JButton chooseDatabaseButton = new JButton("Choose SQLite DB");
        chooseDatabaseButton.addActionListener(event -> chooseDatabase());
        databasePanel.add(new JLabel("MMP DB:"));
        databasePanel.add(databaseField);
        databasePanel.add(chooseDatabaseButton);
        databasePanel.add(refreshRunsButton);

        JPanel configPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        configPanel.add(new JLabel("Max cuts:"));
        configPanel.add(maxCutsSpinner);
        configPanel.add(new JLabel("Min support:"));
        configPanel.add(minSupportSpinner);
        configPanel.add(runButton);

        preflightArea.setEditable(false);
        preflightArea.setLineWrap(true);
        preflightArea.setWrapStyleWord(true);

        JPanel top = new JPanel(new BorderLayout(6, 6));
        top.setBorder(BorderFactory.createTitledBorder("MMP Run Setup"));
        top.add(databasePanel, BorderLayout.NORTH);
        top.add(new JScrollPane(endpointTable), BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout(6, 6));
        south.add(configPanel, BorderLayout.NORTH);
        south.add(new JScrollPane(preflightArea), BorderLayout.CENTER);
        top.add(south, BorderLayout.SOUTH);
        return top;
    }

    private JPanel buildResultsPanel() {
        runTable.setAutoCreateRowSorter(true);
        statsTable.setAutoCreateRowSorter(true);
        pairTable.setAutoCreateRowSorter(true);

        JSplitPane lowerSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(statsTable), new JScrollPane(pairTable));
        lowerSplit.setResizeWeight(0.72);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(runTable), lowerSplit);
        split.setResizeWeight(0.32);

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Persisted MMP Results"));
        panel.add(split, BorderLayout.CENTER);
        return panel;
    }

    private void chooseDatabase() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setFileFilter(new FileNameExtensionFilter("SQLite database (*.sqlite, *.db)", "sqlite", "db"));
        if (databasePath != null) {
            chooser.setSelectedFile(databasePath.toFile());
        }
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            setDatabasePath(chooser.getSelectedFile().toPath());
        }
    }

    private List<EndpointRow> buildEndpointRows(PrismWorkbenchModel model) {
        if (model == null) {
            return List.of();
        }
        List<SubjectSet> subjectSets = model.dataset().getSubjectSets();
        return model.dataset().getEndpointDefinitions().stream()
                .filter(MmpWorkbenchPanel::isNumeric)
                .sorted(Comparator.comparing(EndpointDefinition::getId))
                .map(endpoint -> new EndpointRow(
                        true,
                        endpoint,
                        inferSubjectSetId(endpoint, subjectSets).orElse("")
                ))
                .toList();
    }

    private void updateSubjectSetEditor() {
        ArrayList<String> ids = new ArrayList<>();
        ids.add("");
        if (model != null) {
            model.dataset().getSubjectSets().stream()
                    .map(SubjectSet::getId)
                    .sorted(String::compareToIgnoreCase)
                    .forEach(ids::add);
        }
        TableColumn column = endpointTable.getColumnModel().getColumn(3);
        column.setCellEditor(new DefaultCellEditor(new javax.swing.JComboBox<>(ids.toArray(String[]::new))));
    }

    private void refreshPreflight() {
        Preflight preflight = computePreflight();
        preflightArea.setText(preflight.message());
        runButton.setEnabled(preflight.canRun());
    }

    private Preflight computePreflight() {
        if (model == null || structureProvider == null) {
            return new Preflight(false, "No PRISM repository loaded.");
        }
        if (databasePath == null) {
            return new Preflight(false, "Choose a SQLite database before running MMP statistics.");
        }
        List<EndpointRow> selected = endpointModel.selectedRows();
        if (selected.isEmpty()) {
            return new Preflight(false, "Select at least one numeric endpoint.");
        }

        ArrayList<String> problems = new ArrayList<>();
        LinkedHashSet<String> unionSubjects = new LinkedHashSet<>();
        StringBuilder message = new StringBuilder();
        message.append("Selected endpoints: ").append(selected.size()).append('\n');
        for (EndpointRow row : selected) {
            String subjectSetId = normalize(row.subjectSetId());
            if (subjectSetId == null || model.dataset().findSubjectSet(subjectSetId).isEmpty()) {
                problems.add("Missing measured subject set for endpoint " + row.endpoint().getId());
                continue;
            }
            List<String> subjects = model.dataset().getSubjectsForSet(subjectSetId);
            if (subjects.isEmpty()) {
                problems.add("Subject set " + subjectSetId + " has no subjects");
            }
            unionSubjects.addAll(subjects);
            message.append(row.endpoint().getId()).append(" -> ").append(subjectSetId)
                    .append(" subjects=").append(subjects.size()).append('\n');
        }

        long structuralSubjects = unionSubjects.stream().filter(structureProvider.structureSubjectIds()::contains).count();
        long missingStructures = unionSubjects.size() - structuralSubjects;
        message.append("Union subjects: ").append(unionSubjects.size()).append('\n');
        message.append("Subjects with parsed structures: ").append(structuralSubjects).append('\n');
        message.append("Missing structures in selected sets: ").append(missingStructures).append('\n');
        message.append("Repository structure parse errors: ").append(structureProvider.parseErrorsBySubjectId().size()).append('\n');
        message.append("Database: ").append(databasePath).append('\n');
        if (!problems.isEmpty()) {
            message.append("Problems:\n");
            for (String problem : problems) {
                message.append("- ").append(problem).append('\n');
            }
        }
        boolean canRun = problems.isEmpty() && structuralSubjects >= 2;
        if (structuralSubjects < 2) {
            message.append("Need at least two selected subjects with parsed structures.\n");
        }
        return new Preflight(canRun, message.toString());
    }

    private void runComputation() {
        Preflight preflight = computePreflight();
        if (!preflight.canRun()) {
            preflightArea.setText(preflight.message());
            return;
        }
        List<EndpointRow> selected = endpointModel.selectedRows();
        MmpEndpointStatsConfig.Builder statsBuilder = MmpEndpointStatsConfig.builder()
                .universeMode(MmpUniverseMode.UNION_OF_ENDPOINT_SUBJECTS);
        for (EndpointRow row : selected) {
            statsBuilder.addEndpointId(row.endpoint().getId());
            statsBuilder.putEndpointSubjectSetId(row.endpoint().getId(), row.subjectSetId());
        }
        MmpMiningConfig miningConfig = MmpMiningConfig.defaults().toBuilder()
                .maxCuts((Integer) maxCutsSpinner.getValue())
                .minTransformSupport((Integer) minSupportSpinner.getValue())
                .build();
        MmpEndpointStatsConfig statsConfig = statsBuilder.build();
        Path db = databasePath;

        runButton.setEnabled(false);
        statusLabel.setText("Computing MMP endpoint statistics ...");
        new SwingWorker<MmpEndpointStatsComputationResult, Void>() {
            @Override
            protected MmpEndpointStatsComputationResult doInBackground() {
                try (SqliteMmpAnalyticsRepository repository = SqliteMmpAnalyticsRepository.open(db)) {
                    MmpEndpointStatsComputationService service = new MmpEndpointStatsComputationService(
                            model.dataset().endpointProvider(),
                            model.dataset().subjectSetProvider(),
                            structureProvider,
                            repository,
                            repository,
                            repository
                    );
                    return service.computeAndPersist(statsConfig, miningConfig);
                }
            }

            @Override
            protected void done() {
                try {
                    MmpEndpointStatsComputationResult result = get();
                    statusLabel.setText("MMP stats complete: endpoints=" + result.requestedEndpointCount()
                            + " universes=" + result.universes().size()
                            + " pairs=" + result.pairCount()
                            + " warnings=" + result.warnings().size());
                    if (!result.warnings().isEmpty()) {
                        preflightArea.setText(preflight.message() + "\nWarnings:\n- " + String.join("\n- ", result.warnings()));
                    }
                    refreshPersistedRuns();
                } catch (Exception e) {
                    statusLabel.setText("MMP stats failed: " + rootMessage(e));
                } finally {
                    refreshPreflight();
                }
            }
        }.execute();
    }

    private void refreshPersistedRuns() {
        if (databasePath == null) {
            runModel.setRuns(List.of());
            statsModel.setStats(List.of());
            pairModel.setPairs(List.of());
            return;
        }
        try (SqliteMmpAnalyticsRepository repository = SqliteMmpAnalyticsRepository.open(databasePath)) {
            runModel.setRuns(repository.listStatsRuns());
            statusLabel.setText("Loaded " + runModel.getRowCount() + " MMP stats runs from " + databasePath);
        } catch (Exception e) {
            statusLabel.setText("Failed to read MMP DB: " + rootMessage(e));
        }
    }

    private void loadSelectedRunStats() {
        int selectedRow = runTable.getSelectedRow();
        if (databasePath == null || selectedRow < 0) {
            statsModel.setStats(List.of());
            pairModel.setPairs(List.of());
            return;
        }
        MmpEndpointStatsRun run = runModel.runAt(runTable.convertRowIndexToModel(selectedRow));
        try (SqliteMmpAnalyticsRepository repository = SqliteMmpAnalyticsRepository.open(databasePath)) {
            statsModel.setStats(repository.listTransformStats(run.runId()));
            pairModel.setPairs(List.of());
            statusLabel.setText("Loaded " + statsModel.getRowCount() + " transform stats for " + run.runId());
        } catch (Exception e) {
            statusLabel.setText("Failed to read transform stats: " + rootMessage(e));
        }
    }

    private void loadSelectedExamplePairs() {
        int selectedRow = statsTable.getSelectedRow();
        if (selectedRow < 0) {
            pairModel.setPairs(List.of());
            return;
        }
        MmpTransformStats stats = statsModel.statAt(statsTable.convertRowIndexToModel(selectedRow));
        pairModel.setPairs(stats.examplePairs());
    }

    private static Optional<String> inferSubjectSetId(EndpointDefinition endpoint, List<SubjectSet> subjectSets) {
        List<String> candidates = List.of(
                endpoint.getId(),
                "assay:" + endpoint.getId() + ":measured",
                "assay-measured:" + endpoint.getId(),
                "endpoint:" + endpoint.getId() + ":measured",
                endpoint.getPath(),
                "assay:" + endpoint.getPath() + ":measured"
        );
        for (String candidate : candidates) {
            for (SubjectSet subjectSet : subjectSets) {
                if (candidate.equals(subjectSet.getId())) {
                    return Optional.of(candidate);
                }
            }
        }
        return subjectSets.stream()
                .filter(set -> MEASURED_SET_TYPE.equals(set.getSetType()))
                .filter(set -> MEASURED_SET_SCOPE.equals(set.getSubjectSetScope()))
                .filter(set -> containsIgnoreCase(set.getId(), endpoint.getId())
                        || containsIgnoreCase(set.getName(), endpoint.getName()))
                .map(SubjectSet::getId)
                .findFirst();
    }

    private static boolean isNumeric(EndpointDefinition endpoint) {
        return endpoint.getDatatype() == EndpointDataType.NUMERIC
                || endpoint.getDatatype() == EndpointDataType.OPTIONAL_NUMERIC;
    }

    private static boolean containsIgnoreCase(String text, String token) {
        return text != null && token != null && text.toLowerCase().contains(token.toLowerCase());
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }

    private record EndpointRow(boolean selected, EndpointDefinition endpoint, String subjectSetId) {}

    private record Preflight(boolean canRun, String message) {}

    private static final class EndpointSetupTableModel extends AbstractTableModel {
        private final String[] columns = {"Run", "Endpoint", "Name", "Measured Subject Set"};
        private List<EndpointRow> rows = List.of();

        void setRows(List<EndpointRow> rows) {
            this.rows = List.copyOf(rows == null ? List.of() : rows);
            fireTableDataChanged();
        }

        List<EndpointRow> selectedRows() {
            return rows.stream().filter(EndpointRow::selected).toList();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            EndpointRow row = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> row.selected();
                case 1 -> row.endpoint().getId();
                case 2 -> row.endpoint().getName();
                case 3 -> row.subjectSetId();
                default -> null;
            };
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            EndpointRow row = rows.get(rowIndex);
            EndpointRow next = switch (columnIndex) {
                case 0 -> new EndpointRow(Boolean.TRUE.equals(value), row.endpoint(), row.subjectSetId());
                case 3 -> new EndpointRow(row.selected(), row.endpoint(), Objects.toString(value, ""));
                default -> row;
            };
            ArrayList<EndpointRow> mutable = new ArrayList<>(rows);
            mutable.set(rowIndex, next);
            rows = List.copyOf(mutable);
            fireTableRowsUpdated(rowIndex, rowIndex);
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 0 || columnIndex == 3;
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 0 ? Boolean.class : Object.class;
        }
    }

    private static final class RunTableModel extends AbstractTableModel {
        private final String[] columns = {"Created", "Endpoint", "Subject Set", "Universe", "Subjects", "Values", "Pairs", "Stats"};
        private List<MmpEndpointStatsRun> runs = List.of();

        void setRuns(List<MmpEndpointStatsRun> runs) {
            this.runs = List.copyOf(runs == null ? List.of() : runs);
            fireTableDataChanged();
        }

        MmpEndpointStatsRun runAt(int row) {
            return runs.get(row);
        }

        @Override
        public int getRowCount() {
            return runs.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            MmpEndpointStatsRun run = runs.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> DateTimeFormatter.ISO_INSTANT.format(run.createdAt());
                case 1 -> run.endpointId();
                case 2 -> run.endpointSubjectSetId();
                case 3 -> run.universeId();
                case 4 -> run.subjectCount();
                case 5 -> run.valueCount();
                case 6 -> run.pairCount();
                case 7 -> run.statsCount();
                default -> null;
            };
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex >= 4 ? Integer.class : Object.class;
        }
    }

    private static final class TransformStatsTableModel extends AbstractTableModel {
        private final String[] columns = {"Transform", "Cuts", "Support", "Mean", "Median", "SD", "Min", "Max", "Positive"};
        private List<MmpTransformStats> stats = List.of();

        void setStats(List<MmpTransformStats> stats) {
            this.stats = List.copyOf(stats == null ? List.of() : stats);
            fireTableDataChanged();
        }

        MmpTransformStats statAt(int row) {
            return stats.get(row);
        }

        @Override
        public int getRowCount() {
            return stats.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            MmpTransformStats stat = stats.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> stat.transformId();
                case 1 -> stat.cutCount();
                case 2 -> stat.supportCount();
                case 3 -> stat.meanDelta();
                case 4 -> stat.medianDelta();
                case 5 -> stat.standardDeviation();
                case 6 -> stat.minDelta();
                case 7 -> stat.maxDelta();
                case 8 -> stat.positiveFraction();
                default -> null;
            };
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return switch (columnIndex) {
                case 1, 2 -> Integer.class;
                case 3, 4, 5, 6, 7, 8 -> Double.class;
                default -> Object.class;
            };
        }
    }

    private static final class ExamplePairTableModel extends AbstractTableModel {
        private final String[] columns = {"Compound A", "Compound B", "Value A", "Value B", "Delta", "Cuts", "Key"};
        private List<MmpPair> pairs = List.of();

        void setPairs(List<MmpPair> pairs) {
            this.pairs = List.copyOf(pairs == null ? List.of() : pairs);
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return pairs.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            MmpPair pair = pairs.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> pair.compoundIdA();
                case 1 -> pair.compoundIdB();
                case 2 -> pair.valueA();
                case 3 -> pair.valueB();
                case 4 -> pair.delta();
                case 5 -> pair.cutCount();
                case 6 -> pair.keyIdcode();
                default -> null;
            };
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return switch (columnIndex) {
                case 2, 3, 4 -> Double.class;
                case 5 -> Integer.class;
                default -> Object.class;
            };
        }
    }
}
