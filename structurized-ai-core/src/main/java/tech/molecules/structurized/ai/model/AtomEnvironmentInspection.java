package tech.molecules.structurized.ai.model;

import java.util.List;

public record AtomEnvironmentInspection(
        String centerAtom,
        int radius,
        List<String> atomIds,
        List<String> bondIds,
        String environmentSmiles,
        List<BoundaryAttachment> boundaryAttachments
) {}
