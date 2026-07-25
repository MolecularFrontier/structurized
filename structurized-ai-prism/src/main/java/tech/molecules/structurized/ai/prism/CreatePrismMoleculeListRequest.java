package tech.molecules.structurized.ai.prism;

public record CreatePrismMoleculeListRequest(
        String sessionId,
        String listId,
        String title
) {
}
