package tech.molecules.structurized.decomposition;

/**
 * Original molecule bond crossing this node's boundary.
 */
public record DecompositionBoundaryBond(
        int bondIndex,
        int atomInFragment,
        int atomOutsideFragment,
        int bondType,
        String neighborLabel
) {}
