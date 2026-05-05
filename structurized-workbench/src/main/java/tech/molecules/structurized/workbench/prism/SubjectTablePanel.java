package tech.molecules.structurized.workbench.prism;

import com.actelion.research.chem.StereoMolecule;
import tech.molecules.structurized.prism.provider.SubjectRecord;
import tech.molecules.structurized.workbench.model.PrismStructureProvider;
import tech.molecules.structurized.workbench.model.PrismWorkbenchModel;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Subject table with optional OpenChemLib structure previews.
 */
public final class SubjectTablePanel extends JPanel {
    private final SubjectTableModel tableModel = new SubjectTableModel();
    private final JTable table = new JTable(tableModel);
    private Consumer<String> subjectSelectionListener = subjectId -> {};

    public SubjectTablePanel() {
        super(new BorderLayout());
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(72);
        table.setAutoCreateRowSorter(true);
        table.getColumnModel().getColumn(0).setCellRenderer(new StructureCellRenderer());
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && table.getSelectedRow() >= 0) {
                int modelRow = table.convertRowIndexToModel(table.getSelectedRow());
                subjectSelectionListener.accept(tableModel.subjectAt(modelRow).getSubjectId());
            }
        });
        add(new JScrollPane(table), BorderLayout.CENTER);
    }

    public void setSubjectSelectionListener(Consumer<String> listener) {
        this.subjectSelectionListener = listener == null ? subjectId -> {} : listener;
    }

    public void setModel(PrismWorkbenchModel model, PrismStructureProvider structureProvider) {
        tableModel.setModel(model, structureProvider);
    }

    private static final class SubjectTableModel extends AbstractTableModel {
        private final String[] columns = {"Structure", "Subject", "Structure ID", "Project", "Series", "Batch", "SMILES"};
        private List<SubjectRecord> subjects = List.of();
        private Map<String, StereoMolecule> structures = Map.of();

        void setModel(PrismWorkbenchModel model, PrismStructureProvider structureProvider) {
            if (model == null) {
                subjects = List.of();
                structures = Map.of();
                fireTableDataChanged();
                return;
            }
            List<String> selectedIds = model.selectedSubjectIds();
            Map<String, SubjectRecord> byId = new LinkedHashMap<>();
            for (SubjectRecord subject : model.dataset().getSubjectRecords()) {
                byId.put(subject.getSubjectId(), subject);
            }
            subjects = selectedIds.stream()
                    .map(byId::get)
                    .filter(subject -> subject != null)
                    .toList();
            LinkedHashMap<String, StereoMolecule> nextStructures = new LinkedHashMap<>();
            if (structureProvider != null) {
                for (SubjectRecord subject : subjects) {
                    structureProvider.findStructure(subject.getSubjectId()).ifPresent(molecule ->
                            nextStructures.put(subject.getSubjectId(), molecule));
                }
            }
            structures = Map.copyOf(nextStructures);
            fireTableDataChanged();
        }

        SubjectRecord subjectAt(int row) {
            return subjects.get(row);
        }

        @Override
        public int getRowCount() {
            return subjects.size();
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
            SubjectRecord subject = subjects.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> structures.get(subject.getSubjectId());
                case 1 -> subject.getSubjectId();
                case 2 -> subject.getStructureId();
                case 3 -> subject.getProject();
                case 4 -> subject.getSeries();
                case 5 -> subject.getBatchId();
                case 6 -> subject.getSmiles();
                default -> null;
            };
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 0 ? StereoMolecule.class : Object.class;
        }
    }
}
