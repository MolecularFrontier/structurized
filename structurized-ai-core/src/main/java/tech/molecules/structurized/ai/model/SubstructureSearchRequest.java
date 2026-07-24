package tech.molecules.structurized.ai.model;

import java.util.List;

public record SubstructureSearchRequest(
        String query,
        String queryType,
        List<String> repositoryIds,
        String componentScope,
        int maxResults,
        int maxMatchesPerStructure,
        boolean includeAtomMappings,
        String outputMode,
        int offset,
        int limit
) {
    public static final String OUTPUT_COUNT = "count";
    public static final String OUTPUT_IDS = "ids";
    public static final String OUTPUT_FULL = "full";

    public SubstructureSearchRequest(String query) {
        this(query, "smiles", null, "all", 100, 1, true);
    }

    public SubstructureSearchRequest(
            String query,
            String queryType,
            List<String> repositoryIds,
            String componentScope,
            int maxResults,
            int maxMatchesPerStructure,
            boolean includeAtomMappings
    ) {
        this(query, queryType, repositoryIds, componentScope, maxResults, maxMatchesPerStructure, includeAtomMappings, OUTPUT_FULL, 0, maxResults);
    }
}
