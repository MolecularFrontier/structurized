package tech.molecules.structurized.mmp;

import com.actelion.research.chem.Canonizer;
import com.actelion.research.chem.SmilesParser;
import com.actelion.research.chem.StereoMolecule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers one-cut hydrogen replacement (-H &lt;-&gt; -R) fragmentation and assembly. */
class MmpHydrogenCutTest {

    private static final MmpMiningConfig HYDROGEN_CONFIG = MmpMiningConfig.builder()
            .maxCuts(1)
            .minKeyHeavyAtoms(1)
            .allowHydrogenCuts(true)
            .build();

    private static StereoMolecule molecule(String smiles) {
        StereoMolecule mol = new StereoMolecule();
        try {
            new SmilesParser().parse(mol, smiles);
        } catch (Exception exception) {
            throw new IllegalArgumentException("cannot parse " + smiles, exception);
        }
        mol.ensureHelperArrays(StereoMolecule.cHelperRings);
        return mol;
    }

    private static String idcode(String smiles) {
        return new Canonizer(molecule(smiles)).getIDCode();
    }

    private static List<MmpFragmentationMatch> hydrogenMatches(String smiles) {
        return MmpFragmenter.fragmentWithMapping(
                        new MmpInputCompound("c1", molecule(smiles), null), HYDROGEN_CONFIG).stream()
                .filter(MmpFragmentationMatch::isHydrogenCut)
                .toList();
    }

    @Test
    @DisplayName("hydrogen cuts are not enumerated unless explicitly enabled")
    void hydrogenCutsAreOptIn() {
        List<MmpFragmentationRecord> records = MmpFragmenter.fragment(
                new MmpInputCompound("c1", molecule("c1ccccc1C"), null),
                MmpMiningConfig.builder().maxCuts(1).minKeyHeavyAtoms(1).build());
        assertTrue(records.stream().noneMatch(r -> MmpHydrogenValue.isHydrogen(r.valueIdcode())),
                "default configuration must not produce hydrogen cuts");
    }

    @Test
    @DisplayName("benzene yields exactly one hydrogen site despite six hydrogens")
    void symmetricHydrogensCollapse() {
        assertEquals(1, hydrogenMatches("c1ccccc1").size());
    }

    @Test
    @DisplayName("toluene yields one aromatic ortho, meta, para site plus the methyl site")
    void tolueneHydrogenSitesAreDeduplicatedBySymmetry() {
        // ortho, meta, para on the ring and the methyl group: four distinct environments.
        assertEquals(4, hydrogenMatches("Cc1ccccc1").size());
    }

    @Test
    @DisplayName("a hydrogen cut records zero value heavy atoms and the sentinel value")
    void hydrogenRecordShape() {
        List<MmpFragmentationMatch> matches = hydrogenMatches("c1ccccc1");
        assertEquals(1, matches.size());
        MmpFragmentationMatch match = matches.getFirst();
        assertEquals(MmpHydrogenValue.SENTINEL, match.record().valueIdcode());
        assertEquals(0, match.record().valueHeavyAtomCount());
        assertEquals(1, match.record().cutCount());
        assertEquals(6, match.record().keyHeavyAtomCount());
        assertTrue(match.record().cutBondIndices().isEmpty());
        assertTrue(match.valueAtomIndices().isEmpty());
        assertEquals(1, match.attachments().size());
        assertTrue(match.attachments().getFirst().isHydrogenSite());
    }

    @Test
    @DisplayName("fully substituted atoms contribute no hydrogen site")
    void quaternaryCarbonHasNoHydrogenSite() {
        // The central carbon of neopentane carries no hydrogen; only the four methyls do,
        // and they are symmetry equivalent.
        assertEquals(1, hydrogenMatches("CC(C)(C)C").size());
    }

    @Test
    @DisplayName("sentinel cannot collide with a real IDCode")
    void sentinelIsOutsideIdcodeAlphabet() {
        // Canonizer emits (char)(sixBitValue + 64), so every IDCode character is >= '?' (63).
        for (char character : MmpHydrogenValue.SENTINEL.toCharArray()) {
            if (character < 63) {
                return;
            }
        }
        throw new AssertionError("sentinel must contain a character below ASCII 63");
    }

    @Test
    @DisplayName("applying H->methyl to benzene produces toluene")
    void appliesHydrogenToSubstituent() {
        MmpFragmentationMatch benzeneSite = hydrogenMatches("c1ccccc1").getFirst();
        String methylValue = methylValueIdcode();

        MmpTransformDefinition transform = new MmpTransformDefinition(
                MmpPair.createTransformId(1, MmpHydrogenValue.SENTINEL, methylValue),
                1, MmpHydrogenValue.SENTINEL, methylValue);
        MmpTransformApplicationAttempt attempt =
                MmpTransformApplicator.apply(benzeneSite, transform);

        assertTrue(attempt.isApplied(), "attempt should be applied but was: " + attempt.message());
        assertEquals(idcode("Cc1ccccc1"), attempt.application().productIdcode());
    }

    @Test
    @DisplayName("applying methyl->H to toluene produces benzene")
    void appliesSubstituentToHydrogen() {
        MmpFragmentationMatch methylSite = MmpFragmenter.fragmentWithMapping(
                        new MmpInputCompound("c1", molecule("Cc1ccccc1"), null), HYDROGEN_CONFIG).stream()
                .filter(match -> !match.isHydrogenCut())
                .filter(match -> match.record().valueHeavyAtomCount() == 1)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a methyl fragmentation"));

        MmpTransformDefinition transform = new MmpTransformDefinition(
                MmpPair.createTransformId(1, methylSite.record().valueIdcode(), MmpHydrogenValue.SENTINEL),
                1, methylSite.record().valueIdcode(), MmpHydrogenValue.SENTINEL);
        MmpTransformApplicationAttempt attempt =
                MmpTransformApplicator.apply(methylSite, transform);

        assertTrue(attempt.isApplied(), "attempt should be applied but was: " + attempt.message());
        assertEquals(idcode("c1ccccc1"), attempt.application().productIdcode());
    }

    @Test
    @DisplayName("H->R followed by R->H round trips to the original structure")
    void roundTripsThroughHydrogen() {
        String methylValue = methylValueIdcode();
        MmpFragmentationMatch benzeneSite = hydrogenMatches("c1ccccc1").getFirst();

        MmpTransformApplicationAttempt forward = MmpTransformApplicator.apply(benzeneSite,
                new MmpTransformDefinition("f", 1, MmpHydrogenValue.SENTINEL, methylValue));
        assertTrue(forward.isApplied());
        assertEquals(idcode("Cc1ccccc1"), forward.application().productIdcode());

        StereoMolecule product = new StereoMolecule();
        new com.actelion.research.chem.IDCodeParser()
                .parse(product, forward.application().productIdcode());
        product.ensureHelperArrays(StereoMolecule.cHelperRings);

        MmpFragmentationMatch methylSite = MmpFragmenter.fragmentWithMapping(
                        new MmpInputCompound("c2", product, null), HYDROGEN_CONFIG).stream()
                .filter(match -> methylValue.equals(match.record().valueIdcode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("methyl fragmentation missing from product"));

        MmpTransformApplicationAttempt back = MmpTransformApplicator.apply(methylSite,
                new MmpTransformDefinition("b", 1, methylValue, MmpHydrogenValue.SENTINEL));
        assertTrue(back.isApplied());
        assertEquals(idcode("c1ccccc1"), back.application().productIdcode());
    }

    @Test
    @DisplayName("hydrogen cuts pair unsubstituted and substituted compounds")
    void minesHydrogenPairs() {
        MmpMiningResult result = MmpMiner.mine(List.of(
                new MmpInputCompound("benzene", molecule("c1ccccc1"), 1.0),
                new MmpInputCompound("toluene", molecule("Cc1ccccc1"), 3.0)
        ), MmpMiningConfig.builder()
                .maxCuts(1).minKeyHeavyAtoms(1).minTransformSupport(1).allowHydrogenCuts(true).build());

        List<MmpPair> hydrogenPairs = result.pairs().stream()
                .filter(pair -> MmpHydrogenValue.isHydrogen(pair.fromValueIdcode())
                        || MmpHydrogenValue.isHydrogen(pair.toValueIdcode()))
                .toList();
        assertFalse(hydrogenPairs.isEmpty(), "expected H<->methyl pairs");

        MmpPair forward = hydrogenPairs.stream()
                .filter(pair -> MmpHydrogenValue.isHydrogen(pair.fromValueIdcode()))
                .findFirst()
                .orElseThrow();
        assertEquals("benzene", forward.compoundIdA());
        assertEquals("toluene", forward.compoundIdB());
        assertEquals(2.0, forward.delta(), 1e-9);
    }

    @Test
    @DisplayName("exact fragment selection rejects hydrogen cuts, attachment vicinity accepts them")
    void selectionSemantics() {
        MmpFragmentationMatch site = hydrogenMatches("c1ccccc1").getFirst();
        int attachmentAtom = site.attachments().getFirst().keyAtomIndex();

        assertFalse(MmpSelectionMode.EXACT_FRAGMENT.accepts(site, Set.of(attachmentAtom)));
        assertTrue(MmpSelectionMode.ATTACHMENT_VICINITY.accepts(site, Set.of(attachmentAtom)));
        assertTrue(MmpSelectionMode.EDITABLE_REGION.accepts(site, Set.of(attachmentAtom)));
        assertTrue(MmpSelectionMode.ALL_SITES.accepts(site, Set.of()));

        // An unrelated selection must not silently match every hydrogen site.
        int otherAtom = attachmentAtom == 0 ? 3 : 0;
        assertFalse(MmpSelectionMode.EDITABLE_REGION.accepts(site, Set.of(otherAtom)));
    }

    @Test
    @DisplayName("hydrogen cuts stay within the per-compound record cap on a drug-sized molecule")
    void recordGrowthStaysBounded() {
        // Imatinib: 37 heavy atoms, many aromatic CH and NH positions.
        String imatinib = "CN1CCN(Cc2ccc(cc2)C(=O)Nc2ccc(C)c(Nc3nccc(n3)c3cccnc3)c2)CC1";
        MmpMiningConfig withoutHydrogen = MmpMiningConfig.defaults();
        MmpMiningConfig withHydrogen = withoutHydrogen.toBuilder().allowHydrogenCuts(true).build();

        int baseline = MmpFragmenter.fragment(
                new MmpInputCompound("imatinib", molecule(imatinib), null), withoutHydrogen).size();
        List<MmpFragmentationRecord> extended = MmpFragmenter.fragment(
                new MmpInputCompound("imatinib", molecule(imatinib), null), withHydrogen);
        long hydrogenRecords = extended.stream()
                .filter(r -> MmpHydrogenValue.isHydrogen(r.valueIdcode()))
                .count();

        System.out.printf("imatinib records: baseline=%d withHydrogen=%d (hydrogen=%d, factor=%.2f)%n",
                baseline, extended.size(), hydrogenRecords,
                extended.size() / (double) baseline);

        assertTrue(hydrogenRecords > 0, "expected hydrogen replacement sites");
        assertTrue(extended.size() <= withHydrogen.maxFragmentationRecordsPerCompound(),
                "hydrogen cuts must not exhaust the per-compound record cap");
        // Hydrogen sites are one-cut only, so growth must stay far below the quadratic blow-up
        // that including them in two-cut sets would cause.
        assertTrue(extended.size() < baseline * 3,
                "one-cut-only hydrogen sites should not multiply the record count");
        assertTrue(extended.stream()
                        .filter(r -> MmpHydrogenValue.isHydrogen(r.valueIdcode()))
                        .allMatch(r -> r.cutCount() == 1),
                "every hydrogen record must be a one-cut fragmentation");
    }

    /** Canonical one-cut value fragment for a methyl substituent, taken from toluene. */
    private static String methylValueIdcode() {
        String value = MmpFragmenter.fragmentWithMapping(
                        new MmpInputCompound("t", molecule("Cc1ccccc1"), null), HYDROGEN_CONFIG).stream()
                .filter(match -> !match.isHydrogenCut())
                .filter(match -> match.record().valueHeavyAtomCount() == 1)
                .map(match -> match.record().valueIdcode())
                .findFirst()
                .orElse(null);
        assertNotNull(value, "toluene must yield a methyl value fragment");
        return value;
    }
}
