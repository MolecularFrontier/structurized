package tech.molecules.structurized.workbench.prism;

import com.actelion.research.chem.Canonizer;
import com.actelion.research.chem.IDCodeParser;
import com.actelion.research.chem.Molecule;
import com.actelion.research.chem.StereoMolecule;
import com.actelion.research.gui.editor.SwingEditorPanel;
import tech.molecules.structurized.analytics.mmp.MmpEndpointPreference;
import tech.molecules.structurized.analytics.mmp.MmpEndpointStatsRun;
import tech.molecules.structurized.analytics.mmp.MmpOptimizationDirection;
import tech.molecules.structurized.analytics.mmp.MmpPairStructureEvidence;
import tech.molecules.structurized.analytics.mmp.MmpRecommendationCandidate;
import tech.molecules.structurized.analytics.mmp.MmpRecommendationDiagnostics;
import tech.molecules.structurized.analytics.mmp.MmpRecommendationRequest;
import tech.molecules.structurized.analytics.mmp.MmpRecommendationResult;
import tech.molecules.structurized.analytics.mmp.MmpRecommendationService;
import tech.molecules.structurized.analytics.mmp.SqliteMmpAnalyticsRepository;
import tech.molecules.structurized.mmp.MmpMiningConfig;
import tech.molecules.structurized.mmp.MmpPair;
import tech.molecules.structurized.mmp.MmpSelectionMode;
import tech.molecules.structurized.mmp.MmpTransformStats;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Interactive structure-to-MMP recommendation editor backed by one SQLite artifact. */
final class MmpRecommendationPanel extends JPanel {
    private final SwingEditorPanel editor = new SwingEditorPanel(new StereoMolecule());
    private final EndpointChoiceTableModel endpointModel = new EndpointChoiceTableModel();
    private final JTable endpointTable = new JTable(endpointModel);
    private final JComboBox<MmpSelectionMode> selectionMode =
            new JComboBox<>(MmpSelectionMode.values());
    private final JSpinner maxResults = new JSpinner(new SpinnerNumberModel(
            MmpRecommendationRequest.DEFAULT_MAX_RESULTS, 1, 5000, 50));
    private final JButton findButton = new JButton("Find transformations");
    private final JButton useProductButton = new JButton("Use product as input");
    private final JButton copyProductButton = new JButton("Copy product IDCode");
    private final JLabel status = new JLabel("Choose an MMP SQLite database.");
    private final RecommendationTableModel resultModel = new RecommendationTableModel();
    private final JTable resultTable = new JTable(resultModel);
    private final EvidencePanel evidencePanel = new EvidencePanel();
    private final AtomicLong searchGeneration = new AtomicLong();

    private Path databasePath;
    private SwingWorker<MmpRecommendationResult, Void> activeWorker;
    private boolean stale = true;

    MmpRecommendationPanel() {
        super(new BorderLayout(6, 6));
        selectionMode.setSelectedItem(MmpSelectionMode.EDITABLE_REGION);
        configureEndpointTable();
        configureResultTable();
        add(buildContent(), BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);

        findButton.addActionListener(event -> startSearch());
        useProductButton.addActionListener(event -> useSelectedProduct());
        copyProductButton.addActionListener(event -> copySelectedProduct());
        selectionMode.addActionListener(event -> markStale("Selection mode changed."));
        maxResults.addChangeListener(event -> markStale("Result limit changed."));
        endpointModel.addTableModelListener(event -> markStale("Endpoint settings changed."));
        editor.getDrawArea().addDrawAreaListener(event -> markStale("Structure or atom selection changed."));
        resultTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) updateSelectedCandidate();
        });
        updateActionState();
    }

    void setDatabasePath(Path databasePath) {
        this.databasePath = databasePath == null ? null : databasePath.toAbsolutePath().normalize();
        cancelActiveSearch();
        resultModel.setRows(List.of(), List.of());
        evidencePanel.clear();
        if (this.databasePath == null) {
            endpointModel.setRuns(List.of());
            status.setText("Choose an MMP SQLite database.");
            updateActionState();
            return;
        }
        try (SqliteMmpAnalyticsRepository repository =
                     SqliteMmpAnalyticsRepository.open(this.databasePath)) {
            endpointModel.setRuns(repository.listStatsRuns());
            status.setText("Loaded " + endpointModel.getRowCount()
                    + " endpoint runs. Draw a structure and select an editable region.");
        } catch (RuntimeException exception) {
            endpointModel.setRuns(List.of());
            status.setText("Could not load endpoint runs: " + rootMessage(exception));
        }
        stale = true;
        updateActionState();
    }

    private Component buildContent() {
        JPanel editorPanel = new JPanel(new BorderLayout(4, 4));
        editorPanel.setBorder(BorderFactory.createTitledBorder(
                "Input structure — use the lasso tool to select editable atoms"));
        editorPanel.add(editor, BorderLayout.CENTER);
        editorPanel.setPreferredSize(new Dimension(520, 330));

        JPanel endpoints = new JPanel(new BorderLayout(4, 4));
        endpoints.setBorder(BorderFactory.createTitledBorder("Endpoint preferences"));
        endpoints.add(new JScrollPane(endpointTable), BorderLayout.CENTER);
        endpoints.setPreferredSize(new Dimension(500, 330));

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        controls.add(new JLabel("Selection:"));
        controls.add(selectionMode);
        controls.add(new JLabel("Max results:"));
        controls.add(maxResults);
        controls.add(findButton);
        endpoints.add(controls, BorderLayout.SOUTH);

        JSplitPane inputSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, editorPanel, endpoints);
        inputSplit.setResizeWeight(0.5);

        JPanel resultPanel = new JPanel(new BorderLayout(4, 4));
        resultPanel.setBorder(BorderFactory.createTitledBorder("Generated proposals"));
        resultPanel.add(new JScrollPane(resultTable), BorderLayout.CENTER);
        JPanel resultActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        resultActions.add(useProductButton);
        resultActions.add(copyProductButton);
        resultPanel.add(resultActions, BorderLayout.SOUTH);

        JSplitPane evidenceSplit = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT, resultPanel, evidencePanel);
        evidenceSplit.setResizeWeight(0.62);
        JSplitPane mainSplit = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT, inputSplit, evidenceSplit);
        mainSplit.setResizeWeight(0.38);
        return mainSplit;
    }

    private void configureEndpointTable() {
        endpointTable.setFillsViewportHeight(true);
        endpointTable.getColumnModel().getColumn(0).setMaxWidth(48);
        endpointTable.getColumnModel().getColumn(1).setMaxWidth(64);
        endpointTable.getColumnModel().getColumn(3).setCellEditor(new DefaultCellEditor(
                new JComboBox<>(MmpOptimizationDirection.values())));
    }

    private void configureResultTable() {
        resultTable.setRowHeight(112);
        resultTable.setAutoCreateRowSorter(false);
        resultTable.setDefaultRenderer(StereoMolecule.class, new StructureCellRenderer());
        resultTable.setDefaultRenderer(TransformCell.class, new TransformRenderer());
        resultTable.setDefaultRenderer(EndpointStatCell.class, new EndpointStatRenderer());
        resultTable.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
    }

    private void startSearch() {
        MmpRecommendationRequest request;
        try {
            request = buildRequest();
        } catch (RuntimeException exception) {
            status.setText(rootMessage(exception));
            return;
        }
        cancelActiveSearch();
        long generation = searchGeneration.incrementAndGet();
        findButton.setEnabled(false);
        status.setText("Finding applicable transformations ...");
        activeWorker = new SwingWorker<>() {
            @Override
            protected MmpRecommendationResult doInBackground() {
                try (SqliteMmpAnalyticsRepository repository =
                             SqliteMmpAnalyticsRepository.open(databasePath)) {
                    return new MmpRecommendationService(repository).recommend(request);
                }
            }

            @Override
            protected void done() {
                if (generation != searchGeneration.get()) return;
                try {
                    applyResult(get(), endpointModel.selectedChoices());
                } catch (Exception exception) {
                    status.setText("Recommendation search failed: " + rootMessage(exception));
                } finally {
                    activeWorker = null;
                    updateActionState();
                }
            }
        };
        activeWorker.execute();
    }

    MmpRecommendationRequest buildRequest() {
        if (databasePath == null) {
            throw new IllegalStateException("Choose an MMP SQLite database first.");
        }
        StereoMolecule molecule = currentMolecule();
        if (molecule.getAllAtoms() == 0) {
            throw new IllegalArgumentException("Draw or paste a structure first.");
        }
        List<EndpointChoice> choices = endpointModel.selectedChoices();
        EndpointChoice primary = choices.stream()
                .filter(EndpointChoice::primary)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Select exactly one primary endpoint."));
        CanonicalEditorInput canonicalInput = canonicalEditorInput(molecule);
        Set<Integer> selectedAtoms = canonicalInput.selectedAtomIndices();
        MmpSelectionMode mode = (MmpSelectionMode) selectionMode.getSelectedItem();
        if (mode == null) mode = MmpSelectionMode.EDITABLE_REGION;
        if (mode.requiresSelection() && selectedAtoms.isEmpty()) {
            throw new IllegalArgumentException(
                    "Select editable atoms, or choose All sites.");
        }
        List<MmpEndpointPreference> preferences = choices.stream()
                .map(choice -> new MmpEndpointPreference(
                        choice.run().runId(), choice.direction()))
                .toList();
        return new MmpRecommendationRequest(
                canonicalInput.idcode(),
                selectedAtoms,
                mode,
                preferences,
                primary.run().runId(),
                MmpMiningConfig.defaults(),
                (Integer) maxResults.getValue(),
                MmpRecommendationRequest.DEFAULT_MAX_APPLICATION_ATTEMPTS);
    }

    private void applyResult(
            MmpRecommendationResult result,
            List<EndpointChoice> choices
    ) {
        stale = false;
        resultModel.setRows(result.candidates(), choices);
        configureDynamicColumns();
        evidencePanel.clear();
        if (resultModel.getRowCount() > 0) {
            resultTable.setRowSelectionInterval(0, 0);
        }
        status.setText(diagnosticsText(result.diagnostics()));
        updateActionState();
    }

    private void configureDynamicColumns() {
        resultTable.setRowSorter(new TableRowSorter<>(resultModel));
        if (resultTable.getColumnCount() > 0) {
            resultTable.getColumnModel().getColumn(0).setPreferredWidth(180);
            resultTable.getColumnModel().getColumn(1).setPreferredWidth(240);
            resultTable.getColumnModel().getColumn(2).setPreferredWidth(42);
            resultTable.getColumnModel().getColumn(3).setPreferredWidth(90);
        }
        if (resultTable.getRowSorter() instanceof TableRowSorter<?> rawSorter) {
            @SuppressWarnings("unchecked")
            TableRowSorter<RecommendationTableModel> sorter =
                    (TableRowSorter<RecommendationTableModel>) rawSorter;
            for (int column = 4; column < resultModel.getColumnCount(); column++) {
                sorter.setComparator(column, Comparator.comparingDouble(
                        cell -> ((EndpointStatCell) cell).sortValue()).reversed());
            }
        }
    }

    private void updateSelectedCandidate() {
        MmpRecommendationCandidate candidate = selectedCandidate();
        evidencePanel.setCandidate(candidate, resultModel.choices());
        updateActionState();
    }

    private void useSelectedProduct() {
        MmpRecommendationCandidate candidate = selectedCandidate();
        if (candidate == null || stale) return;
        StereoMolecule product = parse(candidate.productIdcode());
        editor.getDrawArea().setMolecule(product);
        resultModel.setRows(List.of(), List.of());
        evidencePanel.clear();
        stale = true;
        status.setText("Product copied to the editor. Select a region and search again.");
        updateActionState();
    }

    private void copySelectedProduct() {
        MmpRecommendationCandidate candidate = selectedCandidate();
        if (candidate == null || stale) return;
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(candidate.productIdcode()), null);
            status.setText("Product IDCode copied to the clipboard.");
        } catch (RuntimeException exception) {
            status.setText("Could not access the clipboard: " + rootMessage(exception));
        }
    }

    private MmpRecommendationCandidate selectedCandidate() {
        int viewRow = resultTable.getSelectedRow();
        if (viewRow < 0) return null;
        return resultModel.candidateAt(resultTable.convertRowIndexToModel(viewRow));
    }

    private void markStale(String reason) {
        if (activeWorker != null) cancelActiveSearch();
        if (resultModel.getRowCount() > 0) {
            stale = true;
            status.setText(reason + " Existing proposals are stale; run the search again.");
        }
        updateActionState();
    }

    private void cancelActiveSearch() {
        searchGeneration.incrementAndGet();
        if (activeWorker != null) {
            activeWorker.cancel(true);
            activeWorker = null;
        }
        updateActionState();
    }

    private void updateActionState() {
        boolean hasDatabase = databasePath != null && endpointModel.getRowCount() > 0;
        findButton.setEnabled(hasDatabase && activeWorker == null);
        boolean usableSelection = selectedCandidate() != null && !stale && activeWorker == null;
        useProductButton.setEnabled(usableSelection);
        copyProductButton.setEnabled(usableSelection);
    }

    private StereoMolecule currentMolecule() {
        StereoMolecule molecule = editor.getDrawArea().getMolecule();
        return molecule == null ? new StereoMolecule() : new StereoMolecule(molecule);
    }

    private static Set<Integer> selectedAtoms(StereoMolecule molecule) {
        LinkedHashSet<Integer> selected = new LinkedHashSet<>();
        for (int atom = 0; atom < molecule.getAllAtoms(); atom++) {
            if (molecule.isSelectedAtom(atom)) selected.add(atom);
        }
        return Set.copyOf(selected);
    }

    private static CanonicalEditorInput canonicalEditorInput(StereoMolecule molecule) {
        Canonizer canonizer = new Canonizer(molecule);
        String idcode = canonizer.getIDCode();
        int[] canonicalIndexByEditorAtom = canonizer.getGraphIndexes();
        LinkedHashSet<Integer> canonicalSelection = new LinkedHashSet<>();
        for (Integer editorAtom : selectedAtoms(molecule)) {
            canonicalSelection.add(canonicalIndexByEditorAtom[editorAtom]);
        }
        return new CanonicalEditorInput(idcode, Set.copyOf(canonicalSelection));
    }

    private static StereoMolecule parse(String idcode) {
        StereoMolecule molecule = new StereoMolecule();
        new IDCodeParser().parse(molecule, idcode);
        molecule.ensureHelperArrays(Molecule.cHelperCIP);
        return molecule;
    }

    private static String diagnosticsText(MmpRecommendationDiagnostics diagnostics) {
        return "Fragments=" + diagnostics.fragmentationCount()
                + " selected=" + diagnostics.selectedFragmentationCount()
                + " transforms=" + diagnostics.primaryTransformCount()
                + " attempts=" + diagnostics.applicationAttemptCount()
                + " applied=" + diagnostics.appliedCount()
                + " invalid=" + diagnostics.invalidCount()
                + " duplicate=" + diagnostics.duplicateCount()
                + " results=" + diagnostics.resultCount()
                + (diagnostics.truncated() ? " (truncated)" : "")
                + " time=" + diagnostics.duration().toMillis() + " ms";
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName()
                : message;
    }

    void setInputMolecule(StereoMolecule molecule, Set<Integer> selectedAtoms) {
        StereoMolecule copy = new StereoMolecule(molecule);
        for (int atom = 0; atom < copy.getAllAtoms(); atom++) {
            copy.setAtomSelection(atom, selectedAtoms != null && selectedAtoms.contains(atom));
        }
        editor.getDrawArea().setMolecule(copy);
    }

    void applyResultForTest(MmpRecommendationResult result) {
        applyResult(result, endpointModel.selectedChoices());
    }

    int resultCount() {
        return resultModel.getRowCount();
    }

    int evidenceCount() {
        return evidencePanel.rowCount();
    }

    private record CanonicalEditorInput(String idcode, Set<Integer> selectedAtomIndices) {
    }

    private record EndpointChoice(
            boolean selected,
            boolean primary,
            MmpEndpointStatsRun run,
            MmpOptimizationDirection direction
    ) {
        @Override
        public String toString() {
            return run.endpointId();
        }
    }

    private static final class EndpointChoiceTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Use", "Primary", "Endpoint", "Direction", "Stats"};
        private List<EndpointChoice> rows = List.of();

        void setRuns(List<MmpEndpointStatsRun> runs) {
            List<MmpEndpointStatsRun> sorted = runs == null ? List.of() : runs.stream()
                    .sorted(Comparator.comparing(MmpEndpointStatsRun::endpointId))
                    .toList();
            String defaultUniverse = sorted.isEmpty() ? null : sorted.getFirst().universeId();
            ArrayList<EndpointChoice> choices = new ArrayList<>();
            boolean primaryAssigned = false;
            for (MmpEndpointStatsRun run : sorted) {
                boolean selected = run.universeId().equals(defaultUniverse);
                boolean primary = selected && !primaryAssigned;
                primaryAssigned |= primary;
                choices.add(new EndpointChoice(
                        selected, primary, run, MmpOptimizationDirection.NEUTRAL));
            }
            rows = List.copyOf(choices);
            fireTableDataChanged();
        }

        List<EndpointChoice> selectedChoices() {
            return rows.stream().filter(EndpointChoice::selected).toList();
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int column) { return COLUMNS[column]; }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            EndpointChoice row = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> row.selected();
                case 1 -> row.primary();
                case 2 -> row.run().endpointId();
                case 3 -> row.direction();
                case 4 -> row.run().statsCount();
                default -> null;
            };
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return switch (columnIndex) {
                case 0, 1 -> Boolean.class;
                case 3 -> MmpOptimizationDirection.class;
                case 4 -> Integer.class;
                default -> Object.class;
            };
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 0 || columnIndex == 1 || columnIndex == 3;
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            ArrayList<EndpointChoice> mutable = new ArrayList<>(rows);
            EndpointChoice row = mutable.get(rowIndex);
            if (columnIndex == 0) {
                boolean selected = Boolean.TRUE.equals(value);
                mutable.set(rowIndex, new EndpointChoice(
                        selected, selected && row.primary(), row.run(), row.direction()));
            } else if (columnIndex == 1 && Boolean.TRUE.equals(value)) {
                for (int index = 0; index < mutable.size(); index++) {
                    EndpointChoice item = mutable.get(index);
                    mutable.set(index, new EndpointChoice(
                            item.selected() || index == rowIndex,
                            index == rowIndex,
                            item.run(),
                            item.direction()));
                }
            } else if (columnIndex == 3 && value instanceof MmpOptimizationDirection direction) {
                mutable.set(rowIndex, new EndpointChoice(
                        row.selected(), row.primary(), row.run(), direction));
            }
            rows = List.copyOf(mutable);
            fireTableDataChanged();
        }
    }

    private record TransformCell(String fromIdcode, String toIdcode, int cuts) {
    }

    private record EndpointStatCell(
            MmpTransformStats stats,
            MmpOptimizationDirection direction
    ) {
        double sortValue() {
            if (stats == null) return Double.NEGATIVE_INFINITY;
            return direction == MmpOptimizationDirection.NEUTRAL
                    ? stats.supportCount()
                    : direction.desiredDelta(stats.meanDelta());
        }
    }

    private static final class RecommendationTableModel extends AbstractTableModel {
        private List<MmpRecommendationCandidate> candidates = List.of();
        private List<EndpointChoice> choices = List.of();

        void setRows(
                List<MmpRecommendationCandidate> candidates,
                List<EndpointChoice> choices
        ) {
            this.candidates = List.copyOf(candidates == null ? List.of() : candidates);
            this.choices = List.copyOf(choices == null ? List.of() : choices);
            fireTableStructureChanged();
        }

        List<EndpointChoice> choices() {
            return choices;
        }

        MmpRecommendationCandidate candidateAt(int row) {
            return candidates.get(row);
        }

        @Override public int getRowCount() { return candidates.size(); }
        @Override public int getColumnCount() { return 4 + choices.size(); }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> "Product";
                case 1 -> "Transformation";
                case 2 -> "Cuts";
                case 3 -> "Site bonds";
                default -> choices.get(column - 4).run().endpointId();
            };
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            MmpRecommendationCandidate candidate = candidates.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> parse(candidate.productIdcode());
                case 1 -> new TransformCell(
                        candidate.transform().fromValueIdcode(),
                        candidate.transform().toValueIdcode(),
                        candidate.transform().cutCount());
                case 2 -> candidate.transform().cutCount();
                case 3 -> candidate.cutBondIndices().toString();
                default -> {
                    EndpointChoice choice = choices.get(columnIndex - 4);
                    yield new EndpointStatCell(
                            candidate.statsFor(choice.run().runId()), choice.direction());
                }
            };
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return switch (columnIndex) {
                case 0 -> StereoMolecule.class;
                case 1 -> TransformCell.class;
                case 2 -> Integer.class;
                default -> columnIndex >= 4 ? EndpointStatCell.class : String.class;
            };
        }
    }

    private static final class TransformRenderer extends JPanel implements TableCellRenderer {
        private final MoleculeViewPanel from = new MoleculeViewPanel();
        private final MoleculeViewPanel to = new MoleculeViewPanel();
        private final JLabel arrow = new JLabel("→", JLabel.CENTER);

        private TransformRenderer() {
            super(new GridLayout(1, 3, 2, 0));
            add(from);
            add(arrow);
            add(to);
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable table, Object value, boolean selected, boolean focus, int row, int column
        ) {
            Color background = selected ? table.getSelectionBackground() : table.getBackground();
            setBackground(background);
            from.setBackground(background);
            to.setBackground(background);
            if (value instanceof TransformCell transform) {
                from.setMolecule(MmpFragmentDepiction.parse(transform.fromIdcode()).molecule());
                to.setMolecule(MmpFragmentDepiction.parse(transform.toIdcode()).molecule());
                arrow.setText(transform.cuts() + "-cut  →");
            } else {
                from.setMolecule(null);
                to.setMolecule(null);
                arrow.setText("→");
            }
            return this;
        }
    }

    private static final class EndpointStatRenderer extends DefaultTableCellRenderer {
        private static final Color PALE_GREEN = new Color(220, 244, 224);
        private static final Color PALE_RED = new Color(250, 222, 222);
        private static final Color PALE_BLUE = new Color(222, 235, 250);
        private static final Color PALE_ORANGE = new Color(250, 235, 215);

        @Override
        public Component getTableCellRendererComponent(
                JTable table, Object value, boolean selected, boolean focus, int row, int column
        ) {
            super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            setHorizontalAlignment(CENTER);
            if (!(value instanceof EndpointStatCell cell) || cell.stats() == null) {
                setText("N/A");
                if (!selected) setBackground(new Color(240, 240, 240));
                setToolTipText(null);
                return this;
            }
            MmpTransformStats stats = cell.stats();
            setText("<html>N=" + stats.supportCount()
                    + "<br>mean=" + number(stats.meanDelta())
                    + "<br>median=" + number(stats.medianDelta())
                    + "<br>+=" + number(stats.positiveFraction() * 100.0) + "%</html>");
            setToolTipText("SD=" + number(stats.standardDeviation())
                    + ", range=" + number(stats.minDelta()) + " to " + number(stats.maxDelta()));
            if (!selected) setBackground(statColor(cell));
            return this;
        }

        private static Color statColor(EndpointStatCell cell) {
            double mean = cell.stats().meanDelta();
            if (mean == 0.0) return Color.WHITE;
            if (cell.direction() == MmpOptimizationDirection.NEUTRAL) {
                return mean > 0.0 ? PALE_BLUE : PALE_ORANGE;
            }
            return cell.direction().isPreferred(mean) ? PALE_GREEN : PALE_RED;
        }
    }

    private static final class EvidencePanel extends JPanel {
        private final JComboBox<EndpointChoice> endpoint = new JComboBox<>();
        private final EvidenceTableModel model = new EvidenceTableModel();
        private final JTable table = new JTable(model);
        private final JLabel message = new JLabel("Select a proposal to inspect persisted examples.");
        private MmpRecommendationCandidate candidate;

        private EvidencePanel() {
            super(new BorderLayout(4, 4));
            setBorder(BorderFactory.createTitledBorder(
                    "Persisted endpoint-valued examples (up to 10 per transformation)"));
            table.setRowHeight(92);
            table.setDefaultRenderer(StereoMolecule.class, new StructureCellRenderer());
            JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
            header.add(new JLabel("Endpoint:"));
            header.add(endpoint);
            header.add(message);
            add(header, BorderLayout.NORTH);
            add(new JScrollPane(table), BorderLayout.CENTER);
            endpoint.addActionListener(event -> refresh());
        }

        void setCandidate(
                MmpRecommendationCandidate candidate,
                List<EndpointChoice> choices
        ) {
            this.candidate = candidate;
            endpoint.removeAllItems();
            if (candidate != null) {
                for (EndpointChoice choice : choices) {
                    if (candidate.statsFor(choice.run().runId()) != null) endpoint.addItem(choice);
                }
            }
            if (endpoint.getItemCount() > 0) endpoint.setSelectedIndex(0);
            refresh();
        }

        void clear() {
            candidate = null;
            endpoint.removeAllItems();
            model.setRows(List.of());
            message.setText("Select a proposal to inspect persisted examples.");
        }

        int rowCount() {
            return model.getRowCount();
        }

        private void refresh() {
            EndpointChoice choice = (EndpointChoice) endpoint.getSelectedItem();
            if (candidate == null || choice == null) {
                model.setRows(List.of());
                return;
            }
            MmpTransformStats stats = candidate.statsFor(choice.run().runId());
            List<MmpPairStructureEvidence> rows = stats == null ? List.of() : stats.examplePairs()
                    .stream().map(MmpPairStructureEvidence::reconstruct).toList();
            model.setRows(rows);
            message.setText(rows.size() + " persisted examples");
        }
    }

    private static final class EvidenceTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {
                "Compound A", "ID A", "Value A", "Compound B", "ID B", "Value B", "Delta"
        };
        private List<MmpPairStructureEvidence> rows = List.of();

        void setRows(List<MmpPairStructureEvidence> rows) {
            this.rows = List.copyOf(rows == null ? List.of() : rows);
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int column) { return COLUMNS[column]; }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            MmpPairStructureEvidence evidence = rows.get(rowIndex);
            MmpPair pair = evidence.pair();
            return switch (columnIndex) {
                case 0 -> evidence.compoundAIdcode() == null
                        ? null : parse(evidence.compoundAIdcode());
                case 1 -> pair.compoundIdA();
                case 2 -> pair.valueA();
                case 3 -> evidence.compoundBIdcode() == null
                        ? null : parse(evidence.compoundBIdcode());
                case 4 -> pair.compoundIdB();
                case 5 -> pair.valueB();
                case 6 -> pair.delta();
                default -> null;
            };
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return switch (columnIndex) {
                case 0, 3 -> StereoMolecule.class;
                case 2, 5, 6 -> Double.class;
                default -> String.class;
            };
        }
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.4g", value);
    }
}
