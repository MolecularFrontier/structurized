package tech.molecules.structurized.ai.prism;

import java.util.List;

public record AddPrismMoleculesRequest(
        String sessionId,
        String listId,
        List<PrismMoleculeInput> molecules
) {
    public AddPrismMoleculesRequest {
        molecules = molecules == null ? List.of() : List.copyOf(molecules);
    }
}
