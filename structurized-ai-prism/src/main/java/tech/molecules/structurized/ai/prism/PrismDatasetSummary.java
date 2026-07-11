package tech.molecules.structurized.ai.prism;

public record PrismDatasetSummary(
        String datasetId,
        String label,
        String sourcePath,
        int subjectCount,
        int subjectSetCount,
        int endpointCount,
        int endpointValueCount,
        int structureSubjectCount
) {}
