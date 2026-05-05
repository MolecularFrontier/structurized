package tech.molecules.structurized.workbench.prism;

import tech.molecules.structurized.prism.model.EndpointDataType;
import tech.molecules.structurized.prism.model.EndpointDefinition;
import tech.molecules.structurized.prism.provider.inmemory.InMemoryPrismDataset;
import tech.molecules.structurized.workbench.model.PrismWorkbenchModel;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Reusable endpoint table for PRISM repositories.
 */
public final class EndpointBrowserPanel extends JPanel {
    private final EndpointTableModel tableModel = new EndpointTableModel();
    private final JTable table = new JTable(tableModel);
    private Consumer<String> endpointSelectionListener = endpointId -> {};

    public EndpointBrowserPanel() {
        super(new BorderLayout());
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && table.getSelectedRow() >= 0) {
                int modelRow = table.convertRowIndexToModel(table.getSelectedRow());
                endpointSelectionListener.accept(tableModel.endpointAt(modelRow).getId());
            }
        });
        add(new JScrollPane(table), BorderLayout.CENTER);
    }

    public void setEndpointSelectionListener(Consumer<String> endpointSelectionListener) {
        this.endpointSelectionListener = endpointSelectionListener == null ? endpointId -> {} : endpointSelectionListener;
    }

    public void setModel(PrismWorkbenchModel model) {
        tableModel.setDataset(model == null ? null : model.dataset());
        if (table.getRowSorter() instanceof TableRowSorter<?> sorter) {
            @SuppressWarnings("unchecked")
            TableRowSorter<EndpointTableModel> typed = (TableRowSorter<EndpointTableModel>) sorter;
            typed.setSortKeys(List.of(new RowSorter.SortKey(0, SortOrder.ASCENDING)));
        }
    }

    private static final class EndpointTableModel extends AbstractTableModel {
        private final String[] columns = {"Endpoint", "Name", "Type", "Unit", "Scale", "Values"};
        private InMemoryPrismDataset dataset;
        private List<EndpointDefinition> endpoints = List.of();

        void setDataset(InMemoryPrismDataset dataset) {
            this.dataset = dataset;
            this.endpoints = dataset == null ? List.of() : dataset.getEndpointDefinitions().stream()
                    .sorted((a, b) -> a.getId().compareToIgnoreCase(b.getId()))
                    .toList();
            fireTableDataChanged();
        }

        EndpointDefinition endpointAt(int row) {
            return endpoints.get(row);
        }

        @Override
        public int getRowCount() {
            return endpoints.size();
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
            EndpointDefinition endpoint = endpoints.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> endpoint.getId();
                case 1 -> endpoint.getName();
                case 2 -> endpoint.getDatatype();
                case 3 -> endpoint.getUnit();
                case 4 -> endpoint.getNumericMeta() == null ? null : endpoint.getNumericMeta().getScale();
                case 5 -> valueCount(endpoint.getId());
                default -> null;
            };
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            if (columnIndex == 2) {
                return EndpointDataType.class;
            }
            if (columnIndex == 5) {
                return Integer.class;
            }
            return Object.class;
        }

        private int valueCount(String endpointId) {
            if (dataset == null) {
                return 0;
            }
            int count = 0;
            for (var value : dataset.getEndpointValues()) {
                if (endpointId.equals(value.getEndpointId())) {
                    count++;
                }
            }
            return count;
        }
    }
}
