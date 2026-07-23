package tech.molecules.structurized.decomposition;

import com.actelion.research.chem.Molecule;
import com.actelion.research.chem.SSSearcher;
import com.actelion.research.chem.SmilesParser;
import com.actelion.research.chem.StereoMolecule;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Deterministic recursive decomposition engine.
 */
public final class DecompositionEngine {
    private final DecompositionConfig config;

    public DecompositionEngine(DecompositionConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public static DecompositionResult evaluate(
            DecompositionConfig config,
            String moleculeId,
            StereoMolecule molecule
    ) {
        return new DecompositionEngine(config).evaluate(moleculeId, molecule);
    }

    public DecompositionResult evaluate(String moleculeId, StereoMolecule molecule) {
        Objects.requireNonNull(molecule, "molecule");
        StereoMolecule prepared = new StereoMolecule(molecule);
        prepared.ensureHelperArrays(Molecule.cHelperRings);

        BitSet rootAtoms = new BitSet(prepared.getAtoms());
        rootAtoms.set(0, prepared.getAtoms());
        DecompositionNode root = expandNode(
                prepared,
                "root",
                null,
                rootAtoms,
                List.of(),
                List.of()
        );
        return new DecompositionResult(moleculeId, config.version(), root);
    }

    private DecompositionNode expandNode(
            StereoMolecule molecule,
            String path,
            String label,
            BitSet atoms,
            List<DecompositionBoundaryBond> boundaryBonds,
            List<String> ruleHistory
    ) {
        List<RuleAttempt> attempts = new ArrayList<>();
        for (DecompositionRule rule : config.rules()) {
            if (rule == null || !rule.isEnabled() || !Objects.equals(rule.labelToSplit(), label)) {
                continue;
            }

            RuleApplication application = applyRule(molecule, atoms, rule);
            attempts.add(new RuleAttempt(
                    rule.id(),
                    application.status,
                    application.message,
                    application.matchCount,
                    application.distinctAssignmentCount
            ));

            if (application.status == RuleApplicationStatus.NO_MATCH) {
                continue;
            }
            if (application.status != RuleApplicationStatus.APPLIED_UNIQUE) {
                return new DecompositionNode(
                        path,
                        label,
                        atomList(atoms),
                        boundaryBonds,
                        application.status,
                        null,
                        ruleHistory,
                        attempts,
                        List.of(),
                        List.of()
                );
            }

            List<String> childHistory = append(ruleHistory, rule.id());
            List<DecompositionNode> children = new ArrayList<>();
            for (Map.Entry<String, BitSet> entry : application.chosen.components.entrySet()) {
                String childLabel = entry.getKey();
                BitSet childAtoms = entry.getValue();
                children.add(expandNode(
                        molecule,
                        path + "." + childLabel,
                        childLabel,
                        childAtoms,
                        boundaryBondsForChild(childAtoms, application.chosen.cutBonds),
                        childHistory
                ));
            }
            children.sort(Comparator.comparing(DecompositionNode::path));
            return new DecompositionNode(
                    path,
                    label,
                    atomList(atoms),
                    boundaryBonds,
                    RuleApplicationStatus.APPLIED_UNIQUE,
                    rule.id(),
                    ruleHistory,
                    attempts,
                    application.chosen.cutBonds,
                    children
            );
        }

        return new DecompositionNode(
                path,
                label,
                atomList(atoms),
                boundaryBonds,
                RuleApplicationStatus.SKIPPED,
                null,
                ruleHistory,
                attempts,
                List.of(),
                List.of()
        );
    }

    private RuleApplication applyRule(StereoMolecule molecule, BitSet parentAtoms, DecompositionRule rule) {
        BasicRuleProblem basicProblem = validateBasicRule(rule);
        if (basicProblem != null) {
            return RuleApplication.invalid(basicProblem.message);
        }

        StereoMolecule query;
        try {
            query = parseSmarts(rule.smarts());
        } catch (Exception e) {
            return RuleApplication.invalid("SMARTS could not be parsed: " + e.getMessage());
        }

        for (Integer queryAtom : rule.atomLabels().keySet()) {
            if (queryAtom == null || queryAtom < 0 || queryAtom >= query.getAtoms()) {
                return RuleApplication.invalid("atomLabels contains query atom index outside SMARTS: " + queryAtom);
            }
        }

        SSSearcher searcher = new SSSearcher();
        searcher.setMol(query, molecule);
        int rawMatchCount = searcher.findFragmentInMolecule(SSSearcher.cCountModeRigorous, SSSearcher.cDefaultMatchMode);
        if (rawMatchCount == 0) {
            return RuleApplication.noMatch();
        }

        List<int[]> inFragmentMatches = searcher.getMatchList().stream()
                .filter(match -> matchInsideParent(match, parentAtoms))
                .toList();
        if (inFragmentMatches.isEmpty()) {
            return RuleApplication.noMatch();
        }

        Map<String, CandidateAssignment> distinct = new TreeMap<>();
        List<String> invalidMessages = new ArrayList<>();
        for (int[] match : inFragmentMatches) {
            CandidateResult candidate = buildCandidate(molecule, parentAtoms, rule, match);
            if (candidate.invalidMessage != null) {
                invalidMessages.add(candidate.invalidMessage);
                continue;
            }
            distinct.putIfAbsent(candidate.assignment.key, candidate.assignment);
        }

        if (!invalidMessages.isEmpty()) {
            return RuleApplication.invalid(invalidMessages.getFirst(), inFragmentMatches.size(), distinct.size());
        }
        if (distinct.isEmpty()) {
            return RuleApplication.invalid("rule matched but produced no valid decomposition", inFragmentMatches.size(), 0);
        }
        if (distinct.size() > 1) {
            return RuleApplication.nonUnique(inFragmentMatches.size(), distinct.size());
        }
        return RuleApplication.unique(inFragmentMatches.size(), distinct.get(distinct.keySet().iterator().next()));
    }

    private CandidateResult buildCandidate(
            StereoMolecule molecule,
            BitSet parentAtoms,
            DecompositionRule rule,
            int[] queryToMolAtom
    ) {
        Map<Integer, String> labelsByAtom = new HashMap<>();
        for (Map.Entry<Integer, String> entry : rule.atomLabels().entrySet()) {
            int moleculeAtom = queryToMolAtom[entry.getKey()];
            String previous = labelsByAtom.putIfAbsent(moleculeAtom, entry.getValue());
            if (previous != null && !previous.equals(entry.getValue())) {
                return CandidateResult.invalid("one molecule atom is assigned to multiple labels");
            }
        }

        BitSet cutBondIndices = new BitSet(molecule.getBonds());
        List<DecompositionCutBond> cutBonds = new ArrayList<>();
        for (int bond = 0; bond < molecule.getBonds(); bond++) {
            int atom1 = molecule.getBondAtom(0, bond);
            int atom2 = molecule.getBondAtom(1, bond);
            if (!parentAtoms.get(atom1) || !parentAtoms.get(atom2)) {
                continue;
            }
            String label1 = labelsByAtom.get(atom1);
            String label2 = labelsByAtom.get(atom2);
            if (label1 != null && label2 != null && !label1.equals(label2)) {
                cutBondIndices.set(bond);
                cutBonds.add(new DecompositionCutBond(
                        bond,
                        atom1,
                        atom2,
                        molecule.getBondType(bond),
                        label1,
                        label2
                ));
            }
        }

        Map<String, BitSet> componentsByLabel = new TreeMap<>();
        for (BitSet component : connectedComponents(molecule, parentAtoms, cutBondIndices)) {
            Set<String> componentLabels = new HashSet<>();
            for (int atom = component.nextSetBit(0); atom >= 0; atom = component.nextSetBit(atom + 1)) {
                String atomLabel = labelsByAtom.get(atom);
                if (atomLabel != null) {
                    componentLabels.add(atomLabel);
                }
            }
            if (componentLabels.isEmpty()) {
                return CandidateResult.invalid("a resulting component contains no labeled atom");
            }
            if (componentLabels.size() > 1) {
                return CandidateResult.invalid("a resulting component contains multiple label types");
            }
            String componentLabel = componentLabels.iterator().next();
            if (componentsByLabel.containsKey(componentLabel)) {
                return CandidateResult.invalid("label produces multiple disconnected components: " + componentLabel);
            }
            componentsByLabel.put(componentLabel, (BitSet) component.clone());
        }

        cutBonds.sort(Comparator.comparingInt(DecompositionCutBond::bondIndex));
        CandidateAssignment assignment = new CandidateAssignment(
                Map.copyOf(componentsByLabel),
                List.copyOf(cutBonds),
                candidateKey(componentsByLabel)
        );
        return CandidateResult.valid(assignment);
    }

    private static StereoMolecule parseSmarts(String smarts) throws Exception {
        StereoMolecule query = new StereoMolecule();
        new SmilesParser(SmilesParser.SMARTS_MODE_IS_SMARTS).parse(query, smarts);
        query.setFragment(true);
        query.ensureHelperArrays(Molecule.cHelperRings);
        return query;
    }

    private static BasicRuleProblem validateBasicRule(DecompositionRule rule) {
        if (rule.id() == null || rule.id().isBlank()) {
            return new BasicRuleProblem("rule id is required");
        }
        if (rule.smarts() == null || rule.smarts().isBlank()) {
            return new BasicRuleProblem("SMARTS is required");
        }
        if (rule.atomLabels().isEmpty()) {
            return new BasicRuleProblem("atomLabels must contain at least one labeled query atom");
        }
        for (Map.Entry<Integer, String> entry : rule.atomLabels().entrySet()) {
            if (entry.getKey() == null || entry.getKey() < 0) {
                return new BasicRuleProblem("atomLabels contains an invalid query atom index");
            }
            if (!DecompositionConfigValidator.isValidLabel(entry.getValue())) {
                return new BasicRuleProblem("atomLabels contains an invalid label: " + entry.getValue());
            }
        }
        if (rule.labelToSplit() != null && !DecompositionConfigValidator.isValidLabel(rule.labelToSplit())) {
            return new BasicRuleProblem("labelToSplit is invalid: " + rule.labelToSplit());
        }
        return null;
    }

    private static boolean matchInsideParent(int[] match, BitSet parentAtoms) {
        for (int atom : match) {
            if (atom < 0 || !parentAtoms.get(atom)) {
                return false;
            }
        }
        return true;
    }

    private static List<BitSet> connectedComponents(StereoMolecule molecule, BitSet atoms, BitSet cutBondIndices) {
        List<BitSet> components = new ArrayList<>();
        BitSet unvisited = (BitSet) atoms.clone();
        while (!unvisited.isEmpty()) {
            int start = unvisited.nextSetBit(0);
            BitSet component = new BitSet(molecule.getAtoms());
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(start);
            unvisited.clear(start);
            component.set(start);

            while (!queue.isEmpty()) {
                int atom = queue.removeFirst();
                for (int i = 0; i < molecule.getConnAtoms(atom); i++) {
                    int bond = molecule.getConnBond(atom, i);
                    if (cutBondIndices.get(bond)) {
                        continue;
                    }
                    int neighbor = molecule.getConnAtom(atom, i);
                    if (!atoms.get(neighbor) || !unvisited.get(neighbor)) {
                        continue;
                    }
                    unvisited.clear(neighbor);
                    component.set(neighbor);
                    queue.add(neighbor);
                }
            }
            components.add(component);
        }
        return components;
    }

    private static String candidateKey(Map<String, BitSet> componentsByLabel) {
        StringBuilder sb = new StringBuilder();
        componentsByLabel.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sb.append(entry.getKey())
                        .append('=')
                        .append(atomList(entry.getValue()))
                        .append(';'));
        return sb.toString();
    }

    private static List<DecompositionBoundaryBond> boundaryBondsForChild(
            BitSet childAtoms,
            List<DecompositionCutBond> cutBonds
    ) {
        List<DecompositionBoundaryBond> boundaryBonds = new ArrayList<>();
        for (DecompositionCutBond cutBond : cutBonds) {
            if (childAtoms.get(cutBond.atom1())) {
                boundaryBonds.add(new DecompositionBoundaryBond(
                        cutBond.bondIndex(),
                        cutBond.atom1(),
                        cutBond.atom2(),
                        cutBond.bondType(),
                        cutBond.label2()
                ));
            } else if (childAtoms.get(cutBond.atom2())) {
                boundaryBonds.add(new DecompositionBoundaryBond(
                        cutBond.bondIndex(),
                        cutBond.atom2(),
                        cutBond.atom1(),
                        cutBond.bondType(),
                        cutBond.label1()
                ));
            }
        }
        boundaryBonds.sort(Comparator.comparingInt(DecompositionBoundaryBond::bondIndex));
        return List.copyOf(boundaryBonds);
    }

    private static List<Integer> atomList(BitSet atoms) {
        List<Integer> atomList = new ArrayList<>();
        for (int atom = atoms.nextSetBit(0); atom >= 0; atom = atoms.nextSetBit(atom + 1)) {
            atomList.add(atom);
        }
        return List.copyOf(atomList);
    }

    private static List<String> append(List<String> values, String value) {
        List<String> copy = new ArrayList<>(values);
        copy.add(value);
        return List.copyOf(copy);
    }

    private record BasicRuleProblem(String message) {}

    private record CandidateAssignment(
            Map<String, BitSet> components,
            List<DecompositionCutBond> cutBonds,
            String key
    ) {}

    private record CandidateResult(CandidateAssignment assignment, String invalidMessage) {
        static CandidateResult valid(CandidateAssignment assignment) {
            return new CandidateResult(assignment, null);
        }

        static CandidateResult invalid(String message) {
            return new CandidateResult(null, message);
        }
    }

    private static final class RuleApplication {
        final RuleApplicationStatus status;
        final String message;
        final int matchCount;
        final int distinctAssignmentCount;
        final CandidateAssignment chosen;

        private RuleApplication(
                RuleApplicationStatus status,
                String message,
                int matchCount,
                int distinctAssignmentCount,
                CandidateAssignment chosen
        ) {
            this.status = status;
            this.message = message;
            this.matchCount = matchCount;
            this.distinctAssignmentCount = distinctAssignmentCount;
            this.chosen = chosen;
        }

        static RuleApplication noMatch() {
            return new RuleApplication(RuleApplicationStatus.NO_MATCH, "rule did not match this fragment", 0, 0, null);
        }

        static RuleApplication invalid(String message) {
            return invalid(message, 0, 0);
        }

        static RuleApplication invalid(String message, int matchCount, int distinctAssignmentCount) {
            return new RuleApplication(
                    RuleApplicationStatus.INVALID_RULE_OR_ASSIGNMENT,
                    message,
                    matchCount,
                    distinctAssignmentCount,
                    null
            );
        }

        static RuleApplication nonUnique(int matchCount, int distinctAssignmentCount) {
            return new RuleApplication(
                    RuleApplicationStatus.MATCHED_NON_UNIQUE,
                    "rule matched with multiple effective decompositions",
                    matchCount,
                    distinctAssignmentCount,
                    null
            );
        }

        static RuleApplication unique(int matchCount, CandidateAssignment chosen) {
            return new RuleApplication(
                    RuleApplicationStatus.APPLIED_UNIQUE,
                    "rule produced one effective decomposition",
                    matchCount,
                    1,
                    chosen
            );
        }
    }
}
