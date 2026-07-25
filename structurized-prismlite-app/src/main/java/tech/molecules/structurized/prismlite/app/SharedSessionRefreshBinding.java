package tech.molecules.structurized.prismlite.app;

import tech.molecules.structurized.ai.prism.ManagedPrismSession;
import tech.molecules.structurized.ai.prism.ManagedPrismSessionChangeOrigin;
import tech.molecules.structurized.ai.prism.ManagedPrismSessionSubscription;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

final class SharedSessionRefreshBinding implements AutoCloseable {
    private final AtomicLong lastScheduledRevision = new AtomicLong();
    private final ManagedPrismSessionSubscription subscription;

    SharedSessionRefreshBinding(ManagedPrismSession session,
                                Runnable refresh,
                                Consumer<Runnable> scheduler) {
        Objects.requireNonNull(refresh, "refresh");
        Objects.requireNonNull(scheduler, "scheduler");
        this.subscription = session.subscribe(change -> {
            if (change.origin() == ManagedPrismSessionChangeOrigin.LOCAL_UI) return;
            if (change.type() == tech.molecules.structurized.ai.prism.ManagedPrismSessionChangeType.MOLECULES) return;
            long revision = change.revision();
            if (lastScheduledRevision.getAndAccumulate(revision, Math::max) >= revision) return;
            scheduler.accept(refresh);
        });
    }

    @Override
    public void close() {
        subscription.close();
    }
}
