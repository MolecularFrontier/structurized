package tech.molecules.structurized.ai.model;

import java.util.List;

public record ExactStructureSearchResult(
        SearchQuerySummary query,
        SearchScope scope,
        SearchSummary summary,
        List<ExactStructureSearchMatch> matches,
        String identityDefinition
) {}
