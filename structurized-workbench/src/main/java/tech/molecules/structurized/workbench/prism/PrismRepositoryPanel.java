package tech.molecules.structurized.workbench.prism;

import tech.molecules.structurized.prism.io.PrismTsvDatasetLoader;
import tech.molecules.structurized.prism.model.EndpointDataType;
import tech.molecules.structurized.prism.model.EndpointDefinition;
import tech.molecules.structurized.prism.provider.inmemory.InMemoryPrismDataset;
import tech.molecules.structurized.workbench.model.NumericEndpointAnalysis;
import tech.molecules.structurized.workbench.model.PrismStructureProvider;
import tech.molecules.structurized.workbench.model.PrismWorkbenchModel;
import tech.molecules.structurized.workbench.model.PrismWorkbenchRepositorySnapshot;

import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Top-level reusable panel for loading and exploring a PRISM TSV repository.
 */
public final class PrismRepositoryPanel extends JPanel {
    private final EndpointBrowserPanel endpointBrowserPanel = new EndpointBrowserPanel();
    private final SubjectSetBrowserPanel subjectSetBrowserPanel = new SubjectSetBrowserPanel();
    private final SubjectTablePanel subjectTablePanel = new SubjectTablePanel();
    private final NumericEndpointDashboardPanel numericDashboardPanel = new NumericEndpointDashboardPanel();
    private final MmpWorkbenchPanel mmpWorkbenchPanel = new MmpWorkbenchPanel();
    private final JLabel statusLabel = new JLabel("No repository loaded");
    private PrismWorkbenchModel model;
    private tech.molecules.structurized.analytics.mmp.StructureProvider structureProvider;

    public PrismRepositoryPanel() {
        super(new BorderLayout(8, 8));
        add(buildToolbar(), BorderLayout.NORTH);

        JTabbedPane leftTabs = new JTabbedPane();
        leftTabs.addTab("Endpoints", endpointBrowserPanel);
        leftTabs.addTab("Subject Sets", subjectSetBrowserPanel);

        JTabbedPane rightTabs = new JTabbedPane();
        rightTabs.addTab("Subjects", subjectTablePanel);
        rightTabs.addTab("Numeric Endpoint", numericDashboardPanel);
        rightTabs.addTab("MMP Analytics", mmpWorkbenchPanel);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftTabs, rightTabs);
        splitPane.setResizeWeight(0.42);
        add(splitPane, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

        endpointBrowserPanel.setEndpointSelectionListener(this::selectEndpoint);
        subjectSetBrowserPanel.setSubjectSetSelectionListener(this::selectSubjectSet);
    }

    public void loadRepository(Path directory) throws IOException {
        InMemoryPrismDataset dataset = PrismTsvDatasetLoader.load(directory);
        loadRepositorySnapshot(new PrismWorkbenchRepositorySnapshot(
                directory.toString(),
                dataset,
                PrismStructureProvider.from(dataset)
        ));
    }

    public void loadRepositorySnapshot(PrismWorkbenchRepositorySnapshot snapshot) {
        model = PrismWorkbenchModel.of(snapshot);
        structureProvider = snapshot.structureProvider();
        refreshPanels();
        statusLabel.setText("Loaded " + snapshot.displayName()
                + " | endpoints=" + snapshot.dataset().getEndpointDefinitions().size()
                + " subjects=" + snapshot.dataset().getSubjectRecords().size()
                + " structures=" + structureProvider.fetchStructures(model.selectedSubjectIds()).size());
    }

    public PrismWorkbenchModel getModel() {
        return model;
    }

    private JPanel buildToolbar() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton openButton = new JButton("Open PRISM TSV Folder");
        openButton.addActionListener(event -> chooseAndLoadRepository());
        toolbar.add(openButton);
        return toolbar;
    }

    private void chooseAndLoadRepository() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setFileFilter(new FileNameExtensionFilter("PRISM TSV folder", "tsv"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path directory = chooser.getSelectedFile().toPath();
        statusLabel.setText("Loading " + directory + " ...");
        new SwingWorker<InMemoryPrismDataset, Void>() {
            private Exception failure;

            @Override
            protected InMemoryPrismDataset doInBackground() {
                try {
                    return PrismTsvDatasetLoader.load(directory);
                } catch (Exception e) {
                    failure = e;
                    return null;
                }
            }

            @Override
            protected void done() {
                if (failure != null) {
                    statusLabel.setText("Load failed: " + failure.getMessage());
                    return;
                }
                try {
                    loadRepositorySnapshot(new PrismWorkbenchRepositorySnapshot(
                            directory.toString(),
                            get(),
                            PrismStructureProvider.from(get())
                    ));
                } catch (Exception e) {
                    statusLabel.setText("Load failed: " + e.getMessage());
                }
            }
        }.execute();
    }

    private void selectEndpoint(String endpointId) {
        if (model == null) {
            return;
        }
        model = model.withSelectedEndpoint(endpointId);
        updateNumericDashboard();
    }

    private void selectSubjectSet(String subjectSetId) {
        if (model == null) {
            return;
        }
        model = model.withSelectedSubjectSet(subjectSetId);
        subjectTablePanel.setModel(model, structureProvider);
        updateNumericDashboard();
    }

    private void refreshPanels() {
        endpointBrowserPanel.setModel(model);
        subjectSetBrowserPanel.setModel(model);
        subjectTablePanel.setModel(model, structureProvider);
        mmpWorkbenchPanel.setModel(model, structureProvider);
        updateNumericDashboard();
    }

    private void updateNumericDashboard() {
        if (model == null || model.selectedEndpoint().isEmpty()) {
            numericDashboardPanel.setAnalysis(null);
            return;
        }
        EndpointDefinition endpoint = model.selectedEndpoint().orElseThrow();
        if (!isNumeric(endpoint)) {
            numericDashboardPanel.setAnalysis(null);
            return;
        }
        List<String> subjectIds = model.selectedSubjectIds();
        numericDashboardPanel.setAnalysis(NumericEndpointAnalysis.analyze(
                model.dataset(),
                endpoint,
                model.selectedSubjectSetId(),
                subjectIds
        ));
    }

    private static boolean isNumeric(EndpointDefinition endpoint) {
        return endpoint.getDatatype() == EndpointDataType.NUMERIC
                || endpoint.getDatatype() == EndpointDataType.OPTIONAL_NUMERIC;
    }
}
