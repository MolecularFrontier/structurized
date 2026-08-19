package tech.molecules.structurized.mmp;

import com.actelion.research.chem.Canonizer;
import com.actelion.research.chem.Molecule;
import com.actelion.research.chem.SmilesParser;
import com.actelion.research.chem.StereoMolecule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MmpFragmentAssemblerTest {
    @Test
    void reconstructsBothCompoundsOfOneCutPair() throws Exception {
        assertReconstructs("Cc1ccccc1", "CCc1ccccc1", 1);
    }

    @Test
    void reconstructsBothCompoundsOfTwoCutPair() throws Exception {
        assertReconstructs("CCOCC", "CCNCC", 2);
    }

    private static void assertReconstructs(String sourceSmiles, String targetSmiles, int cuts)
            throws Exception {
        StereoMolecule source = parse(sourceSmiles);
        StereoMolecule target = parse(targetSmiles);
        MmpMiningConfig config = config(cuts);
        MmpPair pair = MmpMiner.mine(List.of(
                        new MmpInputCompound("source", source, 1.0),
                        new MmpInputCompound("target", target, 2.0)), config)
                .pairs().stream()
                .filter(candidate -> candidate.cutCount() == cuts)
                .filter(candidate -> candidate.compoundIdA().equals("source"))
                .filter(candidate -> candidate.compoundIdB().equals("target"))
                .findFirst()
                .orElseThrow();

        MmpFragmentAssemblyAttempt a = MmpFragmentAssembler.assemble(
                pair.keyIdcode(), pair.fromValueIdcode(), cuts);
        MmpFragmentAssemblyAttempt b = MmpFragmentAssembler.assemble(
                pair.keyIdcode(), pair.toValueIdcode(), cuts);

        assertTrue(a.isAssembled(), a.message());
        assertTrue(b.isAssembled(), b.message());
        assertEquals(canonical(source), a.productIdcode());
        assertEquals(canonical(target), b.productIdcode());
    }

    static MmpMiningConfig config(int cuts) {
        return MmpMiningConfig.builder()
                .maxCuts(cuts)
                .minKeyHeavyAtoms(1)
                .maxVariableHeavyAtoms(20)
                .maxVariableToMolHeavyAtomFraction(1.0)
                .minTransformSupport(1)
                .build();
    }

    static StereoMolecule parse(String smiles) throws Exception {
        StereoMolecule molecule = new StereoMolecule();
        new SmilesParser().parse(molecule, smiles);
        molecule.ensureHelperArrays(Molecule.cHelperRings);
        return molecule;
    }

    static String canonical(StereoMolecule molecule) {
        return new Canonizer(molecule).getIDCode();
    }
}
