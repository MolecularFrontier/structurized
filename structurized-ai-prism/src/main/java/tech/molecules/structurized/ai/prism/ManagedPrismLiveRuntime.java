package tech.molecules.structurized.ai.prism;

import tech.molecules.structurized.prism.engine.live.PrismLiveExecutionEnvironment;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

final class ManagedPrismLiveRuntime {
    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(threads("prism-live-scheduler"));
    private static final ExecutorService COMPUTATIONS = Executors.newFixedThreadPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())),
            threads("prism-live-compute"));
    private static final PrismLiveExecutionEnvironment ENVIRONMENT =
            new PrismLiveExecutionEnvironment(SCHEDULER, COMPUTATIONS, 512);

    private ManagedPrismLiveRuntime() {
    }

    static PrismLiveExecutionEnvironment environment() {
        return ENVIRONMENT;
    }

    private static ThreadFactory threads(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
