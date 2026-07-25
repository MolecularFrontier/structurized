package tech.molecules.structurized.prismlite.app;

import tech.molecules.structurized.ai.prism.ManagedPrismSessionExecutor;

import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

final class SwingManagedPrismSessionExecutor implements ManagedPrismSessionExecutor {
    @Override
    public <T> T execute(Supplier<T> action) {
        if (SwingUtilities.isEventDispatchThread()) {
            return action.get();
        }
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    result.set(action.get());
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            });
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while committing a Prism session change", exception);
        } catch (InvocationTargetException exception) {
            throw propagate(exception.getCause());
        }
        if (failure.get() != null) {
            throw propagate(failure.get());
        }
        return result.get();
    }

    private static RuntimeException propagate(Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) return runtimeException;
        if (throwable instanceof Error error) throw error;
        return new IllegalStateException("Prism session change failed", throwable);
    }
}
