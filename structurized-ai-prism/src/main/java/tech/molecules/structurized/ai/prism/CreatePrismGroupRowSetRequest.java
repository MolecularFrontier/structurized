package tech.molecules.structurized.ai.prism;

public record CreatePrismGroupRowSetRequest(
        String sessionId,
        String groupingId,
        String groupId,
        String rowSetId,
        String name,
        String description
) {
}
