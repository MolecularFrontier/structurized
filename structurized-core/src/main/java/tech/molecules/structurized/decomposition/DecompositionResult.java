package tech.molecules.structurized.decomposition;

import java.util.List;

/**
 * Complete deterministic decomposition result for one molecule.
 */
public record DecompositionResult(
        String moleculeId,
        String configurationVersion,
        DecompositionNode root
) {
    public boolean rootDecomposed() {
        return root != null && root.status() == RuleApplicationStatus.APPLIED_UNIQUE;
    }

    public boolean hasProblems() {
        return root != null && root.hasProblem();
    }

    public boolean successful() {
        return rootDecomposed() && !hasProblems();
    }

    public List<DecompositionNode> terminalNodes() {
        return root == null ? List.of() : root.terminalNodes();
    }
}
