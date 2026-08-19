package tech.molecules.structurized.workbench.prism;

import com.actelion.research.chem.IDCodeParser;
import com.actelion.research.chem.Molecule;
import com.actelion.research.chem.StereoMolecule;

/** Parses persisted MMP fragment IDCodes and prepares connector atoms for depiction. */
final class MmpFragmentDepiction {
    private MmpFragmentDepiction() {
    }

    static ParseResult parse(String idcode) {
        if (idcode == null || idcode.isBlank()) {
            return new ParseResult(null, "No structure available");
        }
        try {
            StereoMolecule molecule = new StereoMolecule();
            new IDCodeParser().parse(molecule, idcode);
            if (molecule.getAllAtoms() == 0) {
                return new ParseResult(null, "Empty structure");
            }
            molecule.ensureHelperArrays(Molecule.cHelperRings);
            colorConnectors(molecule);
            return new ParseResult(molecule, null);
        } catch (RuntimeException exception) {
            return new ParseResult(null, "Unable to render structure");
        }
    }

    private static void colorConnectors(StereoMolecule molecule) {
        for (int atom = 0; atom < molecule.getAllAtoms(); atom++) {
            if (molecule.getAtomicNo(atom) != 0) {
                continue;
            }
            String label = molecule.getAtomCustomLabel(atom);
            int color = switch (label == null ? "" : label) {
                case "R1" -> Molecule.cAtomColorBlue;
                case "R2" -> Molecule.cAtomColorOrange;
                default -> Molecule.cAtomColorMagenta;
            };
            molecule.setAtomColor(atom, color);
            molecule.setAtomMarker(atom, true);
        }
    }

    record ParseResult(StereoMolecule molecule, String message) {
        ParseResult {
            molecule = molecule == null ? null : new StereoMolecule(molecule);
        }

        @Override
        public StereoMolecule molecule() {
            return molecule == null ? null : new StereoMolecule(molecule);
        }

        boolean isValid() {
            return molecule != null;
        }
    }
}
