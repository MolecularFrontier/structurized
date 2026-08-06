package tech.molecules.structurized.mmp;

import com.actelion.research.chem.Canonizer;
import com.actelion.research.chem.Molecule;
import com.actelion.research.chem.StereoMolecule;
import tech.molecules.structurized.OpenChemLibUtil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Enumerates canonical 1-cut and 2-cut MMP fragmentations for one molecule.
 */
public final class MmpFragmenter {
    private MmpFragmenter() {}

    public static List<MmpFragmentationRecord> fragment(MmpInputCompound input, MmpMiningConfig config) {
        return fragmentWithMapping(input, config).stream()
                .map(MmpFragmentationMatch::record)
                .toList();
    }

    /**
     * Enumerates canonical fragmentations and retains their atom/bond mapping to the input molecule.
     */
    public static List<MmpFragmentationMatch> fragmentWithMapping(MmpInputCompound input, MmpMiningConfig config) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(config, "config");

        StereoMolecule mol = new StereoMolecule(input.molecule());
        mol.ensureHelperArrays(Molecule.cHelperRings);

        int totalHeavyAtoms = OpenChemLibUtil.heavyAtomCount(mol);
        List<Integer> candidateBonds = candidateCutBonds(mol, config);
        Map<String, MmpFragmentationMatch> matches = new LinkedHashMap<>();

        for (int bond : candidateBonds) {
            addRecordsForCutSet(input.compoundId(), mol, config, totalHeavyAtoms, new int[]{bond}, matches);
            if (matches.size() >= config.maxFragmentationRecordsPerCompound()) {
                return List.copyOf(matches.values());
            }
        }

        if (config.maxCuts() >= 2) {
            for (int i = 0; i < candidateBonds.size(); i++) {
                for (int j = i + 1; j < candidateBonds.size(); j++) {
                    int b1 = candidateBonds.get(i);
                    int b2 = candidateBonds.get(j);
                    if (!isAllowedTwoCutSet(mol, config, b1, b2)) {
                        continue;
                    }
                    addRecordsForCutSet(input.compoundId(), mol, config, totalHeavyAtoms, new int[]{b1, b2}, matches);
                    if (matches.size() >= config.maxFragmentationRecordsPerCompound()) {
                        return List.copyOf(matches.values());
                    }
                }
            }
        }

        return List.copyOf(matches.values());
    }

    static List<Integer> candidateCutBonds(StereoMolecule mol, MmpMiningConfig config) {
        boolean[] noCutBond = new boolean[mol.getBonds()];
        for (NoCutBondRule rule : config.noCutBondRules()) {
            rule.markNoCutBonds(mol, noCutBond);
        }

        List<Integer> bonds = new ArrayList<>();
        for (int bond = 0; bond < mol.getBonds(); bond++) {
            if (noCutBond[bond]) {
                continue;
            }
            if (!isBasicEligibleBond(mol, config, bond)) {
                continue;
            }
            if (mol.isRingBond(bond)) {
                if (!config.allowMacrocycleRingCuts() || mol.getBondRingSize(bond) < config.macrocycleMinRingSize()) {
                    continue;
                }
            }
            bonds.add(bond);
        }
        return bonds;
    }

    private static boolean isBasicEligibleBond(StereoMolecule mol, MmpMiningConfig config, int bond) {
        int a1 = mol.getBondAtom(0, bond);
        int a2 = mol.getBondAtom(1, bond);
        if (mol.getAtomicNo(a1) <= 1 || mol.getAtomicNo(a2) <= 1) {
            return false;
        }
        if (config.singleBondsOnly() && mol.getBondOrder(bond) != 1) {
            return false;
        }
        return !mol.isAromaticBond(bond);
    }

    private static boolean isAllowedTwoCutSet(StereoMolecule mol, MmpMiningConfig config, int b1, int b2) {
        boolean r1 = mol.isRingBond(b1);
        boolean r2 = mol.isRingBond(b2);
        if (r1 != r2 && !config.allowMixedRingChainCutSets()) {
            return false;
        }
        if (r1 && r2) {
            return config.allowMacrocycleRingCuts()
                    && mol.getBondRingSize(b1) >= config.macrocycleMinRingSize()
                    && mol.getBondRingSize(b2) >= config.macrocycleMinRingSize();
        }
        return true;
    }

    private static void addRecordsForCutSet(
            String compoundId,
            StereoMolecule mol,
            MmpMiningConfig config,
            int totalHeavyAtoms,
            int[] cutBonds,
            Map<String, MmpFragmentationMatch> matches
    ) {
        Arrays.sort(cutBonds);
        List<BitSet> components = connectedComponentsAfterCuts(mol, cutBonds);
        boolean hasRingCut = Arrays.stream(cutBonds).anyMatch(mol::isRingBond);
        if (cutBonds.length == 1 && components.size() != 2) {
            return;
        }
        if (cutBonds.length == 2 && !hasRingCut && components.size() != 3) {
            return;
        }
        if (cutBonds.length == 2 && hasRingCut && components.size() != 2) {
            return;
        }

        for (BitSet valueAtoms : components) {
            BitSet keyAtoms = allAtoms(mol);
            keyAtoms.andNot(valueAtoms);
            if (!allCutsCrossPartition(mol, valueAtoms, cutBonds)) {
                continue;
            }
            int valueHeavyAtoms = heavyAtomCount(mol, valueAtoms);
            int keyHeavyAtoms = heavyAtomCount(mol, keyAtoms);
            if (!passesSizeFilters(config, totalHeavyAtoms, keyHeavyAtoms, valueHeavyAtoms)) {
                continue;
            }

            CanonicalRecord canonical = canonicalize(mol, keyAtoms, valueAtoms, cutBonds);
            MmpFragmentationRecord record = new MmpFragmentationRecord(
                    compoundId,
                    cutBonds.length,
                    canonical.keyIdcode(),
                    canonical.valueIdcode(),
                    keyHeavyAtoms,
                    valueHeavyAtoms,
                    Arrays.stream(cutBonds).boxed().toList(),
                    null
            );
            MmpFragmentationMatch match = new MmpFragmentationMatch(
                    record,
                    atomIndices(keyAtoms),
                    atomIndices(valueAtoms),
                    attachments(mol, keyAtoms, valueAtoms, cutBonds, canonical.labels())
            );
            matches.putIfAbsent(record.canonicalRecordId(), match);
        }
    }

    private static boolean allCutsCrossPartition(StereoMolecule mol, BitSet valueAtoms, int[] cutBonds) {
        for (int cutBond : cutBonds) {
            int a1 = mol.getBondAtom(0, cutBond);
            int a2 = mol.getBondAtom(1, cutBond);
            if (valueAtoms.get(a1) == valueAtoms.get(a2)) {
                return false;
            }
        }
        return true;
    }

    private static List<Integer> atomIndices(BitSet atoms) {
        return atoms.stream().boxed().toList();
    }

    private static List<MmpAttachment> attachments(
            StereoMolecule mol,
            BitSet keyAtoms,
            BitSet valueAtoms,
            int[] cutBonds,
            int[] labels
    ) {
        List<MmpAttachment> attachments = new ArrayList<>(cutBonds.length);
        for (int i = 0; i < cutBonds.length; i++) {
            int bond = cutBonds[i];
            int a1 = mol.getBondAtom(0, bond);
            int a2 = mol.getBondAtom(1, bond);
            int keyAtom = keyAtoms.get(a1) ? a1 : a2;
            int valueAtom = valueAtoms.get(a1) ? a1 : a2;
            attachments.add(new MmpAttachment(labels[i], bond, keyAtom, valueAtom, mol.getBondType(bond)));
        }
        attachments.sort(Comparator.comparingInt(MmpAttachment::label));
        return List.copyOf(attachments);
    }

    private static boolean passesSizeFilters(MmpMiningConfig config, int totalHeavyAtoms, int keyHeavyAtoms, int valueHeavyAtoms) {
        if (keyHeavyAtoms < config.minKeyHeavyAtoms()) {
            return false;
        }
        if (valueHeavyAtoms < config.minVariableHeavyAtoms() || valueHeavyAtoms > config.maxVariableHeavyAtoms()) {
            return false;
        }
        return valueHeavyAtoms <= totalHeavyAtoms * config.maxVariableToMolHeavyAtomFraction();
    }

    private static List<BitSet> connectedComponentsAfterCuts(StereoMolecule mol, int[] cutBonds) {
        boolean[] isCutBond = new boolean[mol.getBonds()];
        for (int bond : cutBonds) {
            isCutBond[bond] = true;
        }

        boolean[] seen = new boolean[mol.getAllAtoms()];
        List<BitSet> components = new ArrayList<>();
        for (int start = 0; start < mol.getAtoms(); start++) {
            if (mol.getAtomicNo(start) <= 1 || seen[start]) {
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
                    if (isCutBond[bond]) {
                        continue;
                    }
                    int neighbor = mol.getConnAtom(atom, i);
                    if (mol.getAtomicNo(neighbor) <= 1 || seen[neighbor]) {
                        continue;
                    }
                    seen[neighbor] = true;
                    component.set(neighbor);
                    queue.add(neighbor);
                }
            }
            components.add(component);
        }
        components.sort(Comparator.comparing(MmpFragmenter::bitSetKey));
        return components;
    }

    private static CanonicalRecord canonicalize(StereoMolecule mol, BitSet keyAtoms, BitSet valueAtoms, int[] cutBonds) {
        List<int[]> labelPermutations = labelPermutations(cutBonds.length);
        CanonicalRecord best = null;
        for (int[] labels : labelPermutations) {
            String keyIdcode = canonicalFragmentIdcode(mol, keyAtoms, cutBonds, labels);
            String valueIdcode = canonicalFragmentIdcode(mol, valueAtoms, cutBonds, labels);
            CanonicalRecord candidate = new CanonicalRecord(keyIdcode, valueIdcode, labels.clone());
            if (best == null || candidate.compareTo(best) < 0) {
                best = candidate;
            }
        }
        return best;
    }

    private static String canonicalFragmentIdcode(StereoMolecule mol, BitSet includeAtoms, int[] cutBonds, int[] labels) {
        StereoMolecule fragment = new StereoMolecule();
        boolean[] include = new boolean[mol.getAllAtoms()];
        for (int atom = includeAtoms.nextSetBit(0); atom >= 0; atom = includeAtoms.nextSetBit(atom + 1)) {
            include[atom] = true;
        }
        int[] oldToNew = new int[mol.getAllAtoms()];
        Arrays.fill(oldToNew, -1);
        mol.copyMoleculeByAtoms(fragment, include, true, oldToNew);

        for (int i = 0; i < cutBonds.length; i++) {
            int bond = cutBonds[i];
            int a1 = mol.getBondAtom(0, bond);
            int a2 = mol.getBondAtom(1, bond);
            if (includeAtoms.get(a1) == includeAtoms.get(a2)) {
                continue;
            }
            int includedAtom = includeAtoms.get(a1) ? a1 : a2;
            int mapped = oldToNew[includedAtom];
            if (mapped < 0) {
                continue;
            }
            int dummy = fragment.addAtom(0);
            fragment.setAtomCustomLabel(dummy, "R" + labels[i]);
            fragment.addBond(mapped, dummy, mol.getBondType(bond));
        }

        fragment.ensureHelperArrays(Molecule.cHelperRings);
        return new Canonizer(fragment, Canonizer.ENCODE_ATOM_CUSTOM_LABELS).getIDCode();
    }

    private static List<int[]> labelPermutations(int cutCount) {
        if (cutCount == 1) {
            return List.of(new int[]{1});
        }
        return List.of(new int[]{1, 2}, new int[]{2, 1});
    }

    private static BitSet allAtoms(StereoMolecule mol) {
        BitSet atoms = new BitSet(mol.getAllAtoms());
        for (int atom = 0; atom < mol.getAtoms(); atom++) {
            if (mol.getAtomicNo(atom) > 1) {
                atoms.set(atom);
            }
        }
        return atoms;
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

    private static String bitSetKey(BitSet bitSet) {
        StringBuilder sb = new StringBuilder();
        for (int atom = bitSet.nextSetBit(0); atom >= 0; atom = bitSet.nextSetBit(atom + 1)) {
            if (!sb.isEmpty()) {
                sb.append(',');
            }
            sb.append(atom);
        }
        return sb.toString();
    }

    private record CanonicalRecord(String keyIdcode, String valueIdcode, int[] labels) implements Comparable<CanonicalRecord> {
        @Override
        public int compareTo(CanonicalRecord other) {
            int keyCmp = keyIdcode.compareTo(other.keyIdcode);
            if (keyCmp != 0) {
                return keyCmp;
            }
            return valueIdcode.compareTo(other.valueIdcode);
        }
    }
}
