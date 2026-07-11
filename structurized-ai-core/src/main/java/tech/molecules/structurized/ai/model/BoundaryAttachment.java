package tech.molecules.structurized.ai.model;

public record BoundaryAttachment(
        int attachmentId,
        String insideAtom,
        String outsideAtom,
        String bondId,
        int bondOrder
) {}
