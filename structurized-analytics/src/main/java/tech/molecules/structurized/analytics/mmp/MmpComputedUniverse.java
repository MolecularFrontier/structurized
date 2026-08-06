package tech.molecules.structurized.analytics.mmp;

import tech.molecules.structurized.mmp.MmpFragmentationRecord;
import tech.molecules.structurized.mmp.MmpPair;

import java.util.List;
import java.util.Objects;

/** Complete in-memory output for one mined structural universe. */
public record MmpComputedUniverse(
        MmpUniverse universe,
        List<String> structuralSubjectIds,
        List<MmpFragmentationRecord> fragmentationRecords,
        List<MmpPair> pairs,
        int missingStructureCount
) {
    public MmpComputedUniverse {
        universe = Objects.requireNonNull(universe, "universe");
        structuralSubjectIds = List.copyOf(structuralSubjectIds == null ? List.of() : structuralSubjectIds);
        fragmentationRecords = List.copyOf(fragmentationRecords == null ? List.of() : fragmentationRecords);
        pairs = List.copyOf(pairs == null ? List.of() : pairs);
        if (missingStructureCount < 0) {
            throw new IllegalArgumentException("missingStructureCount must not be negative");
        }
    }

    public int structuralSubjectCount() {
        return structuralSubjectIds.size();
    }
}
