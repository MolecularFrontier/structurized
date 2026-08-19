package tech.molecules.structurized.workbench.prism;

import com.actelion.research.chem.Molecule;
import com.actelion.research.chem.StereoMolecule;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MmpFragmentDepictionTest {
    @Test
    void parsesTwoCutFragmentAndColorsNamedConnectors() {
        MmpFragmentDepiction.ParseResult result =
                MmpFragmentDepiction.parse(MmpTestFragments.idcode(6, 2));

        assertTrue(result.isValid());
        StereoMolecule molecule = result.molecule();
        Map<String, Integer> connectorColors = new HashMap<>();
        for (int atom = 0; atom < molecule.getAllAtoms(); atom++) {
            if (molecule.getAtomicNo(atom) == 0) {
                connectorColors.put(molecule.getAtomCustomLabel(atom), molecule.getAtomColor(atom));
            }
        }
        assertEquals(Molecule.cAtomColorBlue, connectorColors.get("R1"));
        assertEquals(Molecule.cAtomColorOrange, connectorColors.get("R2"));
    }

    @Test
    void malformedIdcodeReturnsRenderableFallbackState() {
        MmpFragmentDepiction.ParseResult result = MmpFragmentDepiction.parse("not-an-idcode");

        assertFalse(result.isValid());
        assertNull(result.molecule());
        assertEquals("Unable to render structure", result.message());
    }
}
