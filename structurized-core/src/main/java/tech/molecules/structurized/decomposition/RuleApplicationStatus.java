package tech.molecules.structurized.decomposition;

/**
 * Status of one deterministic decomposition rule attempt or node expansion.
 */
public enum RuleApplicationStatus {
    APPLIED_UNIQUE,
    NO_MATCH,
    MATCHED_NON_UNIQUE,
    INVALID_RULE_OR_ASSIGNMENT,
    SKIPPED
}
