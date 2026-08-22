package tech.molecules.structurized.ai.prism;

import tech.molecules.structurized.prism.engine.PrismSession;
import tech.molecules.structurized.prism.provider.inmemory.InMemoryPrismDataset;
import tech.molecules.structurized.prism.engine.snapshot.PrismSnapshotDataset;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

public interface PrismSessionRegistry {
    ManagedPrismSession register(String sessionId,
                                 String label,
                                 Path sourcePath,
                                 PrismSnapshotDataset snapshot,
                                 Supplier<PrismSnapshotDataset> snapshotReloader,
                                 PrismSession workspace);

    default ManagedPrismSession register(String sessionId, String label, Path sourcePath,
                                         InMemoryPrismDataset dataContext, PrismSession workspace) {
        PrismSnapshotDataset snapshot = dataContext == null ? new WorkspacePrismSnapshotDataset(workspace)
                : PrismSessionImporter.toSnapshot(dataContext, sourcePath, true);
        return register(sessionId, label, sourcePath, snapshot, null, workspace);
    }

    ManagedPrismSession replace(String sessionId,
                                String label,
                                Path sourcePath,
                                PrismSnapshotDataset snapshot,
                                Supplier<PrismSnapshotDataset> snapshotReloader,
                                PrismSession workspace);

    default ManagedPrismSession replace(String sessionId, String label, Path sourcePath,
                                        InMemoryPrismDataset dataContext, PrismSession workspace) {
        PrismSnapshotDataset snapshot = dataContext == null ? new WorkspacePrismSnapshotDataset(workspace)
                : PrismSessionImporter.toSnapshot(dataContext, sourcePath, true);
        return replace(sessionId, label, sourcePath, snapshot, null, workspace);
    }

    Optional<ManagedPrismSession> find(String sessionId);

    Optional<ManagedPrismSession> findByWorkspace(PrismSession workspace);

    ManagedPrismSession require(String sessionId);

    List<ManagedPrismSession> sessions();

    ManagedPrismSessionSubscription subscribe(Consumer<ManagedPrismSessionChange> listener);
}
