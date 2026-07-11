package tech.molecules.structurized.ai.prism;

import java.util.List;

public record PrismDatasetInfo(
        PrismDatasetSummary summary,
        List<PrismSubjectSetSummary> subjectSets,
        List<PrismEndpointSummary> endpoints
) {}
