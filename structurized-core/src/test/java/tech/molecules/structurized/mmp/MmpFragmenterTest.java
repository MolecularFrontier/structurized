package tech.molecules.structurized.mmp;

import com.actelion.research.chem.Molecule;
import com.actelion.research.chem.SmilesParser;
import com.actelion.research.chem.StereoMolecule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MmpFragmenterTest {

    @Test
    void fragmentsSimpleArylSideChainWithOneCut() throws Exception {
        MmpMiningConfig config = relaxedConfig().toBuilder().maxCuts(1).build();
        List<MmpFragmentationRecord> records = MmpFragmenter.fragment(
                new MmpInputCompound("cmpd-1", parse("Cc1ccccc1"), 1.0),
                config
        );

        assertFalse(records.isEmpty());
        assertTrue(records.stream().allMatch(record -> record.cutCount() == 1));
        assertTrue(records.stream().anyMatch(record -> record.keyHeavyAtomCount() == 6 && record.valueHeavyAtomCount() == 1));
    }

    @Test
    void amideCarbonNitrogenBondIsBlockedByDefaultRule() throws Exception {
        StereoMolecule mol = parse("CC(=O)NC");
        MmpMiningConfig config = relaxedConfig();

        List<Integer> candidateBonds = MmpFragmenter.candidateCutBonds(mol, config);

        int amideBond = findBond(mol, 6, 7, atom -> hasDoubleBondedOxygen(mol, atom));
        assertFalse(candidateBonds.contains(amideBond));
    }

    @Test
    void substructureRuleCanBlockMatchedQueryBonds() throws Exception {
        StereoMolecule mol = parse("CCOC");
        StereoMolecule query = parse("CO");
        int queryCOBond = query.getBond(0, 1);
        MmpMiningConfig config = relaxedConfig().toBuilder()
                .noCutBondRules(List.of(NoCutBondRules.substructureRule("block-c-o", query, queryCOBond)))
                .build();

        List<Integer> candidateBonds = MmpFragmenter.candidateCutBonds(mol, config);

        int targetCOBond = findBond(mol, 6, 8, atom -> true);
        assertFalse(candidateBonds.contains(targetCOBond));
    }

    @Test
    void smallRingBondsAreNotCut() throws Exception {
        StereoMolecule mol = parse("C1CCCCC1C");
        MmpMiningConfig config = relaxedConfig();

        List<Integer> candidateBonds = MmpFragmenter.candidateCutBonds(mol, config);

        for (int bond : candidateBonds) {
            assertFalse(mol.isRingBond(bond));
        }
    }

    @Test
    void macrocycleTwoCutRecordsAreGenerated() throws Exception {
        MmpMiningConfig config = relaxedConfig().toBuilder()
                .maxCuts(2)
                .macrocycleMinRingSize(10)
                .build();

        List<MmpFragmentationRecord> records = MmpFragmenter.fragment(
                new MmpInputCompound("macro", parse("C1CCCCCCCCCCC1"), 1.0),
                config
        );

        assertFalse(records.isEmpty());
        assertTrue(records.stream().allMatch(record -> record.cutCount() == 2));
    }


    @Test
    void mappedFragmentationsExposeOriginalAttachmentsAndSelectionFiltering() throws Exception {
        List<MmpFragmentationMatch> matches = MmpFragmenter.fragmentWithMapping(
                new MmpInputCompound("query", parse("CCOc1ccccc1"), null),
                relaxedConfig().toBuilder().maxCuts(1).build()
        );

        assertFalse(matches.isEmpty());
        MmpFragmentationMatch match = matches.getFirst();
        assertEquals(1, match.attachments().size());
        MmpAttachment attachment = match.attachments().getFirst();
        assertTrue(match.keyAtomIndices().contains(attachment.keyAtomIndex()));
        assertTrue(match.valueAtomIndices().contains(attachment.valueAtomIndex()));
        assertTrue(match.touchesAnyAtom(java.util.Set.of(attachment.keyAtomIndex())));
        assertTrue(match.touchesAnyAtom(java.util.Set.of(attachment.valueAtomIndex())));
        assertFalse(match.touchesAnyAtom(java.util.Set.of(10_000)));
        assertTrue(match.touchesAnyAtom(java.util.Set.of()));
    }


    @Test
    void acyclicTwoCutRecordsAlwaysHaveTwoMappedAttachments() throws Exception {
        List<MmpFragmentationMatch> matches = MmpFragmenter.fragmentWithMapping(
                new MmpInputCompound("linker", parse("CCOCC"), null),
                relaxedConfig().toBuilder().maxCuts(2).build()
        ).stream().filter(match -> match.record().cutCount() == 2).toList();

        assertFalse(matches.isEmpty());
        assertTrue(matches.stream().allMatch(match -> match.attachments().size() == 2));
        assertTrue(matches.stream().allMatch(match -> match.attachments().stream()
                .map(MmpAttachment::label).sorted().toList().equals(List.of(1, 2))));
    }

    private static MmpMiningConfig relaxedConfig() {
        return MmpMiningConfig.builder()
                .minKeyHeavyAtoms(1)
                .maxVariableHeavyAtoms(20)
                .maxVariableToMolHeavyAtomFraction(1.0)
                .build();
    }

    private static int findBond(StereoMolecule mol, int atomicNoA, int atomicNoB, AtomPredicate predicateOnA) {
        for (int bond = 0; bond < mol.getBonds(); bond++) {
            int a1 = mol.getBondAtom(0, bond);
            int a2 = mol.getBondAtom(1, bond);
            if (mol.getAtomicNo(a1) == atomicNoA && mol.getAtomicNo(a2) == atomicNoB && predicateOnA.test(a1)) {
                return bond;
            }
            if (mol.getAtomicNo(a2) == atomicNoA && mol.getAtomicNo(a1) == atomicNoB && predicateOnA.test(a2)) {
                return bond;
            }
        }
        throw new AssertionError("bond not found");
    }

    private static boolean hasDoubleBondedOxygen(StereoMolecule mol, int atom) {
        for (int i = 0; i < mol.getConnAtoms(atom); i++) {
            int bond = mol.getConnBond(atom, i);
            int neighbor = mol.getConnAtom(atom, i);
            if (mol.getAtomicNo(neighbor) == 8 && mol.getBondOrder(bond) == 2) {
                return true;
            }
        }
        return false;
    }

    private static StereoMolecule parse(String smiles) throws Exception {
        StereoMolecule molecule = new StereoMolecule();
        new SmilesParser().parse(molecule, smiles);
        molecule.ensureHelperArrays(Molecule.cHelperRings);
        return molecule;
    }

    private interface AtomPredicate {
        boolean test(int atom);
    }
}
