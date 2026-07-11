package tech.molecules.structurized.ai.prism;

import java.util.Map;

public record PrismSubjectSummary(
        String subjectId,
        String structureId,
        String batchId,
        String project,
        String series,
        boolean hasSmiles,
        Map<String, String> metadata
) {}
