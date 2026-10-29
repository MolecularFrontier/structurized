package tech.molecules.structurized.mmp;

import java.util.LinkedHashSet;
import java.util.Set;

/** Converts an input atom selection into an allowed mapped MMP replacement region. */
public enum MmpSelectionMode {
    EDITABLE_REGION("Editable region") {
        @Override
        public boolean accepts(MmpFragmentationMatch match, Set<Integer> selectedAtoms) {
            Set<Integer> selection = normalized(selectedAtoms);
            if (selection.isEmpty()) {
                return false;
            }
            if (match.isHydrogenCut()) {
                // A hydrogen cut has no heavy value atoms, so containsAll() would accept every
                // selection. Require the substitution site itself to be selected instead.
                return match.touchesAnyAtom(selection);
            }
            return selection.containsAll(match.valueAtomIndices());
        }
    },
    EXACT_FRAGMENT("Exact fragment") {
        @Override
        public boolean accepts(MmpFragmentationMatch match, Set<Integer> selectedAtoms) {
            Set<Integer> selection = normalized(selectedAtoms);
            if (match.isHydrogenCut()) {
                // There is no variable fragment that a selection could equal.
                return false;
            }
            return !selection.isEmpty()
                    && selection.equals(new LinkedHashSet<>(match.valueAtomIndices()));
        }
    },
    ATTACHMENT_VICINITY("Attachment vicinity") {
        @Override
        public boolean accepts(MmpFragmentationMatch match, Set<Integer> selectedAtoms) {
            return !normalized(selectedAtoms).isEmpty() && match.touchesAnyAtom(selectedAtoms);
        }
    },
    ALL_SITES("All sites") {
        @Override
        public boolean accepts(MmpFragmentationMatch match, Set<Integer> selectedAtoms) {
            return true;
        }
    };

    private final String label;

    MmpSelectionMode(String label) {
        this.label = label;
    }

    public abstract boolean accepts(MmpFragmentationMatch match, Set<Integer> selectedAtoms);

    public boolean requiresSelection() {
        return this != ALL_SITES;
    }

    @Override
    public String toString() {
        return label;
    }

    private static Set<Integer> normalized(Set<Integer> selectedAtoms) {
        if (selectedAtoms == null || selectedAtoms.isEmpty()) return Set.of();
        LinkedHashSet<Integer> normalized = new LinkedHashSet<>();
        for (Integer atom : selectedAtoms) {
            if (atom != null && atom >= 0) normalized.add(atom);
        }
        return Set.copyOf(normalized);
    }
}
