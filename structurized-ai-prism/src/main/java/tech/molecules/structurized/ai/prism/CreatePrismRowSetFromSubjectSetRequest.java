package tech.molecules.structurized.ai.prism;

public record CreatePrismRowSetFromSubjectSetRequest(
        String sessionId,
        String subjectSetId,
        String rowSetId,
        String name,
        String description
) {}
