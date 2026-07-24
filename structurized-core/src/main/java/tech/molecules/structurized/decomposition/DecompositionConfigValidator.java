package tech.molecules.structurized.decomposition;

import com.actelion.research.chem.Molecule;
import com.actelion.research.chem.SmilesParser;
import com.actelion.research.chem.StereoMolecule;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Static validation for recursive decomposition configurations.
 */
public final class DecompositionConfigValidator {
    public static final String VALIDATION_SCOPE = "schema_and_query_graph";

    private DecompositionConfigValidator() {}

    public static List<String> validate(DecompositionConfig config) {
        return validateDetailed(config).problems();
    }

    public static ValidationReport validateDetailed(DecompositionConfig config) {
        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<RuleDiagnostic> ruleDiagnostics = new ArrayList<>();
        warnings.add("Static validation checks schema, SMARTS parsing, atom-label indices, and query-graph label partitioning; evaluate_decomposition is still required for molecule-specific matching, ambiguity, and coverage.");
        if (config == null) {
            problems.add("configuration is null");
            return new ValidationReport(false, VALIDATION_SCOPE, problems, warnings, null, 0, ruleDiagnostics);
        }

        Set<String> ruleIds = new HashSet<>();
        for (int i = 0; i < config.rules().size(); i++) {
            DecompositionRule rule = config.rules().get(i);
            String prefix = "rules[" + i + "]";
            List<String> ruleProblems = new ArrayList<>();
            List<String> ruleWarnings = new ArrayList<>();
            Integer queryAtomCount = null;
            if (rule == null) {
                ruleProblems.add(prefix + " is null");
                problems.addAll(ruleProblems);
                ruleDiagnostics.add(new RuleDiagnostic(i, null, null, null, false, null, ruleProblems, ruleWarnings));
                continue;
            }
            if (rule.id() == null || rule.id().isBlank()) {
                ruleProblems.add(prefix + ".id is required");
            } else if (!ruleIds.add(rule.id())) {
                ruleProblems.add(prefix + ".id is duplicated: " + rule.id());
            }
            if (rule.smarts() == null || rule.smarts().isBlank()) {
                ruleProblems.add(prefix + ".smarts is required");
            }
            if (rule.atomLabels().isEmpty()) {
                ruleProblems.add(prefix + ".atomLabels must contain at least one labeled query atom");
            }
            for (Map.Entry<Integer, String> entry : rule.atomLabels().entrySet()) {
                if (entry.getKey() == null || entry.getKey() < 0) {
                    ruleProblems.add(prefix + ".atomLabels contains an invalid query atom index");
                }
                if (!isValidLabel(entry.getValue())) {
                    ruleProblems.add(prefix + ".atomLabels contains an invalid label: " + entry.getValue());
                }
            }
            if (rule.labelToSplit() != null && !isValidLabel(rule.labelToSplit())) {
                ruleProblems.add(prefix + ".labelToSplit is invalid: " + rule.labelToSplit());
            }

            if (rule.isEnabled() && rule.smarts() != null && !rule.smarts().isBlank()) {
                try {
                    StereoMolecule query = parseSmarts(rule.smarts());
                    queryAtomCount = query.getAtoms();
                    ruleProblems.addAll(validateAtomLabelIndices(prefix, rule, queryAtomCount));
                    if (ruleProblems.stream().noneMatch(problem -> problem.contains("atomLabels contains"))) {
                        ruleProblems.addAll(validateQueryGraphPartition(prefix, rule, query));
                    }
                } catch (Exception e) {
                    ruleProblems.add(prefix + ".smarts could not be parsed: " + e.getMessage());
                }
            } else if (!rule.isEnabled()) {
                ruleWarnings.add(prefix + " is disabled; SMARTS compilation and query-graph partition validation were skipped");
            }

            problems.addAll(ruleProblems);
            warnings.addAll(ruleWarnings);
            ruleDiagnostics.add(new RuleDiagnostic(
                    i,
                    rule.id(),
                    rule.labelToSplit(),
                    rule.smarts(),
                    rule.isEnabled(),
                    queryAtomCount,
                    List.copyOf(ruleProblems),
                    List.copyOf(ruleWarnings)
            ));
        }
        return new ValidationReport(
                problems.isEmpty(),
                VALIDATION_SCOPE,
                List.copyOf(problems),
                List.copyOf(warnings),
                config.version(),
                config.rules().size(),
                List.copyOf(ruleDiagnostics)
        );
    }

    private static List<String> validateAtomLabelIndices(String prefix, DecompositionRule rule, int queryAtomCount) {
        List<String> problems = new ArrayList<>();
        for (Integer queryAtom : rule.atomLabels().keySet()) {
            if (queryAtom != null && queryAtom >= queryAtomCount) {
                problems.add(prefix + ".atomLabels contains query atom index outside SMARTS: " + queryAtom + " >= " + queryAtomCount);
            }
        }
        return problems;
    }

    private static List<String> validateQueryGraphPartition(String prefix, DecompositionRule rule, StereoMolecule query) {
        List<String> problems = new ArrayList<>();
        Map<Integer, String> labelsByAtom = new LinkedHashMap<>();
        rule.atomLabels().forEach(labelsByAtom::put);
        BitSet cutBonds = new BitSet(query.getBonds());
        for (int bond = 0; bond < query.getBonds(); bond++) {
            int atom1 = query.getBondAtom(0, bond);
            int atom2 = query.getBondAtom(1, bond);
            String label1 = labelsByAtom.get(atom1);
            String label2 = labelsByAtom.get(atom2);
            if (label1 != null && label2 != null && !label1.equals(label2)) {
                cutBonds.set(bond);
            }
        }

        Map<String, Integer> componentsPerLabel = new LinkedHashMap<>();
        for (BitSet component : connectedComponents(query, cutBonds)) {
            Set<String> labels = new HashSet<>();
            for (int atom = component.nextSetBit(0); atom >= 0; atom = component.nextSetBit(atom + 1)) {
                String label = labelsByAtom.get(atom);
                if (label != null) {
                    labels.add(label);
                }
            }
            if (labels.size() > 1) {
                problems.add(prefix + ".atomLabels query graph partition is invalid: a resulting query component contains multiple label types " + labels + ". atomLabels keys are zero-based SMARTS query atom indices, not SMARTS atom-map numbers.");
            } else if (labels.size() == 1) {
                String label = labels.iterator().next();
                componentsPerLabel.merge(label, 1, Integer::sum);
            }
        }
        componentsPerLabel.forEach((label, count) -> {
            if (count > 1) {
                problems.add(prefix + ".atomLabels query graph partition is invalid: label produces multiple disconnected query components: " + label);
            }
        });
        return problems;
    }

    private static StereoMolecule parseSmarts(String smarts) throws Exception {
        StereoMolecule query = new StereoMolecule();
        new SmilesParser(SmilesParser.SMARTS_MODE_IS_SMARTS).parse(query, smarts);
        query.setFragment(true);
        query.ensureHelperArrays(Molecule.cHelperRings);
        return query;
    }

    private static List<BitSet> connectedComponents(StereoMolecule query, BitSet cutBonds) {
        List<BitSet> components = new ArrayList<>();
        BitSet unvisited = new BitSet(query.getAtoms());
        unvisited.set(0, query.getAtoms());
        while (!unvisited.isEmpty()) {
            int start = unvisited.nextSetBit(0);
            BitSet component = new BitSet(query.getAtoms());
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(start);
            unvisited.clear(start);
            component.set(start);
            while (!queue.isEmpty()) {
                int atom = queue.removeFirst();
                for (int i = 0; i < query.getConnAtoms(atom); i++) {
                    int bond = query.getConnBond(atom, i);
                    if (cutBonds.get(bond)) {
                        continue;
                    }
                    int neighbor = query.getConnAtom(atom, i);
                    if (!unvisited.get(neighbor)) {
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

    static boolean isValidLabel(String label) {
        return label != null && !label.isBlank() && !label.contains(".");
    }

    public record ValidationReport(
            boolean valid,
            String validationScope,
            List<String> problems,
            List<String> warnings,
            String version,
            int ruleCount,
            List<RuleDiagnostic> ruleDiagnostics
    ) {}

    public record RuleDiagnostic(
            int ruleIndex,
            String ruleId,
            String labelToSplit,
            String smarts,
            boolean enabled,
            Integer queryAtomCount,
            List<String> problems,
            List<String> warnings
    ) {}
}
