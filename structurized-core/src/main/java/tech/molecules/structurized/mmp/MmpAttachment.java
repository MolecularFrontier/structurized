package tech.molecules.structurized.mmp;

/**
 * One attachment created by an MMP cut, mapped back to the original molecule.
 */
public record MmpAttachment(
        int label,
        int cutBondIndex,
        int keyAtomIndex,
        int valueAtomIndex,
        int bondType
) {
    public MmpAttachment {
        if (label < 1) {
            throw new IllegalArgumentException("label must be positive");
        }
        if (cutBondIndex < 0 || keyAtomIndex < 0 || valueAtomIndex < 0) {
            throw new IllegalArgumentException("attachment atom and bond indices must not be negative");
        }
        if (bondType < 1) {
            throw new IllegalArgumentException("bondType must be positive");
        }
    }

    public String connectorLabel() {
        return "R" + label;
    }

    public boolean touchesAnyAtom(Iterable<Integer> atomIndices) {
        if (atomIndices == null) {
            return false;
        }
        for (Integer atomIndex : atomIndices) {
            if (atomIndex != null && (atomIndex == keyAtomIndex || atomIndex == valueAtomIndex)) {
                return true;
            }
        }
        return false;
    }
}
