package tech.molecules.structurized.gui;

import com.actelion.research.chem.Depictor2D;
import com.actelion.research.chem.StereoMolecule;
import com.actelion.research.gui.JStructureView;
import com.actelion.research.gui.generic.GenericRectangle;
import tech.molecules.structurized.decomposition.DecompositionConfig;
import tech.molecules.structurized.decomposition.DecompositionJson;
import tech.molecules.structurized.decomposition.DecompositionNode;
import tech.molecules.structurized.decomposition.DecompositionRule;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Standalone Swing viewer for running decomposition configs against SMILES files.
 */
public final class DecompositionSwingApp {
    private final JFrame frame;
    private final JTextField smilesFileField;
    private final JTextField configFileField;
    private final JButton runButton;
    private final JLabel statusLabel;
    private final JTextArea summaryArea;
    private final MoleculeTableModel moleculeTableModel;
    private final JTable moleculeTable;
    private final JStructureView moleculeView;
    private final TerminalNodeTableModel terminalNodeTableModel;
    private final JTable terminalNodeTable;
    private final JTextArea moleculeDetailArea;
    private final FragmentSummaryTableModel fragmentSummaryTableModel;
    private final JTable fragmentSummaryTable;
    private final JTextArea fragmentExampleArea;
    private final RuleTableModel ruleTableModel;
    private final JTable ruleTable;
    private final JTextArea configPreviewArea;
    private DecompositionGuiModel.RunModel runModel;

    private DecompositionSwingApp() {
        frame = new JFrame("Structurized Decomposition Viewer");
        smilesFileField = new JTextField(42);
        configFileField = new JTextField(42);
        runButton = new JButton("Run Decomposition");
        statusLabel = new JLabel("Choose a SMILES file and decomposition JSON config.");
        summaryArea = textArea();
        moleculeTableModel = new MoleculeTableModel();
        moleculeTable = new JTable(moleculeTableModel);
        moleculeView = new JStructureView();
        terminalNodeTableModel = new TerminalNodeTableModel();
        terminalNodeTable = new JTable(terminalNodeTableModel);
        moleculeDetailArea = textArea();
        fragmentSummaryTableModel = new FragmentSummaryTableModel();
        fragmentSummaryTable = new JTable(fragmentSummaryTableModel);
        fragmentExampleArea = textArea();
        ruleTableModel = new RuleTableModel();
        ruleTable = new JTable(ruleTableModel);
        configPreviewArea = textArea();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            DecompositionSwingApp app = new DecompositionSwingApp();
            if (args.length > 0) {
                app.smilesFileField.setText(args[0]);
            }
            if (args.length > 1) {
                app.configFileField.setText(args[1]);
            }
            app.show();
        });
    }

    private void show() {
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.setLayout(new BorderLayout(8, 8));
        frame.add(buildInputPanel(), BorderLayout.NORTH);
        frame.add(buildTabs(), BorderLayout.CENTER);
        frame.add(statusLabel, BorderLayout.SOUTH);
        frame.setSize(new Dimension(1500, 900));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private JPanel buildInputPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 4, 2, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridy = 0;

        addFileRow(panel, gbc, "SMILES", smilesFileField, () -> chooseFile(smilesFileField));
        gbc.gridy++;
        addFileRow(panel, gbc, "Config JSON", configFileField, () -> chooseFile(configFileField));
        gbc.gridy++;
        gbc.gridx = 2;
        gbc.weightx = 0;
        JPanel commands = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        commands.add(runButton);
        panel.add(commands, gbc);

        runButton.addActionListener(event -> runDecomposition());
        return panel;
    }

    private void addFileRow(JPanel panel, GridBagConstraints gbc, String label, JTextField field, Runnable browseAction) {
        gbc.gridx = 0;
        gbc.weightx = 0;
        panel.add(new JLabel(label), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        panel.add(field, gbc);
        gbc.gridx = 2;
        gbc.weightx = 0;
        JButton browse = new JButton("Browse");
        browse.addActionListener(event -> browseAction.run());
        panel.add(browse, gbc);
    }

    private JTabbedPane buildTabs() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Molecules", buildMoleculeTab());
        tabs.addTab("Fragments", buildFragmentTab());
        tabs.addTab("Config Preview", buildConfigTab());
        return tabs;
    }

    private JSplitPane buildMoleculeTab() {
        moleculeTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        moleculeTable.setAutoCreateRowSorter(true);
        moleculeTable.setRowSorter(new TableRowSorter<>(moleculeTableModel));
        moleculeTable.setRowHeight(72);
        moleculeTable.getColumnModel().getColumn(0).setCellRenderer(new StructureRenderer());
        moleculeTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                updateSelectedMolecule();
            }
        });

        moleculeView.setPreferredSize(new Dimension(460, 280));
        moleculeView.setBorder(BorderFactory.createTitledBorder("Selected Molecule"));
        terminalNodeTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        terminalNodeTable.setAutoCreateRowSorter(true);
        terminalNodeTable.setRowSorter(new TableRowSorter<>(terminalNodeTableModel));
        terminalNodeTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                updateTerminalHighlight();
            }
        });

        JPanel right = new JPanel(new BorderLayout(8, 8));
        right.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));
        JSplitPane lower = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(terminalNodeTable),
                new JScrollPane(moleculeDetailArea)
        );
        lower.setResizeWeight(0.34);
        right.add(moleculeView, BorderLayout.NORTH);
        right.add(lower, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(moleculeTable), right);
        split.setResizeWeight(0.62);
        return split;
    }

    private JSplitPane buildFragmentTab() {
        fragmentSummaryTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fragmentSummaryTable.setAutoCreateRowSorter(true);
        fragmentSummaryTable.setRowSorter(new TableRowSorter<>(fragmentSummaryTableModel));
        setNumericRenderer(fragmentSummaryTable, 2, 3, 4);
        fragmentSummaryTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                updateSelectedFragmentSummary();
            }
        });

        JSplitPane split = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                new JScrollPane(fragmentSummaryTable),
                new JScrollPane(fragmentExampleArea)
        );
        split.setResizeWeight(0.60);
        return split;
    }

    private JSplitPane buildConfigTab() {
        ruleTable.setAutoCreateRowSorter(true);
        ruleTable.setRowSorter(new TableRowSorter<>(ruleTableModel));
        JSplitPane split = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(ruleTable),
                new JScrollPane(configPreviewArea)
        );
        split.setResizeWeight(0.66);
        return split;
    }

    private void chooseFile(JTextField field) {
        JFileChooser chooser = new JFileChooser();
        if (!field.getText().isBlank()) {
            chooser.setSelectedFile(Path.of(field.getText()).toFile());
        }
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            field.setText(chooser.getSelectedFile().toPath().toString());
        }
    }

    private void runDecomposition() {
        String smilesPathText = smilesFileField.getText().trim();
        String configPathText = configFileField.getText().trim();
        if (smilesPathText.isEmpty() || configPathText.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Both input files are required.", "Missing Input", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Path smilesPath = Path.of(smilesPathText);
        Path configPath = Path.of(configPathText);
        setBusy(true, "Running decomposition...");

        SwingWorker<DecompositionGuiModel.RunModel, Void> worker = new SwingWorker<>() {
            @Override
            protected DecompositionGuiModel.RunModel doInBackground() throws Exception {
                List<SmilesInputReader.SmilesRecord> records = SmilesInputReader.readSmilesRecords(smilesPath);
                DecompositionConfig config = DecompositionJson.readConfig(Files.readString(configPath));
                return DecompositionGuiModel.evaluate(records, config);
            }

            @Override
            protected void done() {
                try {
                    setRunModel(get());
                    setBusy(false, "Decomposition finished.");
                } catch (Exception ex) {
                    setBusy(false, "Decomposition failed.");
                    JOptionPane.showMessageDialog(frame, ex.getMessage(), "Decomposition Failed", JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }

    private void setRunModel(DecompositionGuiModel.RunModel model) {
        this.runModel = model;
        summaryArea.setText(model.summaryText());
        moleculeTableModel.setRows(model.moleculeRows());
        fragmentSummaryTableModel.setRows(model.fragmentRows());
        ruleTableModel.setRules(model.config().rules());
        configPreviewArea.setText(DecompositionGuiModel.rulePreviewText(model.config(), model.validationProblems()));
        if (!model.moleculeRows().isEmpty()) {
            moleculeTable.setRowSelectionInterval(0, 0);
        } else {
            updateSelectedMolecule();
        }
        if (!model.fragmentRows().isEmpty()) {
            fragmentSummaryTable.setRowSelectionInterval(0, 0);
        } else {
            fragmentExampleArea.setText("");
        }
    }

    private void updateSelectedMolecule() {
        DecompositionGuiModel.MoleculeResultRow row = selectedMoleculeRow();
        if (row == null) {
            moleculeView.structureChanged();
            moleculeView.setAtomHighlightColors(null, null);
            terminalNodeTableModel.setNodes(List.of());
            moleculeDetailArea.setText("");
            return;
        }
        if (row.molecule() == null) {
            moleculeView.structureChanged();
            moleculeView.setAtomHighlightColors(null, null);
        } else {
            moleculeView.structureChanged(row.molecule());
        }
        moleculeDetailArea.setText(DecompositionGuiModel.detailText(row));
        moleculeDetailArea.setCaretPosition(0);
        terminalNodeTableModel.setNodes(row.terminalNodes());
        if (!row.terminalNodes().isEmpty()) {
            terminalNodeTable.setRowSelectionInterval(0, 0);
        } else {
            updateTerminalHighlight();
        }
    }

    private void updateTerminalHighlight() {
        DecompositionGuiModel.MoleculeResultRow row = selectedMoleculeRow();
        DecompositionNode node = selectedTerminalNode();
        if (row == null || row.molecule() == null || node == null) {
            moleculeView.setAtomHighlightColors(null, null);
            moleculeView.repaint();
            return;
        }
        int[] colors = new int[row.molecule().getAllAtoms()];
        float[] radii = new float[row.molecule().getAllAtoms()];
        int color = colorForLabel(node.label());
        for (int atom : node.atomIndices()) {
            if (atom >= 0 && atom < colors.length) {
                colors[atom] = color;
                radii[atom] = 0.45f;
            }
        }
        moleculeView.setAtomHighlightColors(colors, radii);
        moleculeView.repaint();
    }

    private void updateSelectedFragmentSummary() {
        DecompositionGuiModel.FragmentSummaryRow row = selectedFragmentRow();
        fragmentExampleArea.setText(row == null ? "" : DecompositionGuiModel.examplesText(row));
        fragmentExampleArea.setCaretPosition(0);
    }

    private DecompositionGuiModel.MoleculeResultRow selectedMoleculeRow() {
        int viewRow = moleculeTable.getSelectedRow();
        if (viewRow < 0) {
            return null;
        }
        return moleculeTableModel.rowAt(moleculeTable.convertRowIndexToModel(viewRow));
    }

    private DecompositionNode selectedTerminalNode() {
        int viewRow = terminalNodeTable.getSelectedRow();
        if (viewRow < 0) {
            return null;
        }
        return terminalNodeTableModel.nodeAt(terminalNodeTable.convertRowIndexToModel(viewRow));
    }

    private DecompositionGuiModel.FragmentSummaryRow selectedFragmentRow() {
        int viewRow = fragmentSummaryTable.getSelectedRow();
        if (viewRow < 0) {
            return null;
        }
        return fragmentSummaryTableModel.rowAt(fragmentSummaryTable.convertRowIndexToModel(viewRow));
    }

    private void setBusy(boolean busy, String message) {
        runButton.setEnabled(!busy);
        statusLabel.setText(message);
    }

    private static JTextArea textArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        return area;
    }

    private static void setNumericRenderer(JTable table, int... columns) {
        DefaultTableCellRenderer renderer = new DefaultTableCellRenderer();
        renderer.setHorizontalAlignment(DefaultTableCellRenderer.RIGHT);
        for (int column : columns) {
            table.getColumnModel().getColumn(column).setCellRenderer(renderer);
        }
    }

    private static int colorForLabel(String label) {
        int[] colors = {
                0x88ffb000,
                0x8800b7ff,
                0x8896ff00,
                0x88ff6680,
                0x8877dd77,
                0x88c28cff,
                0x88ffd166
        };
        int index = Math.floorMod(label == null ? 0 : label.hashCode(), colors.length);
        return colors[index];
    }

    private static final class MoleculeTableModel extends AbstractTableModel {
        private final String[] columns = {"Structure", "Molecule ID", "Status", "Root Rule", "Terminal Paths", "Problem", "Atoms"};
        private List<DecompositionGuiModel.MoleculeResultRow> rows = List.of();

        void setRows(List<DecompositionGuiModel.MoleculeResultRow> rows) {
            this.rows = List.copyOf(rows);
            fireTableDataChanged();
        }

        DecompositionGuiModel.MoleculeResultRow rowAt(int row) {
            return rows.get(row);
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
            DecompositionGuiModel.MoleculeResultRow row = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> row.molecule();
                case 1 -> row.moleculeId();
                case 2 -> row.statusText();
                case 3 -> row.rootRule();
                case 4 -> row.terminalPaths();
                case 5 -> row.problemSummary();
                case 6 -> row.atomCount();
                default -> null;
            };
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return switch (columnIndex) {
                case 0 -> StereoMolecule.class;
                case 6 -> Integer.class;
                default -> String.class;
            };
        }
    }

    private static final class TerminalNodeTableModel extends AbstractTableModel {
        private final String[] columns = {"Path", "Label", "Atoms", "Rule History"};
        private List<DecompositionNode> nodes = List.of();

        void setNodes(List<DecompositionNode> nodes) {
            this.nodes = List.copyOf(nodes);
            fireTableDataChanged();
        }

        DecompositionNode nodeAt(int row) {
            return nodes.get(row);
        }

        @Override
        public int getRowCount() {
            return nodes.size();
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
            DecompositionNode node = nodes.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> node.path();
                case 1 -> node.label();
                case 2 -> node.atomIndices();
                case 3 -> node.ruleHistory();
                default -> null;
            };
        }
    }

    private static final class FragmentSummaryTableModel extends AbstractTableModel {
        private final String[] columns = {"Path", "Label", "Support", "Distinct Fragments", "Singletons", "Example Molecules"};
        private List<DecompositionGuiModel.FragmentSummaryRow> rows = List.of();

        void setRows(List<DecompositionGuiModel.FragmentSummaryRow> rows) {
            this.rows = List.copyOf(rows);
            fireTableDataChanged();
        }

        DecompositionGuiModel.FragmentSummaryRow rowAt(int row) {
            return rows.get(row);
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
            DecompositionGuiModel.FragmentSummaryRow row = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> row.path();
                case 1 -> row.label();
                case 2 -> row.totalSupport();
                case 3 -> row.distinctFragmentCount();
                case 4 -> row.singletonCount();
                case 5 -> row.examples().stream().map(DecompositionGuiModel.FragmentExample::moleculeId).limit(8).collect(Collectors.joining(", "));
                default -> null;
            };
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex >= 2 && columnIndex <= 4 ? Integer.class : String.class;
        }
    }

    private static final class RuleTableModel extends AbstractTableModel {
        private final String[] columns = {"Order", "Enabled", "ID", "Label To Split", "SMARTS", "Atom Labels"};
        private List<DecompositionRule> rules = List.of();

        void setRules(List<DecompositionRule> rules) {
            this.rules = List.copyOf(rules);
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rules.size();
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
            DecompositionRule rule = rules.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> rowIndex + 1;
                case 1 -> rule.isEnabled();
                case 2 -> rule.id();
                case 3 -> rule.labelToSplit() == null ? "<root>" : rule.labelToSplit();
                case 4 -> rule.smarts();
                case 5 -> rule.atomLabels().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(entry -> entry.getKey() + "=" + entry.getValue())
                        .collect(Collectors.joining(", "));
                default -> null;
            };
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return switch (columnIndex) {
                case 0 -> Integer.class;
                case 1 -> Boolean.class;
                default -> String.class;
            };
        }
    }

    private static final class StructureRenderer extends JPanel implements TableCellRenderer {
        private StereoMolecule molecule;

        private StructureRenderer() {
            setBackground(Color.WHITE);
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable table,
                Object value,
                boolean isSelected,
                boolean hasFocus,
                int row,
                int column
        ) {
            molecule = value instanceof StereoMolecule mol ? new StereoMolecule(mol) : null;
            setBackground(isSelected ? table.getSelectionBackground() : Color.WHITE);
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (molecule == null) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Depictor2D depictor = new Depictor2D(molecule, Depictor2D.cDModeSuppressChiralText);
                GenericRectangle rectangle = new GenericRectangle(4, 4, Math.max(1, getWidth() - 8), Math.max(1, getHeight() - 8));
                depictor.validateView(g2, rectangle, Depictor2D.cModeInflateToMaxAVBL | Depictor2D.cDModeSuppressChiralText);
                depictor.paint(g2);
            } finally {
                g2.dispose();
            }
        }
    }
}
