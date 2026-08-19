package tech.molecules.structurized.workbench.prism;

import com.actelion.research.chem.Canonizer;
import com.actelion.research.chem.Molecule;
import com.actelion.research.chem.StereoMolecule;

final class MmpTestFragments {
    private MmpTestFragments() {
    }

    static String idcode(int atomicNumber, int cuts) {
        StereoMolecule molecule = new StereoMolecule();
        int center = molecule.addAtom(atomicNumber);
        for (int cut = 1; cut <= cuts; cut++) {
            int connector = molecule.addAtom(0);
            molecule.setAtomCustomLabel(connector, "R" + cut);
            molecule.addBond(center, connector, Molecule.cBondTypeSingle);
        }
        molecule.ensureHelperArrays(Molecule.cHelperRings);
        return new Canonizer(molecule, Canonizer.ENCODE_ATOM_CUSTOM_LABELS).getIDCode();
    }
}
