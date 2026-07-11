package tech.molecules.structurized.ai.search;

import tech.molecules.structurized.ai.model.ExactStructureSearchRequest;
import tech.molecules.structurized.ai.model.ExactStructureSearchResult;
import tech.molecules.structurized.ai.model.SubstructureSearchRequest;
import tech.molecules.structurized.ai.model.SubstructureSearchResult;

public interface StructureSearchService {
    ExactStructureSearchResult searchExactStructure(ExactStructureSearchRequest request);

    SubstructureSearchResult searchSubstructure(SubstructureSearchRequest request);
}
