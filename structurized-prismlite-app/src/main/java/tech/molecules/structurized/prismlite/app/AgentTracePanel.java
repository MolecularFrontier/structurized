package tech.molecules.structurized.prismlite.app;

import tech.molecules.chemflow.canvas.AgentActivityModel;
import tech.molecules.chemflow.canvas.CanvasActivityState;
import tech.molecules.chemflow.canvas.CanvasActivityTarget;
import tech.molecules.chemflow.canvas.ChemFlowCanvas;
import tech.molecules.chemflow.canvas.ElementActivityBinding;
import tech.molecules.chemflow.model.ElementId;
import tech.molecules.structurized.ai.mcp.AgentExplorationTraceReader;
import tech.molecules.structurized.ai.prism.ManagedPrismSession;
import tech.molecules.structurized.ai.prism.ManagedPrismSessionChangeType;
import tech.molecules.structurized.ai.prism.ManagedPrismSessionSubscription;
import tech.molecules.structurized.ai.trace.AgentElementKind;
import tech.molecules.structurized.ai.trace.AgentElementReference;
import tech.molecules.structurized.ai.trace.AgentExplorationEvent;
import tech.molecules.structurized.ai.trace.AgentExplorationPhase;
import tech.molecules.structurized.ai.trace.AgentExplorationSubscription;
import tech.molecules.structurized.ai.trace.AgentExplorationTrace;
import tech.molecules.structurized.ai.trace.RecordedAgentTrace;
import tech.molecules.structurized.prism.engine.PrismColumn;
import tech.molecules.structurized.prism.engine.PrismColumnType;
import tech.molecules.structurized.prism.engine.PrismMoleculeDocument;
import tech.molecules.structurized.prism.engine.PrismRowGraph;
import tech.molecules.structurized.prism.engine.PrismRowSet;
import tech.molecules.structurized.prism.engine.PrismSession;
import tech.molecules.structurized.prism.engine.PrismViewRecord;
import tech.molecules.structurized.prism.engine.RowGraphNeighborhoodViewSpec;
import tech.molecules.structurized.prismlite.swing.workspace.PrismLiteWorkspaceModel;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JToolBar;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Integrated recorded/live viewer for observable Structurized agent activity. */
final class AgentTracePanel extends JPanel implements AutoCloseable {
    private final ManagedPrismSession managed;
    private final PrismLiteWorkspaceModel workspaceModel;
    private final Runnable refreshWorkspace;
    private final AgentActivityModel activity = new AgentActivityModel();
    private final AgentExplorationTrace liveTrace;
    private final ArrayList<AgentExplorationEvent> events = new ArrayList<>();
    private final ArrayList<AgentExplorationEvent> liveEvents = new ArrayList<>();
    private final AgentExplorationSubscription liveSubscription;
    private final ManagedPrismSessionSubscription sessionSubscription;
    private final Timer timer;
    private final JComboBox<GraphItem> graphSelector = new JComboBox<>();
    private final JComboBox<String> speed = new JComboBox<>(new String[]{"0.5x", "1x", "2x", "5x", "10x"});
    private final JCheckBox presentationTiming = new JCheckBox("Presentation timing", true);
    private final JButton play = new JButton("Play");
    private final JButton goLive = new JButton("Go Live");
    private final JSlider timeline = new JSlider();
    private final JLabel timeLabel = new JLabel("0:00 / 0:00");
    private final JLabel status = new JLabel("Live trace ready");
    private final JList<String> log = new JList<>();
    private final JPanel proposals = new JPanel();
    private final JTabbedPane views = new JTabbedPane();
    private final JPanel riverHost = new JPanel(new BorderLayout());
    private final JPanel graphHost = new JPanel(new BorderLayout());
    private final List<ChemFlowCanvas> canvases = new ArrayList<>();

    private AgentTraceTimeline traceTimeline = new AgentTraceTimeline(List.of(), true);
    private double playheadMillis;
    private boolean playing;
    private boolean liveMode = true;
    private long previousTickNanos;
    private String graphCenterRowId;

    AgentTracePanel(ManagedPrismSession managed,
                    PrismLiteWorkspaceModel workspaceModel,
                    Runnable refreshWorkspace,
                    AgentExplorationTrace liveTrace,
                    Path initialReplay) {
        super(new BorderLayout());
        this.managed = Objects.requireNonNull(managed, "managed");
        this.workspaceModel = Objects.requireNonNull(workspaceModel, "workspaceModel");
        this.refreshWorkspace = Objects.requireNonNull(refreshWorkspace, "refreshWorkspace");
        this.liveTrace = Objects.requireNonNull(liveTrace, "liveTrace");
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        add(toolbar(), BorderLayout.NORTH);
        add(content(), BorderLayout.CENTER);
        add(footer(), BorderLayout.SOUTH);
        refreshGraphs();
        rebuildViews();
        timer = new Timer(33, this::tick);
        timer.setCoalesce(true);
        timer.start();
        liveSubscription = this.liveTrace.subscribe(event -> SwingUtilities.invokeLater(() -> acceptLive(event)));
        sessionSubscription = managed.subscribe(change -> {
            if (change.type() == ManagedPrismSessionChangeType.STRUCTURE) {
                SwingUtilities.invokeLater(() -> {
                    refreshGraphs();
                    rebuildViews();
                });
            }
        });
        if (initialReplay != null) SwingUtilities.invokeLater(() -> load(initialReplay));
    }

    private JComponent toolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        JButton open = new JButton("Open Trace / Bundle");
        open.addActionListener(event -> chooseTrace());
        JButton export = new JButton("Export Bundle");
        export.addActionListener(event -> chooseBundleDestination());
        JButton restart = new JButton("Restart");
        restart.addActionListener(event -> seek(0));
        JButton previous = new JButton("Previous");
        previous.addActionListener(event -> step(-1));
        JButton next = new JButton("Next");
        next.addActionListener(event -> step(1));
        play.addActionListener(event -> setPlaying(!playing));
        goLive.addActionListener(event -> showLiveTrace());
        speed.setSelectedItem("1x");
        presentationTiming.addActionListener(event -> rebuildTimelineAtCursor());
        graphSelector.addActionListener(event -> rebuildViews());
        bar.add(open);
        bar.add(export);
        bar.addSeparator();
        bar.add(restart);
        bar.add(previous);
        bar.add(play);
        bar.add(next);
        bar.add(goLive);
        bar.add(new JLabel("  Speed "));
        bar.add(speed);
        bar.add(presentationTiming);
        bar.add(Box.createHorizontalGlue());
        bar.add(new JLabel("Graph "));
        bar.add(graphSelector);
        return bar;
    }

    private JComponent content() {
        views.addTab("Project River", riverHost);
        views.addTab("MMP / Similarity Graph", graphHost);
        JPanel right = new JPanel(new BorderLayout(0, 8));
        right.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 4));
        JLabel title = new JLabel("AGENT EXPLORATION");
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD, 13f));
        right.add(title, BorderLayout.NORTH);
        log.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        log.setFixedCellHeight(52);
        right.add(new javax.swing.JScrollPane(log), BorderLayout.CENTER);
        proposals.setLayout(new BoxLayout(proposals, BoxLayout.Y_AXIS));
        proposals.setBorder(BorderFactory.createTitledBorder("Agent proposals"));
        right.add(proposals, BorderLayout.SOUTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, views, right);
        split.setResizeWeight(0.78);
        split.setDividerLocation(0.78);
        return split;
    }

    private JComponent footer() {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        timeline.setMinimum(0);
        timeline.setMaximum(1);
        timeline.addChangeListener(event -> {
            if (timeline.getValueIsAdjusting()) {
                setPlaying(false);
                seek(timeline.getValue());
            }
        });
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        left.add(status);
        panel.add(left, BorderLayout.WEST);
        panel.add(timeline, BorderLayout.CENTER);
        panel.add(timeLabel, BorderLayout.EAST);
        return panel;
    }

    private void acceptLive(AgentExplorationEvent event) {
        liveEvents.add(event);
        liveEvents.sort(java.util.Comparator.comparingLong(AgentExplorationEvent::sequence));
        if (!liveMode) {
            status.setText("Recorded replay | " + liveEvents.size() + " live events buffered");
            return;
        }
        boolean atEdge = playheadMillis >= Math.max(0, traceTimeline.durationMillis() - 100);
        events.clear();
        events.addAll(liveEvents);
        traceTimeline = new AgentTraceTimeline(events, presentationTiming.isSelected());
        configureSlider();
        if (atEdge) seek(traceTimeline.durationMillis());
        else status.setText("Live events buffered: " + Math.max(0, traceTimeline.eventCount() - currentCursor() - 1));
    }

    private void showLiveTrace() {
        setPlaying(false);
        liveMode = true;
        events.clear();
        events.addAll(liveEvents);
        traceTimeline = new AgentTraceTimeline(events, presentationTiming.isSelected());
        configureSlider();
        seek(traceTimeline.durationMillis());
        status.setText(liveEvents.isEmpty() ? "Live trace ready" : "Live edge");
    }

    private void chooseTrace() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Open Structurized agent trace");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) load(chooser.getSelectedFile().toPath());
    }

    private void load(Path path) {
        try {
            AgentTraceBundle bundle = AgentTraceBundleIO.read(path);
            RecordedAgentTrace recorded = bundle.trace();
            importBundleSidecars(bundle);
            events.clear();
            events.addAll(recorded.events());
            liveMode = false;
            traceTimeline = new AgentTraceTimeline(events, presentationTiming.isSelected());
            configureSlider();
            refreshGraphs();
            rebuildViews();
            seek(0);
            String fingerprintWarning = !bundle.datasetFingerprint().isBlank()
                    && !bundle.datasetFingerprint().equals(datasetFingerprint())
                    ? " | dataset fingerprint differs" : "";
            status.setText("Loaded " + path.getFileName() + " | " + events.size() + " events"
                    + (recorded.truncatedFinalLine() ? " | truncated final line" : "")
                    + (bundle.graphs().isEmpty() ? "" : " | " + bundle.graphs().size() + " graph snapshots")
                    + fingerprintWarning);
        } catch (Exception exception) {
            status.setText("Could not open trace: " + exception.getMessage());
        }
    }

    private void importBundleSidecars(AgentTraceBundle bundle) {
        Set<String> graphIds = managed.workspace().graphs().stream().map(PrismRowGraph::id).collect(java.util.stream.Collectors.toSet());
        for (PrismRowGraph graph : bundle.graphs()) {
            if (!graphIds.contains(graph.id())) managed.workspace().addGraph(graph);
        }
        for (PrismMoleculeDocument proposal : bundle.proposals()) {
            if (managed.moleculeWorkspace().findDocument(proposal.id()).isEmpty()) {
                managed.moleculeWorkspace().addDocument("scratchpad", proposal.id(), proposal.title(),
                        proposal.mode(), proposal.idcode(), proposal.coordinates());
            }
        }
    }

    private void chooseBundleDestination() {
        if (events.isEmpty()) {
            status.setText("Nothing to export yet.");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export presentation trace bundle");
        chooser.setSelectedFile(new java.io.File("agent-trace.agenttrace.zip"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            Path destination = chooser.getSelectedFile().toPath();
            AgentTraceBundleIO.write(destination, currentBundle());
            status.setText("Exported " + destination.getFileName() + " (dataset remains external)");
        } catch (Exception exception) {
            status.setText("Could not export bundle: " + exception.getMessage());
        }
    }

    private AgentTraceBundle currentBundle() {
        Instant startedAt = events.isEmpty() ? liveTrace.startedAt()
                : events.getFirst().occurredAt().minusMillis(events.getFirst().elapsedMillis());
        String traceId = events.isEmpty() ? liveTrace.traceId() : events.getFirst().traceId();
        RecordedAgentTrace recorded = new RecordedAgentTrace(AgentExplorationTrace.SCHEMA_VERSION, traceId, startedAt, events, false);
        GraphItem selected = (GraphItem) graphSelector.getSelectedItem();
        List<PrismRowGraph> graphs = selected == null ? List.of() : List.of(selected.graph());
        Set<String> proposalIds = events.stream().flatMap(event -> event.references().stream())
                .filter(reference -> reference.kind() == AgentElementKind.PRISM_MOLECULE_DOCUMENT
                        && reference.role() == tech.molecules.structurized.ai.trace.AgentAttentionRole.PROPOSED)
                .map(AgentElementReference::elementId).collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        List<PrismMoleculeDocument> proposalDocuments = proposalIds.stream()
                .map(id -> managed.moleculeWorkspace().findDocument(id).orElse(null))
                .filter(Objects::nonNull).toList();
        return new AgentTraceBundle(recorded, datasetFingerprint(), graphs, proposalDocuments);
    }

    private String datasetFingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (PrismColumn column : managed.workspace().table().columns()) digest.update(column.id().getBytes(StandardCharsets.UTF_8));
            for (int row = 0; row < managed.workspace().totalRowCount(); row++) {
                digest.update(managed.workspace().rowIdForPhysicalRow(row).getBytes(StandardCharsets.UTF_8));
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void rebuildTimelineAtCursor() {
        int cursor = currentCursor();
        traceTimeline = new AgentTraceTimeline(events, presentationTiming.isSelected());
        configureSlider();
        seek(cursor < 0 || traceTimeline.eventCount() == 0 ? 0 : traceTimeline.eventTime(cursor));
    }

    private void configureSlider() {
        timeline.setMaximum((int) Math.min(Integer.MAX_VALUE, Math.max(1, traceTimeline.durationMillis())));
    }

    private void setPlaying(boolean next) {
        playing = next && traceTimeline.eventCount() > 0;
        play.setText(playing ? "Pause" : "Play");
        if (playing) {
            if (playheadMillis >= traceTimeline.durationMillis()) seek(0);
            previousTickNanos = System.nanoTime();
        }
    }

    private void tick(ActionEvent ignored) {
        if (!playing) {
            if (!activity.isEmpty()) for (ChemFlowCanvas canvas : canvases) canvas.repaint();
            return;
        }
        long now = System.nanoTime();
        long delta = Math.max(0, now - previousTickNanos);
        previousTickNanos = now;
        playheadMillis += delta / 1_000_000.0 * speedFactor();
        if (playheadMillis >= traceTimeline.durationMillis()) {
            playheadMillis = traceTimeline.durationMillis();
            setPlaying(false);
        }
        updateSnapshot();
    }

    private double speedFactor() {
        String value = String.valueOf(speed.getSelectedItem()).replace("x", "");
        try { return Double.parseDouble(value); } catch (RuntimeException ignored) { return 1.0; }
    }

    private void step(int direction) {
        if (traceTimeline.eventCount() == 0) return;
        setPlaying(false);
        int cursor = currentCursor();
        int next = Math.max(0, Math.min(traceTimeline.eventCount() - 1, cursor + direction));
        seek(traceTimeline.eventTime(next));
    }

    private int currentCursor() {
        return traceTimeline.snapshot((long) playheadMillis, ignored -> managed.sessionId()).cursor();
    }

    private void seek(long millis) {
        playheadMillis = Math.max(0, Math.min(millis, traceTimeline.durationMillis()));
        updateSnapshot();
    }

    private void updateSnapshot() {
        AgentTraceTimeline.Snapshot snapshot = traceTimeline.snapshot((long) playheadMillis, ignored -> managed.sessionId());
        activity.replaceAll(normalizeSubjects(snapshot.activities()));
        timeline.setValue((int) Math.min(Integer.MAX_VALUE, playheadMillis));
        timeLabel.setText(formatTime((long) playheadMillis) + " / " + formatTime(traceTimeline.durationMillis()));
        updateLog(snapshot.log());
        updateProposals(snapshot.activities().keySet());
        for (ChemFlowCanvas canvas : canvases) canvas.repaint();
        if (snapshot.focusedRowId() != null && !snapshot.focusedRowId().equals(graphCenterRowId)) {
            GraphItem selected = (GraphItem) graphSelector.getSelectedItem();
            if (selected != null && selected.graph().rowIds().contains(snapshot.focusedRowId())) {
                graphCenterRowId = snapshot.focusedRowId();
                rebuildGraphView();
            }
        }
    }

    private Map<CanvasActivityTarget, CanvasActivityState> normalizeSubjects(Map<CanvasActivityTarget, CanvasActivityState> source) {
        LinkedHashMap<CanvasActivityTarget, CanvasActivityState> normalized = new LinkedHashMap<>(source);
        source.forEach((target, state) -> {
            if ("prism_subject".equals(target.namespace())
                    && managed.workspace().physicalRowForRowId(target.elementId()).isPresent()) {
                normalized.put(new CanvasActivityTarget("prism_row", managed.sessionId(), target.elementId()), state);
            }
        });
        return normalized;
    }

    private void updateLog(List<AgentTraceTimeline.InvocationView> entries) {
        String[] rows = entries.stream().map(entry -> {
            String icon = switch (entry.phase()) {
                case STARTED -> "▶";
                case COMPLETED -> "✓";
                case FAILED -> "✕";
            };
            List<String> ids = entry.references().stream().map(AgentElementReference::elementId).distinct().limit(3).toList();
            String detail = ids.isEmpty() ? entry.toolName() : String.join(", ", ids);
            if (entry.errorCode() != null) detail = entry.errorCode();
            return "<html><b>" + icon + " " + escape(entry.label()) + "</b><br><font color='#667085'>"
                    + escape(detail) + "</font></html>";
        }).toArray(String[]::new);
        log.setListData(rows);
    }

    private void updateProposals(Set<CanvasActivityTarget> targets) {
        proposals.removeAll();
        List<CanvasActivityTarget> proposed = targets.stream()
                .filter(target -> "prism_molecule_document".equals(target.namespace()))
                .limit(6).toList();
        if (proposed.isEmpty()) proposals.add(new JLabel("No proposed molecules yet"));
        for (CanvasActivityTarget target : proposed) {
            String title = managed.moleculeWorkspace().findDocument(target.elementId())
                    .map(document -> document.title() + "  [" + document.id() + "]")
                    .orElse(target.elementId() + "  [unresolved]");
            JLabel label = new JLabel("◆ " + title);
            label.setForeground(new java.awt.Color(153, 51, 170));
            proposals.add(label);
        }
        proposals.revalidate();
        proposals.repaint();
    }

    private void refreshGraphs() {
        String selectedId = Optional.ofNullable((GraphItem) graphSelector.getSelectedItem()).map(item -> item.graph().id()).orElse(null);
        graphSelector.removeAllItems();
        managed.workspace().graphs().stream()
                .sorted(java.util.Comparator.comparingInt(AgentTracePanel::graphRank).thenComparing(PrismRowGraph::title))
                .map(GraphItem::new).forEach(graphSelector::addItem);
        if (selectedId != null) {
            for (int index = 0; index < graphSelector.getItemCount(); index++) {
                if (graphSelector.getItemAt(index).graph().id().equals(selectedId)) graphSelector.setSelectedIndex(index);
            }
        }
    }

    private static int graphRank(PrismRowGraph graph) {
        return switch (graph.graphType()) {
            case "chemistry.mmp" -> 0;
            case "chemistry.similarity" -> 1;
            default -> 2;
        };
    }

    private void rebuildViews() {
        rebuildRiverView();
        rebuildGraphView();
    }

    private void rebuildRiverView() {
        GraphItem selected = (GraphItem) graphSelector.getSelectedItem();
        if (selected == null) {
            showMessage(riverHost, "Mine or load an MMP/similarity graph to display the project universe.");
            return;
        }
        PrismSession session = managed.workspace();
        PrismColumn structure = structureColumn(selected.graph()).orElse(null);
        PrismRowSet rowSet = sourceRowSet(selected.graph()).orElse(null);
        if (structure == null || rowSet == null) {
            showMessage(riverHost, "The selected graph has no usable structure column or row set.");
            return;
        }
        String viewId = "agent-trace-river";
        ChemFlowProjectRiverViewSpec spec = new ChemFlowProjectRiverViewSpec(
                viewId, "Agent Project River", selected.graph().id(), rowSet.id(), structure.id(), dateColumnId(), List.of());
        JComponent component = new ChemFlowProjectRiverViewRenderer().createComponent(
                PrismViewRecord.of(spec), workspaceModel, null, refreshWorkspace);
        installActivity(component);
        replace(riverHost, component);
    }

    private void rebuildGraphView() {
        GraphItem selected = (GraphItem) graphSelector.getSelectedItem();
        if (selected == null || selected.graph().rowIds().isEmpty()) {
            showMessage(graphHost, "Select an MMP or similarity graph.");
            return;
        }
        PrismColumn structure = structureColumn(selected.graph()).orElse(null);
        if (structure == null) {
            showMessage(graphHost, "The selected graph has no molecule column.");
            return;
        }
        if (graphCenterRowId == null || !selected.graph().rowIds().contains(graphCenterRowId)) {
            graphCenterRowId = selected.graph().rowIds().iterator().next();
        }
        RowGraphNeighborhoodViewSpec spec = new RowGraphNeighborhoodViewSpec(
                "agent-trace-neighborhood", selected.graph().title() + " around " + graphCenterRowId,
                selected.graph().id(), graphCenterRowId, structure.id(), List.of(), 18, false);
        JComponent component = new ChemFlowGraphNeighborhoodViewRenderer().createComponent(
                PrismViewRecord.of(spec), workspaceModel, null, refreshWorkspace);
        installActivity(component);
        replace(graphHost, component);
    }

    private Optional<PrismColumn> structureColumn(PrismRowGraph graph) {
        Object configured = graph.metadata().get("structureColumnId");
        if (configured != null) {
            Optional<PrismColumn> found = managed.workspace().table().findColumn(String.valueOf(configured));
            if (found.isPresent() && found.get().type() == PrismColumnType.MOLECULE) return found;
        }
        return managed.workspace().table().columns().stream().filter(column -> column.type() == PrismColumnType.MOLECULE).findFirst();
    }

    private Optional<PrismRowSet> sourceRowSet(PrismRowGraph graph) {
        if (graph.sourceRowSetId() != null) {
            try { return Optional.of(managed.workspace().rowSet(graph.sourceRowSetId())); }
            catch (RuntimeException ignored) { }
        }
        try { return Optional.of(managed.workspace().rowSet("all")); }
        catch (RuntimeException ignored) { return managed.workspace().rowSets().stream().findFirst(); }
    }

    private String dateColumnId() {
        return managed.workspace().table().columns().stream()
                .map(PrismColumn::id)
                .filter(id -> id.toLowerCase(Locale.ROOT).contains("date"))
                .findFirst().orElse(null);
    }

    private void installActivity(Component component) {
        for (ChemFlowCanvas canvas : findCanvases(component)) {
            canvas.setAgentActivity(activity);
            LinkedHashMap<ElementId, CanvasActivityTarget> bindings = new LinkedHashMap<>();
            for (int row = 0; row < managed.workspace().totalRowCount(); row++) {
                String rowId = managed.workspace().rowIdForPhysicalRow(row);
                CanvasActivityTarget target = new CanvasActivityTarget("prism_row", managed.sessionId(), rowId);
                bindings.put(new ElementId("row:" + rowId), target);
                bindings.put(new ElementId("river-row:" + safeId(rowId)), target);
            }
            canvas.setActivityBinding(ElementActivityBinding.from(bindings));
            canvases.add(canvas);
        }
    }

    private static List<ChemFlowCanvas> findCanvases(Component root) {
        ArrayList<ChemFlowCanvas> found = new ArrayList<>();
        if (root instanceof ChemFlowCanvas canvas) found.add(canvas);
        if (root instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) found.addAll(findCanvases(child));
        }
        return found;
    }

    private void replace(JPanel host, JComponent component) {
        canvases.removeIf(canvas -> SwingUtilities.isDescendingFrom(canvas, host));
        host.removeAll();
        host.add(component, BorderLayout.CENTER);
        host.revalidate();
        host.repaint();
    }

    private static void showMessage(JPanel host, String message) {
        host.removeAll();
        JLabel label = new JLabel(message, JLabel.CENTER);
        label.setPreferredSize(new Dimension(700, 500));
        host.add(label, BorderLayout.CENTER);
        host.revalidate();
        host.repaint();
    }

    private static String safeId(String value) {
        String safe = String.valueOf(value).trim().replaceAll("[^A-Za-z0-9_.:-]+", "_");
        return safe.isBlank() ? "item" : safe;
    }

    private static String formatTime(long millis) {
        long seconds = Math.max(0, millis) / 1_000;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private static String escape(String text) {
        return String.valueOf(text).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override
    public void close() {
        timer.stop();
        liveSubscription.close();
        sessionSubscription.close();
    }

    private record GraphItem(PrismRowGraph graph) {
        @Override public String toString() { return graph.title() + " [" + graph.graphType() + "]"; }
    }
}
