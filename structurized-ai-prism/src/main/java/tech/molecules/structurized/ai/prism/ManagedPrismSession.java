package tech.molecules.structurized.ai.prism;

import tech.molecules.structurized.prism.engine.PrismMoleculeWorkspace;
import tech.molecules.structurized.prism.engine.PrismSession;
import tech.molecules.structurized.prism.engine.PrismWorkspace;
import tech.molecules.structurized.prism.engine.PrismWorkspaceChange;
import tech.molecules.structurized.prism.engine.PrismWorkspaceChangeOrigin;
import tech.molecules.structurized.prism.engine.PrismWorkspaceChangeType;
import tech.molecules.structurized.prism.engine.PrismWorkspaceExecutor;
import tech.molecules.structurized.prism.engine.live.PrismLiveContext;
import tech.molecules.structurized.prism.engine.ocl.OclLiveEvaluationSupport;
import tech.molecules.structurized.prism.provider.inmemory.InMemoryPrismDataset;
import tech.molecules.structurized.prism.engine.snapshot.PrismSnapshotDataset;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class ManagedPrismSession {
    private final String sessionId;
    private final String label;
    private final Path sourcePath;
    private final PrismSnapshotDataset snapshot;
    private final Supplier<PrismSnapshotDataset> snapshotReloader;
    private final PrismWorkspace prismWorkspace;
    private final Instant openedAt;

    public ManagedPrismSession(String sessionId,
                               String label,
                               Path sourcePath,
                               PrismSnapshotDataset snapshot,
                               Supplier<PrismSnapshotDataset> snapshotReloader,
                               PrismSession workspace,
                               Instant openedAt) {
        this(sessionId, label, sourcePath, snapshot, snapshotReloader, workspace, openedAt, ManagedPrismSessionExecutor.direct());
    }

    public ManagedPrismSession(String sessionId,
                               String label,
                               Path sourcePath,
                               PrismSnapshotDataset snapshot,
                               Supplier<PrismSnapshotDataset> snapshotReloader,
                               PrismSession workspace,
                               Instant openedAt,
                               ManagedPrismSessionExecutor executor) {
        this.sessionId = requireText(sessionId, "sessionId");
        this.label = label == null || label.isBlank() ? this.sessionId : label.trim();
        this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.snapshotReloader = snapshotReloader;
        this.openedAt = openedAt == null ? Instant.now() : openedAt;
        ManagedPrismSessionExecutor managedExecutor = Objects.requireNonNull(executor, "executor");
        PrismWorkspaceExecutor workspaceExecutor = new PrismWorkspaceExecutor() {
            @Override
            public <T> T execute(Supplier<T> action) {
                return managedExecutor.execute(action);
            }
        };
        this.prismWorkspace = new PrismWorkspace(
                this.sessionId, Objects.requireNonNull(workspace, "workspace"), new PrismMoleculeWorkspace(),
                workspaceExecutor, ManagedPrismLiveRuntime.environment(), OclLiveEvaluationSupport::registerDefaults);
    }

    public String sessionId() {
        return sessionId;
    }

    public String label() {
        return label;
    }

    public Path sourcePath() {
        return sourcePath;
    }

    public PrismSnapshotDataset snapshot() {
        return snapshot;
    }

    public Optional<Supplier<PrismSnapshotDataset>> snapshotReloader() { return Optional.ofNullable(snapshotReloader); }

    /** Deprecated compatibility path; MCP analysis uses {@link #snapshot()}. */
    public Optional<InMemoryPrismDataset> dataContext() {
        return snapshot instanceof CanonicalPrismSnapshotDataset canonical
                ? Optional.of(canonical.sourceDataset()) : Optional.empty();
    }

    public InMemoryPrismDataset requireDataContext() {
        return dataContext().orElseThrow(() -> new tech.molecules.structurized.ai.model.ChemOperationException(
                    "prism_data_context_unavailable",
                    "This deprecated operation requires canonical TSV source records; use snapshot row and endpoint-result APIs instead."
            ));
    }

    public PrismSession workspace() {
        return prismWorkspace.session();
    }

    public PrismMoleculeWorkspace moleculeWorkspace() {
        return prismWorkspace.molecules();
    }

    public PrismWorkspace prismWorkspace() {
        return prismWorkspace;
    }

    public PrismLiveContext liveContext() {
        return prismWorkspace.liveContext();
    }

    public Instant openedAt() {
        return openedAt;
    }

    public long revision() {
        return prismWorkspace.revision();
    }

    public ManagedPrismSessionSubscription subscribe(Consumer<ManagedPrismSessionChange> listener) {
        Consumer<ManagedPrismSessionChange> registered = Objects.requireNonNull(listener, "listener");
        var subscription = prismWorkspace.subscribe(change -> registered.accept(managedChange(change)));
        return subscription::close;
    }

    public void runAs(ManagedPrismSessionChangeOrigin origin, Runnable action) {
        callAs(origin, () -> {
            action.run();
            return null;
        });
    }

    public void runAs(ManagedPrismSessionChangeOrigin origin, Long expectedRevision, Runnable action) {
        prismWorkspace.runAs(workspaceOrigin(origin), expectedRevision, action);
    }

    public <T> T callAs(ManagedPrismSessionChangeOrigin origin, Supplier<T> action) {
        return prismWorkspace.callAs(workspaceOrigin(origin), action);
    }

    public <T> T callAs(ManagedPrismSessionChangeOrigin origin, Long expectedRevision, Supplier<T> action) {
        return prismWorkspace.callAs(workspaceOrigin(origin), expectedRevision, action);
    }

    private ManagedPrismSessionChange managedChange(PrismWorkspaceChange change) {
        return new ManagedPrismSessionChange(this, change.revision(), managedType(change.type()), managedOrigin(change.origin()));
    }

    private static ManagedPrismSessionChangeType managedType(PrismWorkspaceChangeType type) {
        return switch (type) {
            case PROJECTION -> ManagedPrismSessionChangeType.PROJECTION;
            case STRUCTURE -> ManagedPrismSessionChangeType.STRUCTURE;
            case VIEWS -> ManagedPrismSessionChangeType.VIEWS;
            case MOLECULES -> ManagedPrismSessionChangeType.MOLECULES;
            case LIVE_CONFIGURATION -> ManagedPrismSessionChangeType.LIVE_CONFIGURATION;
        };
    }

    private static PrismWorkspaceChangeOrigin workspaceOrigin(ManagedPrismSessionChangeOrigin origin) {
        Objects.requireNonNull(origin, "origin");
        return switch (origin) {
            case LOCAL_UI -> PrismWorkspaceChangeOrigin.LOCAL_UI;
            case MCP -> PrismWorkspaceChangeOrigin.AGENT;
            case SYSTEM -> PrismWorkspaceChangeOrigin.SYSTEM;
        };
    }

    private static ManagedPrismSessionChangeOrigin managedOrigin(PrismWorkspaceChangeOrigin origin) {
        return switch (origin) {
            case LOCAL_UI -> ManagedPrismSessionChangeOrigin.LOCAL_UI;
            case AGENT -> ManagedPrismSessionChangeOrigin.MCP;
            case BACKGROUND, SYSTEM -> ManagedPrismSessionChangeOrigin.SYSTEM;
        };
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
