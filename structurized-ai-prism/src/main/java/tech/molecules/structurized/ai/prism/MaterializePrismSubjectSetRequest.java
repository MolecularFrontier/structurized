package tech.molecules.structurized.ai.prism;

public record MaterializePrismSubjectSetRequest(
        String datasetId,
        String subjectSetId,
        String repositoryId,
        String label
) {}
