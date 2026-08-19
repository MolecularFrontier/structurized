package tech.molecules.structurized.ai.trace;

@FunctionalInterface
public interface AgentExplorationSubscription extends AutoCloseable {
    @Override
    void close();
}
