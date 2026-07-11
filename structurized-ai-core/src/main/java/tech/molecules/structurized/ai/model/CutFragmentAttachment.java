package tech.molecules.structurized.ai.model;

public record CutFragmentAttachment(
        int attachmentId,
        String fragmentAtom,
        String otherSideAtom,
        String originalBondId
) {}
