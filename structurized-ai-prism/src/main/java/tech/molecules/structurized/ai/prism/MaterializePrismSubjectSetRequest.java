package tech.molecules.structurized.ai.prism;

public record MaterializePrismSubjectSetRequest(
        String datasetId,
        String subjectSetId,
        String repositoryId,
        String label,
        String structureColumnId
) {
    public MaterializePrismSubjectSetRequest(
            String datasetId,
            String subjectSetId,
            String repositoryId,
            String label
    ) {
        this(datasetId, subjectSetId, repositoryId, label, null);
    }
}
