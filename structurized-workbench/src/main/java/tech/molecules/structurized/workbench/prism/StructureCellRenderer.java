package tech.molecules.structurized.workbench.prism;

import com.actelion.research.chem.StereoMolecule;

import javax.swing.JTable;
import javax.swing.table.TableCellRenderer;
import java.awt.Component;

/**
 * Table renderer for compact OpenChemLib molecule previews.
 */
public final class StructureCellRenderer extends MoleculeViewPanel implements TableCellRenderer {
    public StructureCellRenderer() {
        setOpaque(true);
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
        setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
        setMolecule(value instanceof StereoMolecule molecule ? molecule : null);
        return this;
    }
}
