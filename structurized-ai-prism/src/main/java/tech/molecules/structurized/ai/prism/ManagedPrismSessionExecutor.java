package tech.molecules.structurized.ai.prism;

import java.util.function.Supplier;

@FunctionalInterface
public interface ManagedPrismSessionExecutor {
    <T> T execute(Supplier<T> action);

    static ManagedPrismSessionExecutor direct() {
        return new ManagedPrismSessionExecutor() {
            @Override
            public <T> T execute(Supplier<T> action) {
                return action.get();
            }
        };
    }
}
