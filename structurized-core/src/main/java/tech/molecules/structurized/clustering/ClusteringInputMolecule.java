package tech.molecules.structurized.clustering;

import com.actelion.research.chem.StereoMolecule;

import java.util.Objects;

public record ClusteringInputMolecule(String structureId, String label, StereoMolecule molecule) {
    public ClusteringInputMolecule {
        if (structureId == null || structureId.isBlank()) {
            throw new IllegalArgumentException("structureId must not be blank");
        }
        Objects.requireNonNull(molecule, "molecule");
        structureId = structureId.trim();
        label = label == null || label.isBlank() ? structureId : label.trim();
        molecule = new StereoMolecule(molecule);
    }
}
