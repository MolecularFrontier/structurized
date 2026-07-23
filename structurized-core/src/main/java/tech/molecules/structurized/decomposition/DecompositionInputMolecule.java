package tech.molecules.structurized.decomposition;

import com.actelion.research.chem.StereoMolecule;

/**
 * Dataset row used by the decomposition evaluator.
 */
public record DecompositionInputMolecule(
        String moleculeId,
        StereoMolecule molecule
) {}
