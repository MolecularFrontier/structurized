package tech.molecules.structurized.ai.model;

import java.util.List;

public record SubstructureSearchResult(
        SearchQuerySummary query,
        SearchScope scope,
        SearchSummary summary,
        List<SubstructureSearchMatch> matches
) {}
