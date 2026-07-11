package tech.molecules.structurized.ai.model;

import java.util.List;

public record CutBondsRequest(
        StructureRef structure,
        List<String> bondIds
) {
    public CutBondsRequest {
        if (structure == null) {
            throw new IllegalArgumentException("structure must not be null");
        }
        bondIds = bondIds == null ? List.of() : List.copyOf(bondIds);
    }
}
