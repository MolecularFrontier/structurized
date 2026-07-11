package tech.molecules.structurized.ai.model;

import java.util.List;

public record CutFragment(
        String fragmentId,
        List<String> atomIds,
        List<String> bondIds,
        String smiles,
        List<CutFragmentAttachment> attachments
) {}
