package tech.molecules.structurized.decomposition;

import com.actelion.research.chem.Canonizer;
import com.actelion.research.chem.StereoMolecule;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dataset-wide execution and basic fragment frequency aggregation.
 */
public final class DecompositionDatasetEvaluator {
    private DecompositionDatasetEvaluator() {}

    public static DecompositionDatasetEvaluation evaluate(
            DecompositionConfig config,
            List<DecompositionInputMolecule> molecules
    ) {
        DecompositionEngine engine = new DecompositionEngine(config);
        List<DecompositionResult> results = new ArrayList<>();
        Map<String, Map<String, Integer>> fragmentFrequencies = new LinkedHashMap<>();
        int successfulCount = 0;
        int rootNoMatchCount = 0;
        int nonUniqueCount = 0;
        int invalidCount = 0;

        for (DecompositionInputMolecule input : molecules) {
            DecompositionResult result = engine.evaluate(input.moleculeId(), input.molecule());
            results.add(result);
            if (result.successful()) {
                successfulCount++;
            }
            if (result.root().status() == RuleApplicationStatus.SKIPPED
                    && !result.root().ruleAttempts().isEmpty()
                    && result.root().ruleAttempts().stream()
                    .allMatch(attempt -> attempt.status() == RuleApplicationStatus.NO_MATCH)) {
                rootNoMatchCount++;
            }
            if (containsStatus(result.root(), RuleApplicationStatus.MATCHED_NON_UNIQUE)) {
                nonUniqueCount++;
            }
            if (containsStatus(result.root(), RuleApplicationStatus.INVALID_RULE_OR_ASSIGNMENT)) {
                invalidCount++;
            }

            for (DecompositionNode terminal : result.terminalNodes()) {
                if (terminal.label() == null) {
                    continue;
                }
                fragmentFrequencies
                        .computeIfAbsent(terminal.label(), ignored -> new LinkedHashMap<>())
                        .merge(fragmentSignature(input.molecule(), terminal), 1, Integer::sum);
            }
        }

        return new DecompositionDatasetEvaluation(
                config.version(),
                molecules.size(),
                successfulCount,
                rootNoMatchCount,
                nonUniqueCount,
                invalidCount,
                deepCopy(fragmentFrequencies),
                List.copyOf(results)
        );
    }

    private static boolean containsStatus(DecompositionNode node, RuleApplicationStatus status) {
        if (node.status() == status) {
            return true;
        }
        return node.children().stream().anyMatch(child -> containsStatus(child, status));
    }

    private static String fragmentSignature(StereoMolecule molecule, DecompositionNode node) {
        BitSet atoms = new BitSet(molecule.getAtoms());
        for (int atom : node.atomIndices()) {
            atoms.set(atom);
        }
        boolean[] include = new boolean[molecule.getAtoms()];
        for (int atom = atoms.nextSetBit(0); atom >= 0; atom = atoms.nextSetBit(atom + 1)) {
            include[atom] = true;
        }
        StereoMolecule fragment = new StereoMolecule();
        molecule.copyMoleculeByAtoms(fragment, include, true, null);
        return new Canonizer(fragment, Canonizer.ENCODE_ATOM_CUSTOM_LABELS).getIDCode();
    }

    private static Map<String, Map<String, Integer>> deepCopy(Map<String, Map<String, Integer>> source) {
        Map<String, Map<String, Integer>> copy = new LinkedHashMap<>();
        source.forEach((label, frequencies) -> copy.put(label, Map.copyOf(frequencies)));
        return Map.copyOf(copy);
    }
}
