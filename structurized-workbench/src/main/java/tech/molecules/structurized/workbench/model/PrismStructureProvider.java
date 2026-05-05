package tech.molecules.structurized.workbench.model;

import com.actelion.research.chem.Molecule;
import com.actelion.research.chem.SmilesParser;
import com.actelion.research.chem.StereoMolecule;
import tech.molecules.structurized.analytics.mmp.StructureProvider;
import tech.molecules.structurized.prism.provider.SubjectRecord;
import tech.molecules.structurized.prism.provider.inmemory.InMemoryPrismDataset;

import java.util.LinkedHashMap;
import java.util.Set;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Structure provider backed by PRISM subject SMILES fields.
 */
public final class PrismStructureProvider implements StructureProvider {
    private final Map<String, StereoMolecule> structuresBySubjectId;
    private final Map<String, String> parseErrorsBySubjectId;

    private PrismStructureProvider(Map<String, StereoMolecule> structuresBySubjectId, Map<String, String> parseErrorsBySubjectId) {
        this.structuresBySubjectId = Map.copyOf(structuresBySubjectId);
        this.parseErrorsBySubjectId = Map.copyOf(parseErrorsBySubjectId);
    }

    public static PrismStructureProvider from(InMemoryPrismDataset dataset) {
        Objects.requireNonNull(dataset, "dataset");
        SmilesParser parser = new SmilesParser();
        LinkedHashMap<String, StereoMolecule> structures = new LinkedHashMap<>();
        LinkedHashMap<String, String> errors = new LinkedHashMap<>();
        for (SubjectRecord subject : dataset.getSubjectRecords()) {
            if (subject.getSmiles() == null) {
                continue;
            }
            try {
                StereoMolecule molecule = new StereoMolecule();
                parser.parse(molecule, subject.getSmiles());
                molecule.ensureHelperArrays(Molecule.cHelperRings);
                structures.put(subject.getSubjectId(), molecule);
            } catch (Exception e) {
                errors.put(subject.getSubjectId(), e.getMessage());
            }
        }
        return new PrismStructureProvider(structures, errors);
    }

    @Override
    public Optional<StereoMolecule> findStructure(String subjectId) {
        StereoMolecule molecule = structuresBySubjectId.get(subjectId);
        return molecule == null ? Optional.empty() : Optional.of(new StereoMolecule(molecule));
    }

    public Map<String, String> parseErrorsBySubjectId() {
        return parseErrorsBySubjectId;
    }

    public Set<String> structureSubjectIds() {
        return structuresBySubjectId.keySet();
    }

    public int structureCount() {
        return structuresBySubjectId.size();
    }
}
