package tech.molecules.structurized.ai.prism;

import java.util.List;

public record MaterializePrismSubjectSetResult(
        String datasetId,
        String subjectSetId,
        String repositoryId,
        int subjectsSeen,
        int structuresImported,
        int missingSmiles,
        int invalidSmiles,
        List<PrismSkippedSubject> skippedSubjects
) {}
