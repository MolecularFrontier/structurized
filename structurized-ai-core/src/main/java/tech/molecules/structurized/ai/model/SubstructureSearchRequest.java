package tech.molecules.structurized.ai.model;

import java.util.List;

public record SubstructureSearchRequest(
        String query,
        String queryType,
        List<String> repositoryIds,
        String componentScope,
        int maxResults,
        int maxMatchesPerStructure,
        boolean includeAtomMappings
) {
    public SubstructureSearchRequest(String query) {
        this(query, "smiles", null, "all", 100, 1, true);
    }
}
