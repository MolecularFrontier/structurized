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
        if (keyAtomIndex < 0) {
            throw new IllegalArgumentException("attachment key atom index must not be negative");
        }
        boolean hydrogenSite = cutBondIndex == MmpHydrogenValue.NO_INDEX
                && valueAtomIndex == MmpHydrogenValue.NO_INDEX;
        if (!hydrogenSite && (cutBondIndex < 0 || valueAtomIndex < 0)) {
            throw new IllegalArgumentException("attachment atom and bond indices must not be negative");
        }
        if (bondType < 1) {
            throw new IllegalArgumentException("bondType must be positive");
        }
    }

    /**
     * Returns true for a hydrogen replacement site, which has no cut bond and no heavy value atom.
     */
    public boolean isHydrogenSite() {
        return cutBondIndex == MmpHydrogenValue.NO_INDEX && valueAtomIndex == MmpHydrogenValue.NO_INDEX;
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
