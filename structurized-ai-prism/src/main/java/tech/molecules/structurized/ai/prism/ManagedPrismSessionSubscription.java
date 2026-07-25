package tech.molecules.structurized.ai.prism;

@FunctionalInterface
public interface ManagedPrismSessionSubscription extends AutoCloseable {
    @Override
    void close();
}
