package tech.molecules.structurized.ai.ocl;

import com.actelion.research.chem.Canonizer;
import com.actelion.research.chem.IsomericSmilesCreator;
import com.actelion.research.chem.Molecule;
import com.actelion.research.chem.SmilesParser;
import com.actelion.research.chem.StereoMolecule;
import tech.molecules.structurized.OpenChemLibUtil;
import tech.molecules.structurized.ai.model.ChemOperationException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable address map around one stored OCL molecular graph snapshot.
 */
public final class MolecularSnapshot {
    private final StereoMolecule molecule;
    private final String[] atomIds;
    private final String[] bondIds;
    private final Map<String, Integer> atomIdToIndex;
    private final Map<String, Integer> bondIdToIndex;
    private final String[] atomComponentIds;
    private final List<ComponentSnapshot> components;
    private final String canonicalIdCode;
    private final String canonicalSmiles;

    private MolecularSnapshot(StereoMolecule molecule) {
        this.molecule = new StereoMolecule(molecule);
        this.molecule.ensureHelperArrays(Molecule.cHelperCIP);
        this.atomIds = new String[this.molecule.getAllAtoms()];
        this.bondIds = new String[this.molecule.getBonds()];
        this.atomIdToIndex = new HashMap<>();
        this.bondIdToIndex = new HashMap<>();
        for (int atom = 0; atom < atomIds.length; atom++) {
            atomIds[atom] = "a" + (atom + 1);
            atomIdToIndex.put(atomIds[atom], atom);
        }
        for (int bond = 0; bond < bondIds.length; bond++) {
            bondIds[bond] = "b" + (bond + 1);
            bondIdToIndex.put(bondIds[bond], bond);
        }
        this.components = computeComponents();
        this.atomComponentIds = new String[this.molecule.getAllAtoms()];
        for (ComponentSnapshot component : components) {
            for (int atom : component.atomIndices()) {
                atomComponentIds[atom] = component.componentId();
            }
        }
        this.canonicalIdCode = new Canonizer(this.molecule).getIDCode();
        this.canonicalSmiles = new IsomericSmilesCreator(this.molecule).getSmiles();
    }

    public static MolecularSnapshot fromSmiles(String smiles) {
        if (smiles == null || smiles.isBlank()) {
            throw new ChemOperationException("invalid_smiles", "SMILES must not be null or blank.");
        }
        StereoMolecule mol = new StereoMolecule();
        try {
            SmilesParser parser = new SmilesParser();
            parser.setRandomSeed(1L);
            parser.parse(mol, smiles);
            mol.ensureHelperArrays(Molecule.cHelperCIP);
            return new MolecularSnapshot(mol);
        } catch (Exception e) {
            throw new ChemOperationException("invalid_smiles", "Invalid SMILES: " + smiles, e);
        }
    }

    public StereoMolecule moleculeCopy() {
        return new StereoMolecule(molecule);
    }

    public StereoMolecule moleculeView() {
        return molecule;
    }

    public int atomCount() {
        return molecule.getAllAtoms();
    }

    public int bondCount() {
        return molecule.getBonds();
    }

    public String atomId(int atom) {
        return atomIds[atom];
    }

    public String bondId(int bond) {
        return bondIds[bond];
    }

    public int atomIndex(String atomId) {
        Integer index = atomIdToIndex.get(atomId);
        if (index == null) {
            throw new ChemOperationException("atom_not_found", "Atom " + atomId + " does not exist in this structure.");
        }
        return index;
    }

    public int bondIndex(String bondId) {
        Integer index = bondIdToIndex.get(bondId);
        if (index == null) {
            throw new ChemOperationException("bond_not_found", "Bond " + bondId + " does not exist in this structure.");
        }
        return index;
    }

    public String componentId(int atom) {
        return atomComponentIds[atom];
    }

    public List<ComponentSnapshot> components() {
        return components;
    }

    public String canonicalIdCode() {
        return canonicalIdCode;
    }

    public String canonicalSmiles() {
        return canonicalSmiles;
    }

    private List<ComponentSnapshot> computeComponents() {
        boolean[] seen = new boolean[molecule.getAllAtoms()];
        List<RawComponent> rawComponents = new ArrayList<>();
        for (int start = 0; start < molecule.getAllAtoms(); start++) {
            if (seen[start]) {
                continue;
            }
            BitSet atoms = new BitSet(molecule.getAllAtoms());
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(start);
            seen[start] = true;
            atoms.set(start);
            while (!queue.isEmpty()) {
                int atom = queue.removeFirst();
                for (int i = 0; i < molecule.getConnAtoms(atom); i++) {
                    int neighbor = molecule.getConnAtom(atom, i);
                    if (seen[neighbor]) {
                        continue;
                    }
                    seen[neighbor] = true;
                    atoms.set(neighbor);
                    queue.add(neighbor);
                }
            }
            rawComponents.add(new RawComponent(atoms, componentCanonicalIdCode(atoms)));
        }
        rawComponents.sort(Comparator
                .comparingInt((RawComponent component) -> -heavyAtomCount(component.atoms()))
                .thenComparingInt(component -> -component.atoms().cardinality())
                .thenComparing(RawComponent::canonicalIdCode));

        List<ComponentSnapshot> result = new ArrayList<>(rawComponents.size());
        for (int i = 0; i < rawComponents.size(); i++) {
            RawComponent raw = rawComponents.get(i);
            List<Integer> atoms = bitSetToList(raw.atoms());
            result.add(new ComponentSnapshot(
                    "c" + (i + 1),
                    atoms,
                    heavyAtomCount(raw.atoms()),
                    formalCharge(raw.atoms()),
                    raw.canonicalIdCode()
            ));
        }
        return List.copyOf(result);
    }

    private String componentCanonicalIdCode(BitSet atoms) {
        StereoMolecule fragment = new StereoMolecule();
        int[] oldToNew = new int[molecule.getAllAtoms()];
        Arrays.fill(oldToNew, -1);
        molecule.copyMoleculeByAtoms(fragment, bitSetToBooleanArray(atoms), true, oldToNew);
        fragment.setFragment(false);
        fragment.ensureHelperArrays(Molecule.cHelperCIP);
        return new Canonizer(fragment).getIDCode();
    }

    private int heavyAtomCount(BitSet atoms) {
        int count = 0;
        for (int atom = atoms.nextSetBit(0); atom >= 0; atom = atoms.nextSetBit(atom + 1)) {
            if (molecule.getAtomicNo(atom) > 1) {
                count++;
            }
        }
        return count;
    }

    private int formalCharge(BitSet atoms) {
        int charge = 0;
        for (int atom = atoms.nextSetBit(0); atom >= 0; atom = atoms.nextSetBit(atom + 1)) {
            charge += molecule.getAtomCharge(atom);
        }
        return charge;
    }

    private static List<Integer> bitSetToList(BitSet atoms) {
        List<Integer> result = new ArrayList<>();
        for (int atom = atoms.nextSetBit(0); atom >= 0; atom = atoms.nextSetBit(atom + 1)) {
            result.add(atom);
        }
        return List.copyOf(result);
    }

    private boolean[] bitSetToBooleanArray(BitSet atoms) {
        return OpenChemLibUtil.bitsetToBool(atoms, molecule.getAllAtoms());
    }

    private record RawComponent(BitSet atoms, String canonicalIdCode) {
        private RawComponent {
            Objects.requireNonNull(atoms, "atoms");
            Objects.requireNonNull(canonicalIdCode, "canonicalIdCode");
        }
    }
}
