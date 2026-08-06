package tech.molecules.structurized.mmp;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A canonical MMP fragmentation together with its mapping to the input molecule.
 */
public record MmpFragmentationMatch(
        MmpFragmentationRecord record,
        List<Integer> keyAtomIndices,
        List<Integer> valueAtomIndices,
        List<MmpAttachment> attachments
) {
    public MmpFragmentationMatch {
        record = Objects.requireNonNull(record, "record");
        keyAtomIndices = normalizeAtomIndices(keyAtomIndices, "keyAtomIndices");
        valueAtomIndices = normalizeAtomIndices(valueAtomIndices, "valueAtomIndices");
        attachments = (attachments == null ? List.<MmpAttachment>of() : attachments).stream()
                .map(attachment -> Objects.requireNonNull(attachment, "attachment"))
                .sorted(java.util.Comparator.comparingInt(MmpAttachment::label))
                .toList();
        if (!Collections.disjoint(keyAtomIndices, valueAtomIndices)) {
            throw new IllegalArgumentException("key and value atom mappings must be disjoint");
        }

        if (attachments.size() != record.cutCount()) {
            throw new IllegalArgumentException("attachment count must equal cut count");
        }
        Set<Integer> labels = new HashSet<>();
        for (MmpAttachment attachment : attachments) {
            if (!labels.add(attachment.label())) {
                throw new IllegalArgumentException("attachment labels must be unique");
            }
            if (!keyAtomIndices.contains(attachment.keyAtomIndex())
                    || !valueAtomIndices.contains(attachment.valueAtomIndex())) {
                throw new IllegalArgumentException("attachment atoms must belong to their mapped partitions");
            }
        }
        for (int label = 1; label <= record.cutCount(); label++) {
            if (!labels.contains(label)) {
                throw new IllegalArgumentException("missing attachment label R" + label);
            }
        }
    }

    private static List<Integer> normalizeAtomIndices(List<Integer> atomIndices, String field) {
        List<Integer> supplied = atomIndices == null ? List.of() : atomIndices;
        List<Integer> normalized = supplied.stream()
                .map(atomIndex -> Objects.requireNonNull(atomIndex, field + " must not contain null"))
                .sorted()
                .distinct()
                .toList();
        if (normalized.size() != supplied.size()) {
            throw new IllegalArgumentException(field + " must not contain duplicates");
        }
        if (normalized.stream().anyMatch(atomIndex -> atomIndex < 0)) {
            throw new IllegalArgumentException(field + " must not contain negative indices");
        }
        return normalized;
    }

    /**
     * Returns true when no restriction is supplied, or when at least one selected atom is adjacent to a cut.
     */
    public boolean touchesAnyAtom(Set<Integer> atomIndices) {
        if (atomIndices == null || atomIndices.isEmpty()) {
            return true;
        }
        return attachments.stream().anyMatch(attachment -> attachment.touchesAnyAtom(atomIndices));
    }
}
