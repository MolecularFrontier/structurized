package tech.molecules.structurized.decomposition;

/**
 * Diagnostic summary for one candidate rule tried at a decomposition node.
 */
public record RuleAttempt(
        String ruleId,
        RuleApplicationStatus status,
        String message,
        int matchCount,
        int distinctAssignmentCount
) {}
