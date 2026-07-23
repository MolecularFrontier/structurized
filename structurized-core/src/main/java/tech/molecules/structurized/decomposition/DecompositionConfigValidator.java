package tech.molecules.structurized.decomposition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lightweight structural validation that does not require OpenChemLib SMARTS parsing.
 */
public final class DecompositionConfigValidator {
    private DecompositionConfigValidator() {}

    public static List<String> validate(DecompositionConfig config) {
        List<String> problems = new ArrayList<>();
        if (config == null) {
            problems.add("configuration is null");
            return problems;
        }

        Set<String> ruleIds = new HashSet<>();
        for (int i = 0; i < config.rules().size(); i++) {
            DecompositionRule rule = config.rules().get(i);
            String prefix = "rules[" + i + "]";
            if (rule == null) {
                problems.add(prefix + " is null");
                continue;
            }
            if (rule.id() == null || rule.id().isBlank()) {
                problems.add(prefix + ".id is required");
            } else if (!ruleIds.add(rule.id())) {
                problems.add(prefix + ".id is duplicated: " + rule.id());
            }
            if (rule.smarts() == null || rule.smarts().isBlank()) {
                problems.add(prefix + ".smarts is required");
            }
            if (rule.atomLabels().isEmpty()) {
                problems.add(prefix + ".atomLabels must contain at least one labeled query atom");
            }
            for (Map.Entry<Integer, String> entry : rule.atomLabels().entrySet()) {
                if (entry.getKey() == null || entry.getKey() < 0) {
                    problems.add(prefix + ".atomLabels contains an invalid query atom index");
                }
                if (!isValidLabel(entry.getValue())) {
                    problems.add(prefix + ".atomLabels contains an invalid label: " + entry.getValue());
                }
            }
            if (rule.labelToSplit() != null && !isValidLabel(rule.labelToSplit())) {
                problems.add(prefix + ".labelToSplit is invalid: " + rule.labelToSplit());
            }
        }
        return List.copyOf(problems);
    }

    static boolean isValidLabel(String label) {
        return label != null && !label.isBlank() && !label.contains(".");
    }
}
