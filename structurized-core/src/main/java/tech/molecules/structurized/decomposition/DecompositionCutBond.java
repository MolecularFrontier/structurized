package tech.molecules.structurized.decomposition;

/**
 * Original molecule bond cut by an applied decomposition rule.
 */
public record DecompositionCutBond(
        int bondIndex,
        int atom1,
        int atom2,
        int bondType,
        String label1,
        String label2
) {}
