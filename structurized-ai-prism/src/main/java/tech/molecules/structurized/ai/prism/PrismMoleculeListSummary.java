package tech.molecules.structurized.ai.prism;

public record PrismMoleculeListSummary(
        String sessionId,
        String listId,
        String title,
        int documentCount
) {
}
