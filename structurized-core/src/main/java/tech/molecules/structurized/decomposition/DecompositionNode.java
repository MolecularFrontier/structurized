package tech.molecules.structurized.decomposition;

import java.util.ArrayList;
import java.util.List;

/**
 * One node in the recursive decomposition tree, represented over original molecule atom indices.
 */
public record DecompositionNode(
        String path,
        String label,
        List<Integer> atomIndices,
        List<DecompositionBoundaryBond> boundaryBonds,
        RuleApplicationStatus status,
        String appliedRuleId,
        List<String> ruleHistory,
        List<RuleAttempt> ruleAttempts,
        List<DecompositionCutBond> cutBondsProduced,
        List<DecompositionNode> children
) {
    public DecompositionNode {
        atomIndices = atomIndices == null ? List.of() : List.copyOf(atomIndices);
        boundaryBonds = boundaryBonds == null ? List.of() : List.copyOf(boundaryBonds);
        ruleHistory = ruleHistory == null ? List.of() : List.copyOf(ruleHistory);
        ruleAttempts = ruleAttempts == null ? List.of() : List.copyOf(ruleAttempts);
        cutBondsProduced = cutBondsProduced == null ? List.of() : List.copyOf(cutBondsProduced);
        children = children == null ? List.of() : List.copyOf(children);
    }

    public boolean isTerminal() {
        return children.isEmpty();
    }

    public boolean hasProblem() {
        if (status == RuleApplicationStatus.MATCHED_NON_UNIQUE
                || status == RuleApplicationStatus.INVALID_RULE_OR_ASSIGNMENT) {
            return true;
        }
        return children.stream().anyMatch(DecompositionNode::hasProblem);
    }

    public List<DecompositionNode> terminalNodes() {
        List<DecompositionNode> nodes = new ArrayList<>();
        collectTerminalNodes(this, nodes);
        return List.copyOf(nodes);
    }

    private static void collectTerminalNodes(DecompositionNode node, List<DecompositionNode> nodes) {
        if (node.children.isEmpty()) {
            nodes.add(node);
            return;
        }
        for (DecompositionNode child : node.children) {
            collectTerminalNodes(child, nodes);
        }
    }
}
