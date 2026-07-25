package tech.molecules.structurized.ai.prism;

import tech.molecules.structurized.ai.model.ChemOperationException;
import tech.molecules.structurized.prism.engine.PrismSession;
import tech.molecules.structurized.prism.provider.inmemory.InMemoryPrismDataset;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class InMemoryPrismSessionRegistry implements PrismSessionRegistry {
    private final ManagedPrismSessionExecutor executor;
    private final Map<String, ManagedPrismSession> sessions = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<Consumer<ManagedPrismSessionChange>> listeners = new CopyOnWriteArrayList<>();

    public InMemoryPrismSessionRegistry() {
        this(ManagedPrismSessionExecutor.direct());
    }

    public InMemoryPrismSessionRegistry(ManagedPrismSessionExecutor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public synchronized ManagedPrismSession register(String sessionId,
                                                     String label,
                                                     Path sourcePath,
                                                     InMemoryPrismDataset dataContext,
                                                     PrismSession workspace) {
        Objects.requireNonNull(workspace, "workspace");
        Optional<ManagedPrismSession> existingWorkspace = findByWorkspace(workspace);
        if (existingWorkspace.isPresent()) {
            return existingWorkspace.get();
        }
        if (sessions.containsKey(sessionId)) {
            throw new ChemOperationException("duplicate_prism_session_id", "Prism session " + sessionId + " already exists.");
        }
        ManagedPrismSession managed = new ManagedPrismSession(
                sessionId, label, sourcePath, dataContext, workspace, Instant.now(), executor);
        managed.subscribe(this::publish);
        sessions.put(sessionId, managed);
        return managed;
    }

    @Override
    public synchronized Optional<ManagedPrismSession> find(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public synchronized Optional<ManagedPrismSession> findByWorkspace(PrismSession workspace) {
        return sessions.values().stream().filter(session -> session.workspace() == workspace).findFirst();
    }

    @Override
    public synchronized ManagedPrismSession require(String sessionId) {
        ManagedPrismSession session = sessions.get(sessionId);
        if (session == null) {
            throw new ChemOperationException("prism_session_not_found", "Prism session " + sessionId + " does not exist.");
        }
        return session;
    }

    @Override
    public synchronized List<ManagedPrismSession> sessions() {
        return List.copyOf(new ArrayList<>(sessions.values()));
    }

    @Override
    public ManagedPrismSessionSubscription subscribe(Consumer<ManagedPrismSessionChange> listener) {
        Consumer<ManagedPrismSessionChange> registered = Objects.requireNonNull(listener, "listener");
        listeners.add(registered);
        return () -> listeners.remove(registered);
    }

    private void publish(ManagedPrismSessionChange change) {
        for (Consumer<ManagedPrismSessionChange> listener : listeners) {
            try {
                listener.accept(change);
            } catch (RuntimeException ignored) {
                // Session observers cannot roll back an already committed workspace change.
            }
        }
    }
}
