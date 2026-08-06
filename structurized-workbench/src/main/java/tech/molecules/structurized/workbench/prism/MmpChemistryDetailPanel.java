package tech.molecules.structurized.workbench.prism;

import com.actelion.research.chem.StereoMolecule;
import tech.molecules.structurized.mmp.MmpPair;
import tech.molecules.structurized.mmp.MmpTransformStats;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Locale;

/** Large chemistry view for the selected persisted MMP transform and example pair. */
final class MmpChemistryDetailPanel extends JPanel {
    private final JLabel heading = new JLabel("Select a transformation");
    private final JLabel cutBadge = new JLabel(" ");
    private final ChemistryTile keyTile = new ChemistryTile("Example constant key");
    private final ChemistryTile fromTile = new ChemistryTile("From fragment");
    private final ChemistryTile toTile = new ChemistryTile("To fragment");
    private final JTextArea metadata = new JTextArea();
    private Integer displayedCutCount;

    MmpChemistryDetailPanel() {
        super(new BorderLayout(8, 8));
        setBorder(BorderFactory.createTitledBorder("Chemistry"));

        cutBadge.setOpaque(true);
        cutBadge.setBackground(new Color(232, 239, 250));
        cutBadge.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        cutBadge.setFont(cutBadge.getFont().deriveFont(Font.BOLD));
        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.add(heading, BorderLayout.CENTER);
        header.add(cutBadge, BorderLayout.EAST);

        JLabel arrow = new JLabel("→", JLabel.CENTER);
        arrow.setFont(arrow.getFont().deriveFont(Font.BOLD, 24f));
        JPanel transformation = new JPanel(new GridBagLayout());
        GridBagConstraints tile = new GridBagConstraints();
        tile.gridy = 0;
        tile.fill = GridBagConstraints.BOTH;
        tile.weightx = 1.0;
        tile.weighty = 1.0;
        tile.insets = new Insets(0, 0, 0, 8);
        tile.gridx = 0;
        transformation.add(keyTile, tile);
        tile.gridx = 1;
        transformation.add(fromTile, tile);
        GridBagConstraints arrowCell = new GridBagConstraints();
        arrowCell.gridx = 2;
        arrowCell.gridy = 0;
        arrowCell.insets = new Insets(0, 2, 0, 10);
        transformation.add(arrow, arrowCell);
        tile.gridx = 3;
        tile.insets = new Insets(0, 0, 0, 0);
        transformation.add(toTile, tile);

        metadata.setEditable(false);
        metadata.setLineWrap(true);
        metadata.setWrapStyleWord(true);
        metadata.setRows(5);
        metadata.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));

        add(header, BorderLayout.NORTH);
        add(transformation, BorderLayout.CENTER);
        add(metadata, BorderLayout.SOUTH);
        clear();
    }

    void showTransform(MmpTransformStats stats) {
        if (stats == null) {
            clear();
            return;
        }
        displayedCutCount = stats.cutCount();
        heading.setText("Directed transformation");
        cutBadge.setText(stats.cutCount() + "-cut");
        keyTile.showMessage("Select an example pair");
        fromTile.setIdcode(stats.fromValueIdcode());
        toTile.setIdcode(stats.toValueIdcode());
        metadata.setText(transformMetadata(stats));
        metadata.setCaretPosition(0);
    }

    void showPair(MmpTransformStats stats, MmpPair pair) {
        if (stats == null || pair == null) {
            showTransform(stats);
            return;
        }
        displayedCutCount = pair.cutCount();
        heading.setText(pair.compoundIdA() + " → " + pair.compoundIdB());
        cutBadge.setText(pair.cutCount() + "-cut");
        keyTile.setIdcode(pair.keyIdcode());
        fromTile.setIdcode(pair.fromValueIdcode());
        toTile.setIdcode(pair.toValueIdcode());
        metadata.setText(pairMetadata(stats, pair));
        metadata.setCaretPosition(0);
    }

    void clear() {
        displayedCutCount = null;
        heading.setText("Select a transformation");
        cutBadge.setText(" ");
        keyTile.showMessage("No example selected");
        fromTile.showMessage("No transformation selected");
        toTile.showMessage("No transformation selected");
        metadata.setText("");
    }

    Integer displayedCutCount() {
        return displayedCutCount;
    }

    StereoMolecule displayedKey() {
        return keyTile.molecule();
    }

    StereoMolecule displayedFrom() {
        return fromTile.molecule();
    }

    StereoMolecule displayedTo() {
        return toTile.molecule();
    }

    String displayedMetadata() {
        return metadata.getText();
    }

    private static String transformMetadata(MmpTransformStats stats) {
        return """
                Support: %d
                Mean delta: %s    Median delta: %s    SD: %s
                Range: %s to %s    Positive fraction: %s
                """.formatted(
                stats.supportCount(),
                number(stats.meanDelta()),
                number(stats.medianDelta()),
                number(stats.standardDeviation()),
                number(stats.minDelta()),
                number(stats.maxDelta()),
                number(stats.positiveFraction()));
    }

    private static String pairMetadata(MmpTransformStats stats, MmpPair pair) {
        return """
                Compound A: %s    Value A: %s
                Compound B: %s    Value B: %s    Delta: %s
                Support: %d    Mean delta: %s    Median delta: %s
                Attachment points: %s
                """.formatted(
                pair.compoundIdA(), number(pair.valueA()),
                pair.compoundIdB(), number(pair.valueB()), number(pair.delta()),
                stats.supportCount(), number(stats.meanDelta()), number(stats.medianDelta()),
                pair.cutCount() == 1 ? "R1" : "R1 and R2");
    }

    private static String number(Number number) {
        return number == null ? "—" : String.format(Locale.ROOT, "%.4g", number.doubleValue());
    }

    private static final class ChemistryTile extends JPanel {
        private final MoleculeViewPanel moleculeView = new MoleculeViewPanel();
        private final JLabel message = new JLabel("", JLabel.CENTER);

        private ChemistryTile(String title) {
            super(new BorderLayout());
            setBorder(BorderFactory.createTitledBorder(title));
            moleculeView.setPreferredSize(new Dimension(180, 145));
            message.setForeground(Color.DARK_GRAY);
            add(moleculeView, BorderLayout.CENTER);
            add(message, BorderLayout.SOUTH);
        }

        private void setIdcode(String idcode) {
            MmpFragmentDepiction.ParseResult parsed = MmpFragmentDepiction.parse(idcode);
            moleculeView.setMolecule(parsed.molecule());
            message.setText(parsed.isValid() ? connectorLegend(parsed.molecule()) : parsed.message());
        }

        private void showMessage(String text) {
            moleculeView.setMolecule(null);
            message.setText(text);
        }

        private StereoMolecule molecule() {
            return moleculeView.getMolecule();
        }

        private static String connectorLegend(StereoMolecule molecule) {
            boolean r1 = false;
            boolean r2 = false;
            for (int atom = 0; molecule != null && atom < molecule.getAllAtoms(); atom++) {
                String label = molecule.getAtomicNo(atom) == 0 ? molecule.getAtomCustomLabel(atom) : null;
                r1 |= "R1".equals(label);
                r2 |= "R2".equals(label);
            }
            if (r1 && r2) return "R1 blue  •  R2 orange";
            if (r1) return "R1 blue";
            return " ";
        }
    }
}
