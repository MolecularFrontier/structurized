package tech.molecules.structurized.workbench.prism;

import tech.molecules.structurized.prism.provider.SubjectSet;
import tech.molecules.structurized.workbench.model.PrismWorkbenchModel;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.util.List;
import java.util.function.Consumer;

/**
 * Reusable subject-set browser grouped by PRISM scope and set type.
 */
public final class SubjectSetBrowserPanel extends JPanel {
    private final SubjectSetTableModel tableModel = new SubjectSetTableModel();
    private final JTable table = new JTable(tableModel);
    private Consumer<String> subjectSetSelectionListener = subjectSetId -> {};

    public SubjectSetBrowserPanel() {
        super(new BorderLayout());
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && table.getSelectedRow() >= 0) {
                int modelRow = table.convertRowIndexToModel(table.getSelectedRow());
                subjectSetSelectionListener.accept(tableModel.subjectSetAt(modelRow).getId());
            }
        });
        add(new JScrollPane(table), BorderLayout.CENTER);
    }

    public void setSubjectSetSelectionListener(Consumer<String> listener) {
        this.subjectSetSelectionListener = listener == null ? subjectSetId -> {} : listener;
    }

    public void setModel(PrismWorkbenchModel model) {
        tableModel.setModel(model);
    }

    private static final class SubjectSetTableModel extends AbstractTableModel {
        private final String[] columns = {"Scope", "Type", "Subject Set", "Name", "Subjects"};
        private PrismWorkbenchModel model;
        private List<SubjectSet> subjectSets = List.of();

        void setModel(PrismWorkbenchModel model) {
            this.model = model;
            this.subjectSets = model == null ? List.of() : model.dataset().getSubjectSets().stream()
                    .sorted((a, b) -> (safe(a.getSubjectSetScope()) + safe(a.getSetType()) + a.getId())
                            .compareToIgnoreCase(safe(b.getSubjectSetScope()) + safe(b.getSetType()) + b.getId()))
                    .toList();
            fireTableDataChanged();
        }

        SubjectSet subjectSetAt(int row) {
            return subjectSets.get(row);
        }

        @Override
        public int getRowCount() {
            return subjectSets.size();
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
            SubjectSet subjectSet = subjectSets.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> subjectSet.getSubjectSetScope();
                case 1 -> subjectSet.getSetType();
                case 2 -> subjectSet.getId();
                case 3 -> subjectSet.getName();
                case 4 -> model == null ? 0 : model.dataset().getSubjectsForSet(subjectSet.getId()).size();
                default -> null;
            };
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 4 ? Integer.class : Object.class;
        }

        private static String safe(String value) {
            return value == null ? "" : value;
        }
    }
}
