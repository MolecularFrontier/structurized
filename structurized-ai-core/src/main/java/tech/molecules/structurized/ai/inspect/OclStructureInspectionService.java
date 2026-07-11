package tech.molecules.structurized.ai.inspect;

import com.actelion.research.chem.IsomericSmilesCreator;
import com.actelion.research.chem.Molecule;
import com.actelion.research.chem.StereoMolecule;
import tech.molecules.structurized.OpenChemLibUtil;
import tech.molecules.structurized.ai.model.AtomEnvironmentInspection;
import tech.molecules.structurized.ai.model.AtomInspection;
import tech.molecules.structurized.ai.model.AtomRef;
import tech.molecules.structurized.ai.model.BondCut;
import tech.molecules.structurized.ai.model.BondCutResult;
import tech.molecules.structurized.ai.model.BondInspection;
import tech.molecules.structurized.ai.model.CutBondsRequest;
import tech.molecules.structurized.ai.model.CutFragment;
import tech.molecules.structurized.ai.model.CutFragmentAttachment;
import tech.molecules.structurized.ai.model.BondRef;
import tech.molecules.structurized.ai.model.BoundaryAttachment;
import tech.molecules.structurized.ai.model.ChemOperationException;
import tech.molecules.structurized.ai.model.ComponentInspection;
import tech.molecules.structurized.ai.model.Coordinates2d;
import tech.molecules.structurized.ai.model.RingSystemAttachment;
import tech.molecules.structurized.ai.model.RingSystemInspection;
import tech.molecules.structurized.ai.model.ShortestPathResult;
import tech.molecules.structurized.ai.model.StructureInspection;
import tech.molecules.structurized.ai.model.StructureRef;
import tech.molecules.structurized.ai.ocl.ComponentSnapshot;
import tech.molecules.structurized.ai.ocl.MolecularSnapshot;
import tech.molecules.structurized.ai.repository.StoredStructure;
import tech.molecules.structurized.ai.repository.StructureRepositoryService;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public final class OclStructureInspectionService implements StructureInspectionService {
    private static final int MIN_ENVIRONMENT_RADIUS = 0;
    private static final int MAX_ENVIRONMENT_RADIUS = 4;
    private static final String RING_SYSTEM_ALGORITHM = "ring_atoms_connected_by_ring_bonds";

    private final StructureRepositoryService repositoryService;

    public OclStructureInspectionService(StructureRepositoryService repositoryService) {
        this.repositoryService = Objects.requireNonNull(repositoryService, "repositoryService");
    }

    @Override
    public StructureInspection inspectStructure(StructureRef structure) {
        StoredStructure stored = repositoryService.getStructure(structure);
        MolecularSnapshot snapshot = stored.snapshot();
        List<ComponentInspection> components = snapshot.components().stream()
                .map(component -> inspectComponent(snapshot, component))
                .toList();
        List<AtomInspection> atoms = new ArrayList<>(snapshot.atomCount());
        for (int atom = 0; atom < snapshot.atomCount(); atom++) {
            atoms.add(inspectAtom(snapshot, atom));
        }
        List<BondInspection> bonds = new ArrayList<>(snapshot.bondCount());
        for (int bond = 0; bond < snapshot.bondCount(); bond++) {
            bonds.add(inspectBond(snapshot, bond));
        }
        return new StructureInspection(stored.record(), components, List.copyOf(atoms), List.copyOf(bonds));
    }

    @Override
    public AtomInspection inspectAtom(AtomRef atom) {
        StoredStructure stored = repositoryService.getStructure(atom.structure());
        MolecularSnapshot snapshot = stored.snapshot();
        return inspectAtom(snapshot, snapshot.atomIndex(atom.atomId()));
    }

    @Override
    public BondInspection inspectBond(BondRef bond) {
        StoredStructure stored = repositoryService.getStructure(bond.structure());
        MolecularSnapshot snapshot = stored.snapshot();
        return inspectBond(snapshot, snapshot.bondIndex(bond.bondId()));
    }

    @Override
    public AtomEnvironmentInspection inspectAtomEnvironment(AtomRef atom, int radius) {
        if (radius < MIN_ENVIRONMENT_RADIUS || radius > MAX_ENVIRONMENT_RADIUS) {
            throw new ChemOperationException(
                    "invalid_radius",
                    "Atom environment radius must be between " + MIN_ENVIRONMENT_RADIUS + " and " + MAX_ENVIRONMENT_RADIUS + "."
            );
        }
        StoredStructure stored = repositoryService.getStructure(atom.structure());
        MolecularSnapshot snapshot = stored.snapshot();
        int center = snapshot.atomIndex(atom.atomId());
        StereoMolecule mol = snapshot.moleculeView();
        BitSet includedAtoms = atomsWithinRadius(mol, center, radius);
        BitSet includedBonds = inducedBonds(mol, includedAtoms);
        List<BoundaryCandidate> boundaries = boundaryCandidates(mol, includedAtoms, includedBonds);
        List<BoundaryAttachment> attachments = boundaryAttachments(snapshot, mol, boundaries);
        return new AtomEnvironmentInspection(
                snapshot.atomId(center),
                radius,
                atomIds(snapshot, includedAtoms),
                bondIds(snapshot, includedBonds),
                fragmentSmiles(snapshot, includedAtoms, boundaries),
                attachments
        );
    }

    @Override
    public RingSystemInspection inspectRingSystem(AtomRef atom) {
        StoredStructure stored = repositoryService.getStructure(atom.structure());
        MolecularSnapshot snapshot = stored.snapshot();
        int start = snapshot.atomIndex(atom.atomId());
        StereoMolecule mol = snapshot.moleculeView();
        if (!mol.isRingAtom(start)) {
            return new RingSystemInspection(
                    snapshot.atomId(start),
                    false,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    0,
                    0,
                    null,
                    RING_SYSTEM_ALGORITHM
            );
        }

        BitSet ringAtoms = ringSystemAtoms(mol, start);
        BitSet ringBonds = ringSystemBonds(mol, ringAtoms);
        List<BoundaryCandidate> boundaries = boundaryCandidates(mol, ringAtoms, ringBonds);
        List<RingSystemAttachment> attachments = boundaries.stream()
                .map(boundary -> new RingSystemAttachment(
                        snapshot.atomId(boundary.insideAtom()),
                        snapshot.atomId(boundary.outsideAtom()),
                        snapshot.bondId(boundary.bond())
                ))
                .toList();
        List<String> junctionAtoms = junctionAtoms(snapshot, mol, ringAtoms, ringBonds);
        Set<Integer> ringSizes = new TreeSet<>();
        int aromaticBondCount = 0;
        for (int bond = ringBonds.nextSetBit(0); bond >= 0; bond = ringBonds.nextSetBit(bond + 1)) {
            int ringSize = mol.getBondRingSize(bond);
            if (ringSize > 0) {
                ringSizes.add(ringSize);
            }
            if (mol.isAromaticBond(bond)) {
                aromaticBondCount++;
            }
        }
        int aromaticAtomCount = 0;
        for (int ringAtom = ringAtoms.nextSetBit(0); ringAtom >= 0; ringAtom = ringAtoms.nextSetBit(ringAtom + 1)) {
            if (mol.isAromaticAtom(ringAtom)) {
                aromaticAtomCount++;
            }
        }

        return new RingSystemInspection(
                snapshot.atomId(start),
                true,
                atomIds(snapshot, ringAtoms),
                bondIds(snapshot, ringBonds),
                junctionAtoms,
                attachments,
                List.copyOf(ringSizes),
                aromaticAtomCount,
                aromaticBondCount,
                fragmentSmiles(snapshot, ringAtoms, boundaries),
                RING_SYSTEM_ALGORITHM
        );
    }

    @Override
    public ShortestPathResult findShortestPath(AtomRef start, AtomRef end) {
        if (!start.structure().equals(end.structure())) {
            throw new ChemOperationException("structure_not_found", "Both atoms must refer to the same structure.");
        }
        StoredStructure stored = repositoryService.getStructure(start.structure());
        MolecularSnapshot snapshot = stored.snapshot();
        int startAtom = snapshot.atomIndex(start.atomId());
        int endAtom = snapshot.atomIndex(end.atomId());
        if (!Objects.equals(snapshot.componentId(startAtom), snapshot.componentId(endAtom))) {
            throw new ChemOperationException(
                    "atoms_not_connected",
                    "Atoms " + start.qualifiedId() + " and " + end.qualifiedId() + " are in different components."
            );
        }
        PathComputation path = shortestPath(snapshot.moleculeView(), startAtom, endAtom);
        List<String> atomPath = new ArrayList<>(path.atomPath().length);
        for (int atom : path.atomPath()) {
            atomPath.add(snapshot.atomId(atom));
        }
        List<String> bondPath = new ArrayList<>(path.bondPath().length);
        List<String> rotatableBonds = new ArrayList<>();
        for (int bond : path.bondPath()) {
            bondPath.add(snapshot.bondId(bond));
            StereoMolecule mol = snapshot.moleculeView();
            boolean aromatic = mol.isAromaticBond(bond);
            boolean delocalized = mol.isDelocalizedBond(bond) || mol.getBondType(bond) == Molecule.cBondTypeDelocalized;
            if (isRotatableCandidate(mol, bond, aromatic, delocalized)) {
                rotatableBonds.add(snapshot.bondId(bond));
            }
        }
        return new ShortestPathResult(
                snapshot.atomId(startAtom),
                snapshot.atomId(endAtom),
                path.atomPath().length - 1,
                List.copyOf(atomPath),
                List.copyOf(bondPath),
                ringSystemTransitions(snapshot.moleculeView(), path.atomPath()),
                List.copyOf(rotatableBonds),
                path.alternativeShortestPathsExist()
        );
    }

    @Override
    public BondCutResult cutBonds(CutBondsRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.bondIds().isEmpty()) {
            throw new ChemOperationException("invalid_cut_request", "At least one bond ID is required.");
        }
        StoredStructure stored = repositoryService.getStructure(request.structure());
        MolecularSnapshot snapshot = stored.snapshot();
        StereoMolecule mol = snapshot.moleculeView();

        List<Integer> cutBondIndices = new ArrayList<>();
        Set<Integer> seenBonds = new HashSet<>();
        for (String bondId : request.bondIds()) {
            int bond = snapshot.bondIndex(bondId);
            if (!seenBonds.add(bond)) {
                throw new ChemOperationException("duplicate_bond_id", "Bond " + bondId + " was supplied more than once.");
            }
            cutBondIndices.add(bond);
        }
        cutBondIndices.sort(Integer::compareTo);

        BitSet cutBondSet = new BitSet(mol.getBonds());
        for (int bond : cutBondIndices) {
            cutBondSet.set(bond);
        }

        int originalComponentCount = snapshot.components().size();
        List<String> warnings = new ArrayList<>();
        if (originalComponentCount > 1) {
            warnings.add("Parent structure contains pre-existing disconnected components.");
        }

        List<BondCut> cuts = new ArrayList<>(cutBondIndices.size());
        for (int i = 0; i < cutBondIndices.size(); i++) {
            int bond = cutBondIndices.get(i);
            int attachmentId = i + 1;
            int a1 = mol.getBondAtom(0, bond);
            int a2 = mol.getBondAtom(1, bond);
            boolean aromatic = mol.isAromaticBond(bond);
            boolean delocalized = mol.isDelocalizedBond(bond) || mol.getBondType(bond) == Molecule.cBondTypeDelocalized;
            cuts.add(new BondCut(
                    snapshot.bondId(bond),
                    attachmentId,
                    snapshot.atomId(a1),
                    snapshot.atomId(a2),
                    mol.getBondOrder(bond),
                    mol.isRingBond(bond),
                    aromatic
            ));
            BitSet singleCut = new BitSet(mol.getBonds());
            singleCut.set(bond);
            if (mol.isRingBond(bond) && connectedComponentsAfterCuts(mol, singleCut).size() <= originalComponentCount) {
                warnings.add("Cut bond " + snapshot.bondId(bond) + " is a ring bond and did not disconnect the graph by itself.");
            }
            if (aromatic || delocalized) {
                warnings.add("Cut bond " + snapshot.bondId(bond) + " is aromatic or delocalized; fragment SMILES may require special interpretation.");
            }
        }

        List<BitSet> components = connectedComponentsAfterCuts(mol, cutBondSet);
        if (components.size() <= originalComponentCount) {
            warnings.add("Requested cuts did not increase connected component count.");
        }

        List<CutFragment> fragments = cutFragments(snapshot, components, cutBondIndices, cutBondSet);
        return new BondCutResult(
                request.structure(),
                List.copyOf(cuts),
                fragments,
                List.copyOf(warnings)
        );
    }

    private ComponentInspection inspectComponent(MolecularSnapshot snapshot, ComponentSnapshot component) {
        List<String> atomIds = component.atomIndices().stream()
                .map(snapshot::atomId)
                .toList();
        return new ComponentInspection(
                component.componentId(),
                component.heavyAtomCount(),
                component.atomIndices().size(),
                atomIds,
                component.formalCharge(),
                component.canonicalIdCode()
        );
    }

    private AtomInspection inspectAtom(MolecularSnapshot snapshot, int atom) {
        StereoMolecule mol = snapshot.moleculeView();
        List<String> neighborAtoms = new ArrayList<>();
        List<String> incidentBonds = new ArrayList<>();
        int heavyAtomDegree = 0;
        for (int i = 0; i < mol.getConnAtoms(atom); i++) {
            int neighbor = mol.getConnAtom(atom, i);
            int bond = mol.getConnBond(atom, i);
            neighborAtoms.add(snapshot.atomId(neighbor));
            incidentBonds.add(snapshot.bondId(bond));
            if (mol.getAtomicNo(neighbor) > 1) {
                heavyAtomDegree++;
            }
        }
        int ringSize = mol.getAtomRingSize(atom);
        int isotopeMass = mol.getAtomMass(atom);
        return new AtomInspection(
                snapshot.atomId(atom),
                mol.getAtomLabel(atom),
                mol.getAtomicNo(atom),
                isotopeMass == 0 ? null : isotopeMass,
                mol.getAtomCharge(atom),
                mol.getImplicitHydrogens(atom),
                mol.getAllHydrogens(atom),
                heavyAtomDegree,
                mol.getOccupiedValence(atom),
                mol.isAromaticAtom(atom),
                mol.isRingAtom(atom),
                ringSize == 0 ? null : ringSize,
                snapshot.componentId(atom),
                List.copyOf(neighborAtoms),
                List.copyOf(incidentBonds),
                atomStereo(mol, atom),
                new Coordinates2d(round(mol.getAtomX(atom)), round(mol.getAtomY(atom)))
        );
    }

    private BondInspection inspectBond(MolecularSnapshot snapshot, int bond) {
        StereoMolecule mol = snapshot.moleculeView();
        int a1 = mol.getBondAtom(0, bond);
        int a2 = mol.getBondAtom(1, bond);
        int ringSize = mol.getBondRingSize(bond);
        boolean aromatic = mol.isAromaticBond(bond);
        boolean delocalized = mol.isDelocalizedBond(bond) || mol.getBondType(bond) == Molecule.cBondTypeDelocalized;
        return new BondInspection(
                snapshot.bondId(bond),
                snapshot.atomId(a1),
                snapshot.atomId(a2),
                mol.getBondOrder(bond),
                mol.getBondType(bond),
                aromatic,
                delocalized,
                mol.isRingBond(bond),
                ringSize == 0 ? null : ringSize,
                bondStereo(mol, bond),
                isRotatableCandidate(mol, bond, aromatic, delocalized)
        );
    }

    private static BitSet atomsWithinRadius(StereoMolecule mol, int center, int radius) {
        int[] distance = new int[mol.getAllAtoms()];
        Arrays.fill(distance, -1);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(center);
        distance[center] = 0;
        while (!queue.isEmpty()) {
            int atom = queue.removeFirst();
            if (distance[atom] == radius) {
                continue;
            }
            for (int i = 0; i < mol.getConnAtoms(atom); i++) {
                int neighbor = mol.getConnAtom(atom, i);
                if (distance[neighbor] >= 0) {
                    continue;
                }
                distance[neighbor] = distance[atom] + 1;
                queue.add(neighbor);
            }
        }
        BitSet atoms = new BitSet(mol.getAllAtoms());
        for (int atom = 0; atom < distance.length; atom++) {
            if (distance[atom] >= 0 && distance[atom] <= radius) {
                atoms.set(atom);
            }
        }
        return atoms;
    }

    private static BitSet inducedBonds(StereoMolecule mol, BitSet atoms) {
        BitSet bonds = new BitSet(mol.getBonds());
        for (int bond = 0; bond < mol.getBonds(); bond++) {
            if (atoms.get(mol.getBondAtom(0, bond)) && atoms.get(mol.getBondAtom(1, bond))) {
                bonds.set(bond);
            }
        }
        return bonds;
    }

    private static BitSet ringSystemAtoms(StereoMolecule mol, int start) {
        BitSet atoms = new BitSet(mol.getAllAtoms());
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        atoms.set(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            int atom = queue.removeFirst();
            for (int i = 0; i < mol.getConnAtoms(atom); i++) {
                int bond = mol.getConnBond(atom, i);
                if (!mol.isRingBond(bond)) {
                    continue;
                }
                int neighbor = mol.getConnAtom(atom, i);
                if (!mol.isRingAtom(neighbor) || atoms.get(neighbor)) {
                    continue;
                }
                atoms.set(neighbor);
                queue.add(neighbor);
            }
        }
        return atoms;
    }

    private static BitSet ringSystemBonds(StereoMolecule mol, BitSet ringAtoms) {
        BitSet bonds = new BitSet(mol.getBonds());
        for (int bond = 0; bond < mol.getBonds(); bond++) {
            if (mol.isRingBond(bond)
                    && ringAtoms.get(mol.getBondAtom(0, bond))
                    && ringAtoms.get(mol.getBondAtom(1, bond))) {
                bonds.set(bond);
            }
        }
        return bonds;
    }

    private static List<BoundaryCandidate> boundaryCandidates(StereoMolecule mol, BitSet includedAtoms, BitSet includedBonds) {
        List<BoundaryCandidate> boundaries = new ArrayList<>();
        for (int bond = 0; bond < mol.getBonds(); bond++) {
            if (includedBonds.get(bond)) {
                continue;
            }
            int a1 = mol.getBondAtom(0, bond);
            int a2 = mol.getBondAtom(1, bond);
            boolean include1 = includedAtoms.get(a1);
            boolean include2 = includedAtoms.get(a2);
            if (include1 == include2) {
                continue;
            }
            boundaries.add(include1
                    ? new BoundaryCandidate(bond, a1, a2)
                    : new BoundaryCandidate(bond, a2, a1));
        }
        boundaries.sort(Comparator.comparingInt(BoundaryCandidate::bond));
        return List.copyOf(boundaries);
    }

    private static List<BoundaryAttachment> boundaryAttachments(
            MolecularSnapshot snapshot,
            StereoMolecule mol,
            List<BoundaryCandidate> boundaries
    ) {
        List<BoundaryAttachment> attachments = new ArrayList<>(boundaries.size());
        for (int i = 0; i < boundaries.size(); i++) {
            BoundaryCandidate boundary = boundaries.get(i);
            attachments.add(new BoundaryAttachment(
                    i + 1,
                    snapshot.atomId(boundary.insideAtom()),
                    snapshot.atomId(boundary.outsideAtom()),
                    snapshot.bondId(boundary.bond()),
                    mol.getBondOrder(boundary.bond())
            ));
        }
        return List.copyOf(attachments);
    }

    private static List<String> junctionAtoms(MolecularSnapshot snapshot, StereoMolecule mol, BitSet ringAtoms, BitSet ringBonds) {
        List<String> junctions = new ArrayList<>();
        for (int atom = ringAtoms.nextSetBit(0); atom >= 0; atom = ringAtoms.nextSetBit(atom + 1)) {
            int ringBondDegree = 0;
            for (int i = 0; i < mol.getConnAtoms(atom); i++) {
                if (ringBonds.get(mol.getConnBond(atom, i))) {
                    ringBondDegree++;
                }
            }
            if (ringBondDegree > 2) {
                junctions.add(snapshot.atomId(atom));
            }
        }
        return List.copyOf(junctions);
    }

    private static List<String> atomIds(MolecularSnapshot snapshot, BitSet atoms) {
        List<String> ids = new ArrayList<>();
        for (int atom = atoms.nextSetBit(0); atom >= 0; atom = atoms.nextSetBit(atom + 1)) {
            ids.add(snapshot.atomId(atom));
        }
        return List.copyOf(ids);
    }

    private static List<String> bondIds(MolecularSnapshot snapshot, BitSet bonds) {
        List<String> ids = new ArrayList<>();
        for (int bond = bonds.nextSetBit(0); bond >= 0; bond = bonds.nextSetBit(bond + 1)) {
            ids.add(snapshot.bondId(bond));
        }
        return List.copyOf(ids);
    }

    private static String fragmentSmiles(MolecularSnapshot snapshot, BitSet includedAtoms, List<BoundaryCandidate> boundaries) {
        List<FragmentAttachmentCandidate> attachments = new ArrayList<>(boundaries.size());
        for (int i = 0; i < boundaries.size(); i++) {
            BoundaryCandidate boundary = boundaries.get(i);
            attachments.add(new FragmentAttachmentCandidate(
                    boundary.bond(),
                    boundary.insideAtom(),
                    boundary.outsideAtom(),
                    i + 1
            ));
        }
        return fragmentSmilesWithAttachments(snapshot, includedAtoms, attachments);
    }

    private static String fragmentSmilesWithAttachments(
            MolecularSnapshot snapshot,
            BitSet includedAtoms,
            List<FragmentAttachmentCandidate> attachments
    ) {
        StereoMolecule mol = snapshot.moleculeView();
        StereoMolecule fragment = new StereoMolecule();
        int[] oldToNew = new int[mol.getAllAtoms()];
        Arrays.fill(oldToNew, -1);
        mol.copyMoleculeByAtoms(fragment, OpenChemLibUtil.bitsetToBool(includedAtoms, mol.getAllAtoms()), true, oldToNew);
        for (FragmentAttachmentCandidate attachment : attachments) {
            int insideAtom = oldToNew[attachment.insideAtom()];
            if (insideAtom < 0) {
                continue;
            }
            int dummy = fragment.addAtom(0);
            fragment.setAtomMapNo(dummy, attachment.attachmentId(), false);
            fragment.addBond(insideAtom, dummy, mol.getBondType(attachment.bond()));
        }
        fragment.ensureHelperArrays(Molecule.cHelperCIP);
        return new IsomericSmilesCreator(fragment, IsomericSmilesCreator.MODE_INCLUDE_MAPPING).getSmiles();
    }

    private static List<CutFragment> cutFragments(
            MolecularSnapshot snapshot,
            List<BitSet> components,
            List<Integer> cutBondIndices,
            BitSet cutBondSet
    ) {
        StereoMolecule mol = snapshot.moleculeView();
        List<CutFragment> fragments = new ArrayList<>(components.size());
        for (int i = 0; i < components.size(); i++) {
            BitSet componentAtoms = components.get(i);
            BitSet componentBonds = new BitSet(mol.getBonds());
            for (int bond = 0; bond < mol.getBonds(); bond++) {
                if (!cutBondSet.get(bond)
                        && componentAtoms.get(mol.getBondAtom(0, bond))
                        && componentAtoms.get(mol.getBondAtom(1, bond))) {
                    componentBonds.set(bond);
                }
            }
            List<FragmentAttachmentCandidate> attachmentCandidates = new ArrayList<>();
            List<CutFragmentAttachment> attachments = new ArrayList<>();
            for (int cutIndex = 0; cutIndex < cutBondIndices.size(); cutIndex++) {
                int bond = cutBondIndices.get(cutIndex);
                int attachmentId = cutIndex + 1;
                int a1 = mol.getBondAtom(0, bond);
                int a2 = mol.getBondAtom(1, bond);
                if (componentAtoms.get(a1)) {
                    attachmentCandidates.add(new FragmentAttachmentCandidate(bond, a1, a2, attachmentId));
                    attachments.add(new CutFragmentAttachment(
                            attachmentId,
                            snapshot.atomId(a1),
                            snapshot.atomId(a2),
                            snapshot.bondId(bond)
                    ));
                }
                if (componentAtoms.get(a2)) {
                    attachmentCandidates.add(new FragmentAttachmentCandidate(bond, a2, a1, attachmentId));
                    attachments.add(new CutFragmentAttachment(
                            attachmentId,
                            snapshot.atomId(a2),
                            snapshot.atomId(a1),
                            snapshot.bondId(bond)
                    ));
                }
            }
            fragments.add(new CutFragment(
                    "f" + (i + 1),
                    atomIds(snapshot, componentAtoms),
                    bondIds(snapshot, componentBonds),
                    fragmentSmilesWithAttachments(snapshot, componentAtoms, attachmentCandidates),
                    List.copyOf(attachments)
            ));
        }
        return List.copyOf(fragments);
    }

    private static List<BitSet> connectedComponentsAfterCuts(StereoMolecule mol, BitSet cutBonds) {
        boolean[] seen = new boolean[mol.getAllAtoms()];
        List<BitSet> components = new ArrayList<>();
        for (int start = 0; start < mol.getAllAtoms(); start++) {
            if (seen[start]) {
                continue;
            }
            BitSet component = new BitSet(mol.getAllAtoms());
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(start);
            seen[start] = true;
            component.set(start);
            while (!queue.isEmpty()) {
                int atom = queue.removeFirst();
                for (int i = 0; i < mol.getConnAtoms(atom); i++) {
                    int bond = mol.getConnBond(atom, i);
                    if (cutBonds.get(bond)) {
                        continue;
                    }
                    int neighbor = mol.getConnAtom(atom, i);
                    if (seen[neighbor]) {
                        continue;
                    }
                    seen[neighbor] = true;
                    component.set(neighbor);
                    queue.add(neighbor);
                }
            }
            components.add(component);
        }
        components.sort(Comparator
                .comparingInt((BitSet component) -> -heavyAtomCount(mol, component))
                .thenComparingInt(component -> -component.cardinality())
                .thenComparingInt(component -> component.nextSetBit(0)));
        return List.copyOf(components);
    }

    private static int heavyAtomCount(StereoMolecule mol, BitSet atoms) {
        int count = 0;
        for (int atom = atoms.nextSetBit(0); atom >= 0; atom = atoms.nextSetBit(atom + 1)) {
            if (mol.getAtomicNo(atom) > 1) {
                count++;
            }
        }
        return count;
    }

    private static PathComputation shortestPath(StereoMolecule mol, int startAtom, int endAtom) {
        if (startAtom == endAtom) {
            return new PathComputation(new int[]{startAtom}, new int[0], false);
        }
        int[] distance = new int[mol.getAllAtoms()];
        int[] pathCounts = new int[mol.getAllAtoms()];
        int[] previousAtom = new int[mol.getAllAtoms()];
        int[] previousBond = new int[mol.getAllAtoms()];
        Arrays.fill(distance, -1);
        Arrays.fill(previousAtom, -1);
        Arrays.fill(previousBond, -1);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        distance[startAtom] = 0;
        pathCounts[startAtom] = 1;
        queue.add(startAtom);
        while (!queue.isEmpty()) {
            int atom = queue.removeFirst();
            if (distance[endAtom] >= 0 && distance[atom] >= distance[endAtom]) {
                continue;
            }
            for (int i = 0; i < mol.getConnAtoms(atom); i++) {
                int neighbor = mol.getConnAtom(atom, i);
                int bond = mol.getConnBond(atom, i);
                int nextDistance = distance[atom] + 1;
                if (distance[neighbor] == -1) {
                    distance[neighbor] = nextDistance;
                    previousAtom[neighbor] = atom;
                    previousBond[neighbor] = bond;
                    pathCounts[neighbor] = pathCounts[atom];
                    queue.add(neighbor);
                } else if (distance[neighbor] == nextDistance) {
                    pathCounts[neighbor] = Math.min(2, pathCounts[neighbor] + pathCounts[atom]);
                }
            }
        }
        if (distance[endAtom] < 0) {
            throw new ChemOperationException("atoms_not_connected", "Atoms are not connected.");
        }
        int[] atomPath = new int[distance[endAtom] + 1];
        int[] bondPath = new int[distance[endAtom]];
        int cursor = endAtom;
        for (int i = atomPath.length - 1; i >= 0; i--) {
            atomPath[i] = cursor;
            if (i > 0) {
                bondPath[i - 1] = previousBond[cursor];
                cursor = previousAtom[cursor];
            }
        }
        return new PathComputation(atomPath, bondPath, pathCounts[endAtom] > 1);
    }

    private static int ringSystemTransitions(StereoMolecule mol, int[] atomPath) {
        int transitions = 0;
        String previous = ringSystemKey(mol, atomPath[0]);
        for (int i = 1; i < atomPath.length; i++) {
            String current = ringSystemKey(mol, atomPath[i]);
            if (!Objects.equals(previous, current) && (!"-".equals(previous) || !"-".equals(current))) {
                transitions++;
            }
            previous = current;
        }
        return transitions;
    }

    private static String ringSystemKey(StereoMolecule mol, int atom) {
        if (!mol.isRingAtom(atom)) {
            return "-";
        }
        BitSet atoms = ringSystemAtoms(mol, atom);
        return Integer.toString(atoms.nextSetBit(0));
    }

    private static boolean isRotatableCandidate(StereoMolecule mol, int bond, boolean aromatic, boolean delocalized) {
        int a1 = mol.getBondAtom(0, bond);
        int a2 = mol.getBondAtom(1, bond);
        return mol.getBondOrder(bond) == 1
                && !aromatic
                && !delocalized
                && !mol.isRingBond(bond)
                && mol.getAtomicNo(a1) > 1
                && mol.getAtomicNo(a2) > 1
                && mol.getConnAtoms(a1) > 1
                && mol.getConnAtoms(a2) > 1;
    }

    private static String atomStereo(StereoMolecule mol, int atom) {
        int parity = mol.getAtomParity(atom);
        return parity == Molecule.cAtomParityNone ? null : Integer.toString(parity);
    }

    private static String bondStereo(StereoMolecule mol, int bond) {
        int parity = mol.getBondParity(bond);
        return parity == Molecule.cBondParityNone ? null : Integer.toString(parity);
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private record BoundaryCandidate(int bond, int insideAtom, int outsideAtom) {}

    private record FragmentAttachmentCandidate(int bond, int insideAtom, int outsideAtom, int attachmentId) {}

    private record PathComputation(int[] atomPath, int[] bondPath, boolean alternativeShortestPathsExist) {}
}
