package tech.molecules.structurized.ai.prism;

public record PrismSubjectSetSummary(
        String subjectSetId,
        String name,
        String setType,
        String subjectSetScope,
        String parentSetId,
        String description,
        int subjectCount
) {}
