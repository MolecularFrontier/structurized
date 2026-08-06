package tech.molecules.structurized.mmp;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MmpSelectionModeTest {
    @Test
    void regionExactVicinityAndAllSitesHaveDistinctSemantics() throws Exception {
        MmpMiningConfig config = MmpFragmentAssemblerTest.config(2);
        List<MmpFragmentationMatch> matches = MmpFragmenter.fragmentWithMapping(
                new MmpInputCompound(
                        "query", MmpFragmentAssemblerTest.parse("CCOCC"), null), config);
        MmpFragmentationMatch twoCut = matches.stream()
                .filter(match -> match.record().cutCount() == 2)
                .findFirst()
                .orElseThrow();
        Set<Integer> exact = Set.copyOf(twoCut.valueAtomIndices());
        LinkedHashSet<Integer> larger = new LinkedHashSet<>(exact);
        larger.add(twoCut.keyAtomIndices().getFirst());
        MmpFragmentationMatch oneCutWithRemoteKeyAtom = MmpFragmenter.fragmentWithMapping(
                        new MmpInputCompound(
                                "one-cut", MmpFragmentAssemblerTest.parse("CCc1ccccc1"), null),
                        MmpFragmentAssemblerTest.config(1)).stream()
                .filter(match -> match.keyAtomIndices().stream()
                        .anyMatch(atom -> match.attachments().stream()
                                .noneMatch(attachment -> attachment.keyAtomIndex() == atom)))
                .findFirst()
                .orElseThrow();
        int farKeyAtom = oneCutWithRemoteKeyAtom.keyAtomIndices().stream()
                .filter(atom -> oneCutWithRemoteKeyAtom.attachments().stream()
                        .noneMatch(attachment -> attachment.keyAtomIndex() == atom))
                .findFirst()
                .orElseThrow();

        assertTrue(MmpSelectionMode.EDITABLE_REGION.accepts(twoCut, larger));
        assertTrue(MmpSelectionMode.EXACT_FRAGMENT.accepts(twoCut, exact));
        assertFalse(MmpSelectionMode.EXACT_FRAGMENT.accepts(twoCut, larger));
        assertTrue(MmpSelectionMode.ATTACHMENT_VICINITY.accepts(
                twoCut, Set.of(twoCut.attachments().getFirst().keyAtomIndex())));
        assertFalse(MmpSelectionMode.ATTACHMENT_VICINITY.accepts(
                oneCutWithRemoteKeyAtom, Set.of(farKeyAtom)));
        assertTrue(MmpSelectionMode.ALL_SITES.accepts(twoCut, Set.of()));
        assertFalse(MmpSelectionMode.EDITABLE_REGION.accepts(twoCut, Set.of()));
    }
}
