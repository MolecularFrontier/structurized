package tech.molecules.structurized.gui;

import com.actelion.research.chem.IDCodeParser;
import com.actelion.research.chem.IsomericSmilesCreator;
import com.actelion.research.chem.Molecule;
import com.actelion.research.chem.SmilesParser;
import com.actelion.research.chem.StereoMolecule;
import com.actelion.research.gui.JDrawPanel;
import com.actelion.research.gui.JStructureView;
import tech.molecules.structurized.OpenChemLibUtil;
import tech.molecules.structurized.transforms.OclStrictMcsProvider;
import tech.molecules.structurized.transforms.TransformationGroup;
import tech.molecules.structurized.transforms.TransformationSignature;
import tech.molecules.structurized.transforms.TransformationSplitter;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Minimal internal Swing app for inspecting the strict-MCS-based A/B transformation split.
 */
public final class PairTransformationSwingApp {
    private final JFrame frame;
    private final MoleculeEditorPanel editorA;
    private final MoleculeEditorPanel editorB;
    private final JButton analyzeButton;
    private final JButton swapButton;
    private final JSpinner radiusSpinner;
    private final JCheckBox keepMultiCenterCheckBox;
    private final JCheckBox allowNonStrictMcsCheckBox;
    private final JProgressBar progressBar;
    private final JLabel statusLabel;
    private final JStructureView coreView;
    private final JStructureView removedView;
    private final JStructureView addedView;
    private final JStructureView contextView;
    private final JStructureView visualCoreView;
    private final JPanel visualTransformListPanel;
    private final JTextArea visualSummaryArea;
    private final GroupTableModel groupTableModel;
    private final JTable groupTable;
    private final JTextArea detailArea;
    private PairAnalysis lastAnalysis;

    private PairTransformationSwingApp() {
        frame = new JFrame("structurized A/B Transformation Debugger");
        editorA = new MoleculeEditorPanel("Molecule A", "c1ccccc1C");
        editorB = new MoleculeEditorPanel("Molecule B", "c1ccccc1F");
        analyzeButton = new JButton("Analyze A -> B");
        swapButton = new JButton("Swap");
        radiusSpinner = new JSpinner(new SpinnerNumberModel(1, 0, 5, 1));
        keepMultiCenterCheckBox = new JCheckBox("Keep multi-center", true);
        allowNonStrictMcsCheckBox = new JCheckBox("Allow non-strict MCS", false);
        progressBar = new JProgressBar();
        statusLabel = new JLabel("Draw or paste two structures, then analyze.");
        coreView = new JStructureView();
        removedView = new JStructureView();
        addedView = new JStructureView();
        contextView = new JStructureView();
        visualCoreView = new JStructureView();
        visualTransformListPanel = new JPanel();
        visualSummaryArea = new JTextArea();
        groupTableModel = new GroupTableModel();
        groupTable = new JTable(groupTableModel);
        detailArea = new JTextArea();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // default look and feel is fine for this internal tool
            }
            new PairTransformationSwingApp().show();
        });
    }

    private void show() {
        editorA.loadInitialSmiles();
        editorB.loadInitialSmiles();

        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout(8, 8));
        frame.add(buildTopPanel(), BorderLayout.NORTH);
        frame.add(buildCenterPanel(), BorderLayout.CENTER);
        frame.add(buildStatusPanel(), BorderLayout.SOUTH);
        frame.setSize(new Dimension(1500, 900));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private JPanel buildTopPanel() {
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        controls.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        controls.add(analyzeButton);
        controls.add(swapButton);
        controls.add(new JLabel("Context radius"));
        controls.add(radiusSpinner);
        controls.add(keepMultiCenterCheckBox);
        controls.add(allowNonStrictMcsCheckBox);

        analyzeButton.addActionListener(event -> analyze());
        swapButton.addActionListener(event -> swapEditors());
        return controls;
    }

    private JSplitPane buildCenterPanel() {
        JSplitPane editorSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, editorA, editorB);
        editorSplit.setResizeWeight(0.5);

        JTabbedPane resultTabs = new JTabbedPane();
        resultTabs.addTab("Debug", buildDebugTab());
        resultTabs.addTab("Visual", buildVisualTab());

        JSplitPane mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editorSplit, resultTabs);
        mainSplit.setResizeWeight(0.44);
        return mainSplit;
    }

    private JSplitPane buildDebugTab() {
        JSplitPane resultSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildGroupPanel(), buildDetailPanel());
        resultSplit.setResizeWeight(0.48);
        return resultSplit;
    }

    private JSplitPane buildVisualTab() {
        visualCoreView.setBorder(BorderFactory.createTitledBorder("Shared Scaffold With Difference Atoms"));
        visualCoreView.setPreferredSize(new Dimension(460, 320));

        visualSummaryArea.setEditable(false);
        visualSummaryArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        visualSummaryArea.setLineWrap(true);
        visualSummaryArea.setWrapStyleWord(true);

        JPanel scaffoldPanel = new JPanel(new BorderLayout(8, 8));
        scaffoldPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 0));
        scaffoldPanel.add(visualCoreView, BorderLayout.NORTH);
        scaffoldPanel.add(new JScrollPane(visualSummaryArea), BorderLayout.CENTER);

        visualTransformListPanel.setLayout(new BoxLayout(visualTransformListPanel, BoxLayout.Y_AXIS));
        JScrollPane transformScroll = new JScrollPane(visualTransformListPanel);
        transformScroll.setBorder(BorderFactory.createTitledBorder("Transformations"));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scaffoldPanel, transformScroll);
        split.setResizeWeight(0.34);
        return split;
    }

    private JPanel buildGroupPanel() {
        coreView.setBorder(BorderFactory.createTitledBorder("Shared Strict MCS Core"));
        coreView.setPreferredSize(new Dimension(420, 250));

        groupTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        groupTable.setAutoCreateRowSorter(true);
        groupTable.setRowHeight(24);
        groupTable.setRowSorter(new TableRowSorter<>(groupTableModel));
        groupTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                updateSelectedGroupDetail();
            }
        });
        for (int column : new int[]{0}) {
            groupTable.getColumnModel().getColumn(column).setCellRenderer(new RightAlignedRenderer());
        }

        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
        panel.add(coreView, BorderLayout.NORTH);
        panel.add(new JScrollPane(groupTable), BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildDetailPanel() {
        removedView.setBorder(BorderFactory.createTitledBorder("Removed From A"));
        addedView.setBorder(BorderFactory.createTitledBorder("Added In B"));
        contextView.setBorder(BorderFactory.createTitledBorder("Attachment Context"));
        removedView.setPreferredSize(new Dimension(260, 210));
        addedView.setPreferredSize(new Dimension(260, 210));
        contextView.setPreferredSize(new Dimension(260, 210));

        JPanel structurePanel = new JPanel(new BorderLayout(8, 8));
        structurePanel.add(removedView, BorderLayout.WEST);
        structurePanel.add(addedView, BorderLayout.CENTER);
        structurePanel.add(contextView, BorderLayout.EAST);

        detailArea.setEditable(false);
        detailArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        detailArea.setLineWrap(true);
        detailArea.setWrapStyleWord(true);

        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));
        panel.add(structurePanel, BorderLayout.NORTH);
        panel.add(new JScrollPane(detailArea), BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildStatusPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
        progressBar.setIndeterminate(false);
        panel.add(statusLabel, BorderLayout.CENTER);
        panel.add(progressBar, BorderLayout.EAST);
        return panel;
    }

    private void analyze() {
        final StereoMolecule moleculeA;
        final StereoMolecule moleculeB;
        try {
            moleculeA = editorA.currentMolecule();
            moleculeB = editorB.currentMolecule();
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(frame, ex.getMessage(), "Invalid Structure", JOptionPane.WARNING_MESSAGE);
            return;
        }

        boolean allowNonStrictMcs = allowNonStrictMcsCheckBox.isSelected();
        setBusy(true, "Computing MCS and transformation groups...");
        int radius = (Integer) radiusSpinner.getValue();
        boolean keepMultiCenter = keepMultiCenterCheckBox.isSelected();

        SwingWorker<PairAnalysis, Void> worker = new SwingWorker<>() {
            @Override
            protected PairAnalysis doInBackground() {
                StereoMolecule a = new StereoMolecule(moleculeA);
                StereoMolecule b = new StereoMolecule(moleculeB);
                a.ensureHelperArrays(Molecule.cHelperRings);
                b.ensureHelperArrays(Molecule.cHelperRings);

                OclStrictMcsProvider.MCSMappingResult mappingResult =
                        new OclStrictMcsProvider().computeMCSMapping(a, b, !allowNonStrictMcs);
                if (mappingResult.failure() != null) {
                    return PairAnalysis.failure(a, b, mappingFailureText(mappingResult));
                }

                List<TransformationGroup> groups = TransformationSplitter.splitIntoTransformations(
                        a,
                        b,
                        mappingResult.mcsMap(),
                        radius,
                        TransformationSplitter.FeatureMask.DEFAULT
                );
                if (!keepMultiCenter) {
                    groups = new ArrayList<>(groups);
                    groups.removeIf(group -> group.type != tech.molecules.structurized.transforms.TransformationType.REPLACEMENT);
                }

                CoreDisplay core = createCoreDisplay(a, mappingResult.mcsMap());
                return PairAnalysis.success(a, b, mappingResult, core, groups);
            }

            @Override
            protected void done() {
                try {
                    applyAnalysis(get());
                } catch (Exception ex) {
                    clearResults();
                    JOptionPane.showMessageDialog(
                            frame,
                            "Failed to analyze structures:\n" + ex.getMessage(),
                            "Analysis Failed",
                            JOptionPane.ERROR_MESSAGE
                    );
                    statusLabel.setText("Analysis failed.");
                } finally {
                    setBusy(false, statusLabel.getText());
                }
            }
        };
        worker.execute();
    }

    private void applyAnalysis(PairAnalysis analysis) {
        lastAnalysis = analysis;
        if (analysis.failure != null) {
            clearViews();
            groupTableModel.setGroups(List.of());
            detailArea.setText(analysis.failure);
            clearVisualView();
            statusLabel.setText(analysis.failure);
            return;
        }

        updateVisualView(analysis);
        coreView.setBorder(BorderFactory.createTitledBorder(analysis.mappingStrict
                ? "Shared Strict MCS Core"
                : "Shared Non-Strict MCS Core"));
        coreView.structureChanged(analysis.core.molecule);
        groupTableModel.setGroups(analysis.groups);
        if (!analysis.groups.isEmpty()) {
            groupTable.setRowSelectionInterval(0, 0);
        } else {
            clearFragmentViews();
            detailArea.setText(mappingSummary(analysis)
                    + "\nNo outside-core differences were detected with the selected MCS mapping.");
            highlightCoreAttachments(null);
        }
        statusLabel.setText((analysis.mappingStrict ? "Strict" : "Non-strict")
                + " MCS atoms: " + analysis.core.molecule.getAtoms()
                + ", mappings A/B: " + analysis.mappingCountA + "/" + analysis.mappingCountB
                + ", ring bonds A/B: " + analysis.selectedRingBondsA + "/" + analysis.selectedRingBondsB
                + ", transformation groups: " + analysis.groups.size() + ".");
    }

    private void updateSelectedGroupDetail() {
        if (lastAnalysis == null || lastAnalysis.failure != null) {
            return;
        }
        int viewRow = groupTable.getSelectedRow();
        if (viewRow < 0) {
            clearFragmentViews();
            detailArea.setText("");
            highlightCoreAttachments(null);
            return;
        }

        int modelRow = groupTable.convertRowIndexToModel(viewRow);
        TransformationGroup group = groupTableModel.getGroupAt(modelRow);
        TransformationSignature signature = group.signature;

        setViewFromIdcode(removedView, signature.removedIdcode);
        setViewFromIdcode(addedView, signature.addedIdcode);
        setViewFromIdcode(contextView, signature.contextShellIdcode);
        highlightCoreAttachments(group);

        detailArea.setText("""
                %s

                Type: %s
                Reaction Class: %s
                Attachment Atoms In A: %s
                Signature ID: %s
                Radius: %d
                Expanded Raw Context Radius: %d
                Feature Mask: %d

                Removed SMILES: %s
                Added SMILES: %s
                Context SMILES: %s

                Removed IDCode:
                %s

                Added IDCode:
                %s

                Attachment Pattern:
                %s

                Context Shell IDCode:
                %s

                Expanded Raw Context IDCode:
                %s
                """.formatted(
                mappingSummary(lastAnalysis),
                group.type,
                signature.rxnClass,
                oneBased(group.attachmentsA),
                signature.sigId,
                signature.radiusR,
                signature.expandedRawContextRadius,
                signature.featureMask,
                smilesFromIdcode(signature.removedIdcode),
                smilesFromIdcode(signature.addedIdcode),
                smilesFromIdcode(signature.contextShellIdcode),
                nullToEmpty(signature.removedIdcode),
                nullToEmpty(signature.addedIdcode),
                signature.attachmentPattern,
                nullToEmpty(signature.contextShellIdcode),
                nullToEmpty(signature.expandedRawContextIdcode)
        ));
        detailArea.setCaretPosition(0);
    }

    private void swapEditors() {
        StereoMolecule moleculeA = editorA.currentMoleculeOrNull();
        StereoMolecule moleculeB = editorB.currentMoleculeOrNull();
        editorA.setMolecule(moleculeB);
        editorB.setMolecule(moleculeA);
        clearResults();
        statusLabel.setText("Swapped A and B.");
    }

    private void setBusy(boolean busy, String message) {
        analyzeButton.setEnabled(!busy);
        swapButton.setEnabled(!busy);
        radiusSpinner.setEnabled(!busy);
        keepMultiCenterCheckBox.setEnabled(!busy);
        allowNonStrictMcsCheckBox.setEnabled(!busy);
        editorA.setControlsEnabled(!busy);
        editorB.setControlsEnabled(!busy);
        progressBar.setIndeterminate(busy);
        statusLabel.setText(message);
    }

    private void clearResults() {
        lastAnalysis = null;
        clearViews();
        clearVisualView();
        groupTableModel.setGroups(List.of());
        detailArea.setText("");
    }

    private void clearViews() {
        coreView.structureChanged();
        coreView.setBorder(BorderFactory.createTitledBorder("Shared Strict MCS Core"));
        clearFragmentViews();
        coreView.setAtomHighlightColors(null, null);
    }

    private void clearFragmentViews() {
        removedView.structureChanged();
        addedView.structureChanged();
        contextView.structureChanged();
    }

    private void updateVisualView(PairAnalysis analysis) {
        AnnotatedCoreDisplay annotatedCore = createAnnotatedCoreDisplay(analysis);
        visualCoreView.structureChanged(annotatedCore.molecule);
        visualCoreView.setAtomText(annotatedCore.atomText);
        if (annotatedCore.hasHighlights()) {
            visualCoreView.setAtomHighlightColors(annotatedCore.colors, annotatedCore.radii);
        } else {
            visualCoreView.setAtomHighlightColors(null, null);
        }
        visualCoreView.repaint();

        visualSummaryArea.setText("""
                %s
                Highlighted Difference Atoms: %s
                Transformations: %d
                """.formatted(
                mappingSummary(analysis).trim(),
                annotatedCore.relevantAttachmentLabels,
                analysis.groups.size()
        ));
        visualSummaryArea.setCaretPosition(0);

        visualTransformListPanel.removeAll();
        if (analysis.groups.isEmpty()) {
            JTextArea emptyText = compactTextArea("No outside-core differences were detected with the selected MCS mapping.");
            emptyText.setAlignmentX(Component.LEFT_ALIGNMENT);
            visualTransformListPanel.add(emptyText);
        } else {
            for (int index = 0; index < analysis.groups.size(); index++) {
                JPanel row = createVisualTransformationRow(index + 1, analysis.groups.get(index));
                row.setAlignmentX(Component.LEFT_ALIGNMENT);
                visualTransformListPanel.add(row);
                visualTransformListPanel.add(Box.createRigidArea(new Dimension(0, 4)));
            }
        }
        visualTransformListPanel.revalidate();
        visualTransformListPanel.repaint();
    }

    private void clearVisualView() {
        visualCoreView.structureChanged();
        visualCoreView.setAtomText(null);
        visualCoreView.setAtomHighlightColors(null, null);
        visualSummaryArea.setText("");
        visualTransformListPanel.removeAll();
        visualTransformListPanel.revalidate();
        visualTransformListPanel.repaint();
    }

    private JPanel createVisualTransformationRow(int index, TransformationGroup group) {
        TransformationSignature signature = group.signature;
        JStructureView removed = new JStructureView();
        JStructureView added = new JStructureView();
        removed.setBorder(BorderFactory.createTitledBorder("From A"));
        added.setBorder(BorderFactory.createTitledBorder("In B"));
        Dimension fragmentSize = new Dimension(210, 90);
        removed.setPreferredSize(fragmentSize);
        added.setPreferredSize(fragmentSize);
        setViewFromIdcode(removed, signature.removedIdcode);
        setViewFromIdcode(added, signature.addedIdcode);

        JLabel arrow = new JLabel("->", JLabel.CENTER);
        arrow.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));

        JPanel structures = new JPanel(new GridLayout(1, 3, 8, 0));
        structures.add(removed);
        structures.add(arrow);
        structures.add(added);

        JTextArea metadata = compactTextArea("""
                #%d  %s
                R atoms: %s
                from A: %s
                in B: %s
                sig: %s
                """.formatted(
                index,
                group.type,
                oneBased(group.attachmentsA),
                smilesFromIdcode(signature.removedIdcode),
                smilesFromIdcode(signature.addedIdcode),
                signature.sigId.substring(0, Math.min(12, signature.sigId.length()))
        ));
        metadata.setPreferredSize(new Dimension(210, 90));

        JPanel row = new JPanel(new BorderLayout(8, 8));
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Transformation " + index),
                BorderFactory.createEmptyBorder(2, 4, 2, 4)
        ));
        row.add(metadata, BorderLayout.WEST);
        row.add(structures, BorderLayout.CENTER);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 132));
        return row;
    }

    private void highlightCoreAttachments(TransformationGroup group) {
        if (lastAnalysis == null || lastAnalysis.core == null) {
            return;
        }
        int atomCount = lastAnalysis.core.molecule.getAllAtoms();
        int[] colors = new int[atomCount];
        float[] radii = new float[atomCount];
        if (group != null) {
            for (int atomA : group.attachmentsA) {
                int coreAtom = lastAnalysis.core.mapAtoCore[atomA];
                if (coreAtom >= 0 && coreAtom < atomCount) {
                    colors[coreAtom] = 0xffffc400;
                    radii[coreAtom] = 0.68f;
                }
            }
        }
        if (Arrays.stream(colors).anyMatch(color -> color != 0)) {
            coreView.setAtomHighlightColors(colors, radii);
        } else {
            coreView.setAtomHighlightColors(null, null);
        }
        coreView.repaint();
    }

    private static CoreDisplay createCoreDisplay(StereoMolecule moleculeA, TransformationSplitter.MCSMap mcs) {
        BitSet coreAtoms = new BitSet(moleculeA.getAllAtoms());
        for (int atom = 0; atom < mcs.mapAtoB.length; atom++) {
            if (mcs.mapAtoB[atom] >= 0) {
                coreAtoms.set(atom);
            }
        }

        StereoMolecule core = new StereoMolecule();
        boolean[] include = OpenChemLibUtil.bitsetToBool(coreAtoms, moleculeA.getAllAtoms());
        int[] mapAtoCore = new int[moleculeA.getAllAtoms()];
        Arrays.fill(mapAtoCore, -1);
        moleculeA.copyMoleculeByAtoms(core, include, true, mapAtoCore);
        core.ensureHelperArrays(Molecule.cHelperRings);
        return new CoreDisplay(core, mapAtoCore);
    }

    private static AnnotatedCoreDisplay createAnnotatedCoreDisplay(PairAnalysis analysis) {
        StereoMolecule display = new StereoMolecule(analysis.core.molecule);
        int scaffoldAtomCount = display.getAtoms();
        Set<Integer> relevantCoreAttachmentAtoms = new LinkedHashSet<>();
        for (TransformationGroup group : analysis.groups) {
            for (int atomA : group.attachmentsA) {
                int coreAtom = analysis.core.mapAtoCore[atomA];
                if (coreAtom >= 0) {
                    relevantCoreAttachmentAtoms.add(coreAtom);
                }
            }
        }

        List<String> relevantLabels = new ArrayList<>();
        for (int coreAtom : relevantCoreAttachmentAtoms) {
            relevantLabels.add("atom " + (coreAtom + 1));
        }
        display.ensureHelperArrays(Molecule.cHelperRings);

        int[] colors = new int[display.getAllAtoms()];
        float[] radii = new float[display.getAllAtoms()];
        String[] atomText = new String[display.getAllAtoms()];
        for (int atom = 0; atom < display.getAllAtoms(); atom++) {
            if (atom < scaffoldAtomCount && relevantCoreAttachmentAtoms.contains(atom)) {
                display.setAtomColor(atom, Molecule.cAtomColorOrange);
                display.setAtomMarker(atom, true);
                colors[atom] = 0xffffc400;
                radii[atom] = 0.68f;
                atomText[atom] = "*";
            }
        }

        return new AnnotatedCoreDisplay(display, colors, radii, atomText, relevantLabels);
    }

    private static JTextArea compactTextArea(String text) {
        JTextArea textArea = new JTextArea(text);
        textArea.setEditable(false);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setOpaque(false);
        return textArea;
    }

    private static String mappingFailureText(OclStrictMcsProvider.MCSMappingResult result) {
        StringBuilder text = new StringBuilder(result.failure() == null ? "MCS mapping failed." : result.failure());
        if (result.mcsAtomCount() > 0 || result.mappingCountA() > 0 || result.mappingCountB() > 0) {
            text.append("\n\nMCS atoms: ").append(result.mcsAtomCount())
                    .append("\nMappings to A: ").append(result.mappingCountA())
                    .append("\nMappings to B: ").append(result.mappingCountB());
        }
        return text.toString();
    }

    private static String mappingSummary(PairAnalysis analysis) {
        String warning = analysis.mappingWarning == null || analysis.mappingWarning.isBlank()
                ? ""
                : "\nWarning: " + analysis.mappingWarning;
        return """
                MCS Mode: %s
                MCS Atoms: %d
                Mappings To A: %d
                Mappings To B: %d
                Selected Ring Bonds A/B: %d/%d%s
                """.formatted(
                analysis.mappingStrict ? "strict" : "non-strict",
                analysis.mcsAtomCount,
                analysis.mappingCountA,
                analysis.mappingCountB,
                analysis.selectedRingBondsA,
                analysis.selectedRingBondsB,
                warning
        );
    }

    private static void setViewFromIdcode(JStructureView view, String idcode) {
        StereoMolecule molecule = moleculeFromIdcode(idcode);
        if (molecule == null || molecule.getAtoms() == 0) {
            view.structureChanged();
            return;
        }
        view.structureChanged(molecule);
    }

    private static StereoMolecule moleculeFromIdcode(String idcode) {
        if (idcode == null || idcode.isBlank()) {
            return null;
        }
        try {
            StereoMolecule molecule = new StereoMolecule();
            new IDCodeParser().parse(molecule, idcode);
            molecule.ensureHelperArrays(Molecule.cHelperRings);
            return molecule;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String smilesFromIdcode(String idcode) {
        StereoMolecule molecule = moleculeFromIdcode(idcode);
        if (molecule == null || molecule.getAtoms() == 0) {
            return "[empty]";
        }
        try {
            return new IsomericSmilesCreator(molecule).getSmiles();
        } catch (RuntimeException ex) {
            return "[unavailable]";
        }
    }

    private static List<Integer> oneBased(List<Integer> zeroBasedAtoms) {
        return zeroBasedAtoms.stream().map(atom -> atom + 1).toList();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record CoreDisplay(StereoMolecule molecule, int[] mapAtoCore) {
    }

    private record AnnotatedCoreDisplay(
            StereoMolecule molecule,
            int[] colors,
            float[] radii,
            String[] atomText,
            List<String> relevantAttachmentLabels
    ) {
        boolean hasHighlights() {
            return Arrays.stream(colors).anyMatch(color -> color != 0);
        }
    }

    private record PairAnalysis(
            StereoMolecule moleculeA,
            StereoMolecule moleculeB,
            TransformationSplitter.MCSMap mcs,
            CoreDisplay core,
            List<TransformationGroup> groups,
            boolean mappingStrict,
            int mcsAtomCount,
            int mappingCountA,
            int mappingCountB,
            int selectedRingBondsA,
            int selectedRingBondsB,
            String mappingWarning,
            String failure
    ) {
        static PairAnalysis success(
                StereoMolecule moleculeA,
                StereoMolecule moleculeB,
                OclStrictMcsProvider.MCSMappingResult mappingResult,
                CoreDisplay core,
                List<TransformationGroup> groups
        ) {
            return new PairAnalysis(
                    moleculeA,
                    moleculeB,
                    mappingResult.mcsMap(),
                    core,
                    List.copyOf(groups),
                    mappingResult.strict(),
                    mappingResult.mcsAtomCount(),
                    mappingResult.mappingCountA(),
                    mappingResult.mappingCountB(),
                    mappingResult.selectedRingBondsA(),
                    mappingResult.selectedRingBondsB(),
                    mappingResult.warning(),
                    null
            );
        }

        static PairAnalysis failure(StereoMolecule moleculeA, StereoMolecule moleculeB, String failure) {
            return new PairAnalysis(moleculeA, moleculeB, null, null, List.of(), false, 0, 0, 0, 0, 0, null, failure);
        }
    }

    private static final class MoleculeEditorPanel extends JPanel {
        private final String title;
        private final String initialSmiles;
        private final JDrawPanel drawPanel;
        private final JTextField smilesField;
        private final JButton loadSmilesButton;
        private final JButton cleanButton;
        private final JButton clearButton;

        private MoleculeEditorPanel(String title, String initialSmiles) {
            super(new BorderLayout(6, 6));
            this.title = title;
            this.initialSmiles = initialSmiles;
            this.drawPanel = new JDrawPanel(new StereoMolecule());
            this.smilesField = new JTextField(initialSmiles, 34);
            this.loadSmilesButton = new JButton("Load SMILES");
            this.cleanButton = new JButton("Clean");
            this.clearButton = new JButton("Clear");

            setBorder(BorderFactory.createTitledBorder(title));
            add(buildControlPanel(), BorderLayout.NORTH);
            add(drawPanel, BorderLayout.CENTER);
            setMinimumSize(new Dimension(420, 260));
        }

        private JPanel buildControlPanel() {
            JPanel panel = new JPanel(new BorderLayout(6, 0));
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            buttons.add(loadSmilesButton);
            buttons.add(cleanButton);
            buttons.add(clearButton);
            panel.add(smilesField, BorderLayout.CENTER);
            panel.add(buttons, BorderLayout.EAST);

            loadSmilesButton.addActionListener(event -> loadSmilesFromField());
            cleanButton.addActionListener(event -> drawPanel.cleanStructure());
            clearButton.addActionListener(event -> setMolecule(new StereoMolecule()));
            return panel;
        }

        private void loadInitialSmiles() {
            smilesField.setText(initialSmiles);
            loadSmilesFromField();
        }

        private void loadSmilesFromField() {
            String smiles = smilesField.getText().trim();
            if (smiles.isEmpty()) {
                JOptionPane.showMessageDialog(this, "SMILES is empty.", title, JOptionPane.WARNING_MESSAGE);
                return;
            }
            try {
                StereoMolecule molecule = new StereoMolecule();
                new SmilesParser().parse(molecule, smiles);
                molecule.ensureHelperArrays(Molecule.cHelperRings);
                setMolecule(molecule);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(
                        this,
                        "Could not parse SMILES:\n" + ex.getMessage(),
                        title,
                        JOptionPane.WARNING_MESSAGE
                );
            }
        }

        private StereoMolecule currentMolecule() {
            StereoMolecule molecule = currentMoleculeOrNull();
            if (molecule == null || molecule.getAtoms() == 0) {
                throw new IllegalArgumentException(title + " is empty.");
            }
            molecule.ensureHelperArrays(Molecule.cHelperRings);
            return molecule;
        }

        private StereoMolecule currentMoleculeOrNull() {
            StereoMolecule molecule = drawPanel.getDrawArea().getMolecule();
            if (molecule == null) {
                return null;
            }
            StereoMolecule clone = new StereoMolecule(molecule);
            clone.ensureHelperArrays(Molecule.cHelperRings);
            return clone;
        }

        private void setMolecule(StereoMolecule molecule) {
            StereoMolecule clone = molecule == null ? new StereoMolecule() : new StereoMolecule(molecule);
            clone.ensureHelperArrays(Molecule.cHelperRings);
            drawPanel.getDrawArea().setMolecule(clone);
        }

        private void setControlsEnabled(boolean enabled) {
            smilesField.setEnabled(enabled);
            loadSmilesButton.setEnabled(enabled);
            cleanButton.setEnabled(enabled);
            clearButton.setEnabled(enabled);
            drawPanel.setEnabled(enabled);
            drawPanel.getDrawArea().setEnabled(enabled);
        }
    }

    private static final class GroupTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {
                "#", "Type", "A Attachments", "Reaction", "Signature", "Removed", "Added"
        };
        private List<TransformationGroup> groups = List.of();

        void setGroups(List<TransformationGroup> groups) {
            this.groups = List.copyOf(groups);
            fireTableDataChanged();
        }

        TransformationGroup getGroupAt(int rowIndex) {
            return groups.get(rowIndex);
        }

        @Override
        public int getRowCount() {
            return groups.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 0 ? Integer.class : String.class;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            TransformationGroup group = groups.get(rowIndex);
            TransformationSignature signature = group.signature;
            return switch (columnIndex) {
                case 0 -> rowIndex + 1;
                case 1 -> group.type.name();
                case 2 -> oneBased(group.attachmentsA).toString();
                case 3 -> signature.rxnClass;
                case 4 -> signature.sigId.substring(0, Math.min(12, signature.sigId.length()));
                case 5 -> smilesFromIdcode(signature.removedIdcode);
                case 6 -> smilesFromIdcode(signature.addedIdcode);
                default -> "";
            };
        }
    }

    private static final class RightAlignedRenderer extends DefaultTableCellRenderer {
        private RightAlignedRenderer() {
            setHorizontalAlignment(JLabel.RIGHT);
        }
    }
}
