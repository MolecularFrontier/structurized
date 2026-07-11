package tech.molecules.structurized.ai.model;

import java.util.List;

public record ExactStructureSearchRequest(
        String querySmiles,
        List<String> repositoryIds,
        String componentScope
) {
    public ExactStructureSearchRequest(String querySmiles) {
        this(querySmiles, null, "whole_record");
    }
}
