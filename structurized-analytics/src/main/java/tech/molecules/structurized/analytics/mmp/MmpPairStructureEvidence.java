package tech.molecules.structurized.analytics.mmp;

import tech.molecules.structurized.mmp.MmpFragmentAssembler;
import tech.molecules.structurized.mmp.MmpFragmentAssemblyAttempt;
import tech.molecules.structurized.mmp.MmpPair;

import java.util.Objects;

/** Persisted valued example pair plus reconstructed complete compound structures. */
public record MmpPairStructureEvidence(
        MmpPair pair,
        String compoundAIdcode,
        String compoundBIdcode,
        String warning
) {
    public MmpPairStructureEvidence {
        pair = Objects.requireNonNull(pair, "pair");
        warning = normalize(warning);
    }

    public static MmpPairStructureEvidence reconstruct(MmpPair pair) {
        Objects.requireNonNull(pair, "pair");
        MmpFragmentAssemblyAttempt a = MmpFragmentAssembler.assemble(
                pair.keyIdcode(), pair.fromValueIdcode(), pair.cutCount());
        MmpFragmentAssemblyAttempt b = MmpFragmentAssembler.assemble(
                pair.keyIdcode(), pair.toValueIdcode(), pair.cutCount());
        String warning = null;
        if (!a.isAssembled() || !b.isAssembled()) {
            warning = "Could not reconstruct "
                    + (!a.isAssembled() ? "compound A: " + a.message() : "")
                    + (!a.isAssembled() && !b.isAssembled() ? "; " : "")
                    + (!b.isAssembled() ? "compound B: " + b.message() : "");
        }
        return new MmpPairStructureEvidence(
                pair,
                a.isAssembled() ? a.productIdcode() : null,
                b.isAssembled() ? b.productIdcode() : null,
                warning);
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
