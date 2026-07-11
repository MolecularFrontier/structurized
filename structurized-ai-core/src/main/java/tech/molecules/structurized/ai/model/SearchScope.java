package tech.molecules.structurized.ai.model;

import java.util.List;

public record SearchScope(
        List<String> repositoryIds,
        int structuresSearched,
        String componentScope
) {}
