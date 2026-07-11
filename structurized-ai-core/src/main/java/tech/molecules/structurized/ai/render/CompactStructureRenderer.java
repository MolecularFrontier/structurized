package tech.molecules.structurized.ai.render;

import tech.molecules.structurized.ai.model.AtomInspection;
import tech.molecules.structurized.ai.model.BondInspection;
import tech.molecules.structurized.ai.model.ComponentInspection;
import tech.molecules.structurized.ai.model.StructureInspection;
import tech.molecules.structurized.ai.model.StructureRecord;

import java.util.stream.Collectors;

public final class CompactStructureRenderer {
    public String render(StructureInspection inspection) {
        StructureRecord record = inspection.record();
        StringBuilder sb = new StringBuilder();
        sb.append("STRUCTURE ").append(record.repositoryId()).append(':').append(record.structureId()).append('\n');
        sb.append("label: ").append(nullToDash(record.label())).append('\n');
        sb.append("input_smiles: ").append(nullToDash(record.inputSmiles())).append('\n');
        sb.append("canonical_smiles: ").append(nullToDash(record.canonicalSmiles())).append('\n');
        sb.append("canonical_idcode: ").append(nullToDash(record.canonicalIdCode())).append('\n');
        sb.append("components: ").append(inspection.components().size()).append('\n');
        sb.append("atoms: ").append(inspection.atoms().size()).append('\n');
        sb.append("bonds: ").append(inspection.bonds().size()).append('\n');

        sb.append("\nCOMPONENTS\n");
        sb.append("id  heavy  atoms  charge  canonical_idcode\n");
        for (ComponentInspection component : inspection.components()) {
            sb.append(component.componentId()).append("  ")
                    .append(component.heavyAtomCount()).append("  ")
                    .append(atomRange(component)).append("  ")
                    .append(component.formalCharge()).append("  ")
                    .append(component.canonicalIdCode())
                    .append('\n');
        }

        sb.append("\nATOMS\n");
        sb.append("id  el  q  H  deg  val  arom  ring  minR  stereo  comp  x  y\n");
        for (AtomInspection atom : inspection.atoms()) {
            sb.append(atom.atomId()).append("  ")
                    .append(atom.element()).append("  ")
                    .append(atom.formalCharge()).append("  ")
                    .append(atom.totalHydrogens()).append("  ")
                    .append(atom.heavyAtomDegree()).append("  ")
                    .append(atom.occupiedValence()).append("  ")
                    .append(yn(atom.aromatic())).append("  ")
                    .append(yn(atom.ringAtom())).append("  ")
                    .append(nullToDash(atom.smallestRingSize())).append("  ")
                    .append(nullToDash(atom.stereo())).append("  ")
                    .append(atom.componentId()).append("  ")
                    .append(format(atom.coordinates2d().x())).append("  ")
                    .append(format(atom.coordinates2d().y()))
                    .append('\n');
        }

        sb.append("\nBONDS\n");
        sb.append("id  atoms  order  arom  ring  minR  stereo  rot\n");
        for (BondInspection bond : inspection.bonds()) {
            sb.append(bond.bondId()).append("  ")
                    .append(bond.atom1()).append('-').append(bond.atom2()).append("  ")
                    .append(bond.order()).append("  ")
                    .append(yn(bond.aromatic())).append("  ")
                    .append(yn(bond.ringBond())).append("  ")
                    .append(nullToDash(bond.smallestRingSize())).append("  ")
                    .append(nullToDash(bond.stereo())).append("  ")
                    .append(yn(bond.rotatableCandidate()))
                    .append('\n');
        }
        return sb.toString();
    }

    private static String atomRange(ComponentInspection component) {
        return component.atomIds().stream().collect(Collectors.joining(","));
    }

    private static String yn(boolean value) {
        return value ? "Y" : "N";
    }

    private static String nullToDash(Object value) {
        return value == null ? "-" : value.toString();
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }
}
