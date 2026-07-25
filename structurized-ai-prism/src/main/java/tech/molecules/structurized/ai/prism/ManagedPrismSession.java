package tech.molecules.structurized.ai.prism;

import tech.molecules.structurized.prism.engine.PrismSession;
import tech.molecules.structurized.prism.engine.PrismSessionChange;
import tech.molecules.structurized.prism.engine.PrismMoleculeWorkspace;
import tech.molecules.structurized.prism.provider.inmemory.InMemoryPrismDataset;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class ManagedPrismSession {
    private final String sessionId;
    private final String label;
    private final Path sourcePath;
    private final InMemoryPrismDataset dataContext;
    private final PrismSession workspace;
    private final PrismMoleculeWorkspace moleculeWorkspace;
    private final Instant openedAt;
    private final ManagedPrismSessionExecutor executor;
    private volatile long revision;
    private final CopyOnWriteArrayList<Consumer<ManagedPrismSessionChange>> listeners = new CopyOnWriteArrayList<>();
    private final ThreadLocal<MutationScope> mutationScope = new ThreadLocal<>();

    public ManagedPrismSession(String sessionId,
                               String label,
                               Path sourcePath,
                               InMemoryPrismDataset dataContext,
                               PrismSession workspace,
                               Instant openedAt) {
        this(sessionId, label, sourcePath, dataContext, workspace, openedAt, ManagedPrismSessionExecutor.direct());
    }

    public ManagedPrismSession(String sessionId,
                               String label,
                               Path sourcePath,
                               InMemoryPrismDataset dataContext,
                               PrismSession workspace,
                               Instant openedAt,
                               ManagedPrismSessionExecutor executor) {
        this.sessionId = requireText(sessionId, "sessionId");
        this.label = label == null || label.isBlank() ? this.sessionId : label.trim();
        this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath");
        this.dataContext = dataContext;
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.moleculeWorkspace = new PrismMoleculeWorkspace();
        this.openedAt = openedAt == null ? Instant.now() : openedAt;
        this.executor = Objects.requireNonNull(executor, "executor");
        this.revision = 1L;
        this.workspace.subscribe(this::workspaceChanged);
        this.moleculeWorkspace.subscribe(change -> recordChange(ManagedPrismSessionChangeType.MOLECULES));
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

    public Optional<InMemoryPrismDataset> dataContext() {
        return Optional.ofNullable(dataContext);
    }

    public InMemoryPrismDataset requireDataContext() {
        if (dataContext == null) {
            throw new tech.molecules.structurized.ai.model.ChemOperationException(
                    "prism_data_context_unavailable",
                    "This Prism session was opened from a PrismPack and does not have canonical PRISM TSV endpoint records."
            );
        }
        return dataContext;
    }

    public PrismSession workspace() {
        return workspace;
    }

    public PrismMoleculeWorkspace moleculeWorkspace() {
        return moleculeWorkspace;
    }


    public Instant openedAt() {
        return openedAt;
    }

    public long revision() {
        return revision;
    }

    public ManagedPrismSessionSubscription subscribe(Consumer<ManagedPrismSessionChange> listener) {
        Consumer<ManagedPrismSessionChange> registered = Objects.requireNonNull(listener, "listener");
        listeners.add(registered);
        return () -> listeners.remove(registered);
    }

    public void runAs(ManagedPrismSessionChangeOrigin origin, Runnable action) {
        callAs(origin, () -> {
            action.run();
            return null;
        });
    }

    public <T> T callAs(ManagedPrismSessionChangeOrigin origin, Supplier<T> action) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(action, "action");
        return executor.execute(() -> callDirect(origin, action));
    }

    private <T> T callDirect(ManagedPrismSessionChangeOrigin origin, Supplier<T> action) {
        MutationScope previous = mutationScope.get();
        if (previous != null) {
            return action.get();
        }
        MutationScope scope = new MutationScope();
        mutationScope.set(scope);
        try {
            T result = action.get();
            if (scope.type != null) {
                publish(scope.type, origin);
            }
            return result;
        } finally {
            if (previous == null) mutationScope.remove();
            else mutationScope.set(previous);
        }
    }


    private void workspaceChanged(PrismSessionChange change) {
        ManagedPrismSessionChangeType type = switch (change.type()) {
            case PROJECTION -> ManagedPrismSessionChangeType.PROJECTION;
            case STRUCTURE -> ManagedPrismSessionChangeType.STRUCTURE;
            case VIEWS -> ManagedPrismSessionChangeType.VIEWS;
        };
        recordChange(type);
    }

    private void recordChange(ManagedPrismSessionChangeType type) {
        MutationScope scope = mutationScope.get();
        if (scope != null) {
            scope.type = ManagedPrismSessionChangeType.merge(scope.type, type);
            return;
        }
        publish(type, ManagedPrismSessionChangeOrigin.LOCAL_UI);
    }

    private synchronized long publish(ManagedPrismSessionChangeType type, ManagedPrismSessionChangeOrigin origin) {
        long nextRevision = ++revision;
        ManagedPrismSessionChange change = new ManagedPrismSessionChange(this, nextRevision, type, origin);
        for (Consumer<ManagedPrismSessionChange> listener : listeners) {
            try {
                listener.accept(change);
            } catch (RuntimeException ignored) {
                // Session observers cannot roll back an already committed workspace change.
            }
        }
        return nextRevision;
    }

    private static final class MutationScope {
        private ManagedPrismSessionChangeType type;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
