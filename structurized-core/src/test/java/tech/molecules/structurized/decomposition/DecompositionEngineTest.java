package tech.molecules.structurized.decomposition;

import com.actelion.research.chem.SmilesParser;
import com.actelion.research.chem.StereoMolecule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecompositionEngineTest {

    @Test
    void configRoundTripsThroughJson() throws Exception {
        DecompositionConfig config = DecompositionConfig.of(List.of(
                DecompositionRule.of("split_root", null, "CCO", Map.of(0, "alkyl", 1, "linker", 2, "head"))
        ));

        String json = DecompositionJson.writeConfig(config);
        DecompositionConfig parsed = DecompositionJson.readConfig(json);

        assertEquals(DecompositionConfig.DEFAULT_VERSION, parsed.version());
        assertEquals(1, parsed.rules().size());
        assertEquals("split_root", parsed.rules().getFirst().id());
        assertEquals("alkyl", parsed.rules().getFirst().atomLabels().get(0));
    }

    @Test
    void validatorFindsInvalidRuleShape() {
        DecompositionConfig config = DecompositionConfig.of(List.of(
                new DecompositionRule("", null, null, "bad.label", "", Map.of(0, ""), true),
                DecompositionRule.of("dup", null, "CC", Map.of(0, "a")),
                DecompositionRule.of("dup", null, "CC", Map.of(0, "b"))
        ));

        List<String> problems = DecompositionConfigValidator.validate(config);

        assertTrue(problems.stream().anyMatch(problem -> problem.contains("id is required")));
        assertTrue(problems.stream().anyMatch(problem -> problem.contains("labelToSplit is invalid")));
        assertTrue(problems.stream().anyMatch(problem -> problem.contains("duplicated")));
    }

    @Test
    void nWaySplitAllowsUnlabeledAtomsToInheritComponentLabel() throws Exception {
        DecompositionConfig config = DecompositionConfig.of(List.of(
                DecompositionRule.of("split_root", null, "CCO", Map.of(0, "alkyl", 1, "linker", 2, "head"))
        ));

        DecompositionResult result = DecompositionEngine.evaluate(config, "butanol_fragment", parse("CCCO"));

        assertTrue(result.successful());
        assertEquals(RuleApplicationStatus.APPLIED_UNIQUE, result.root().status());
        assertEquals(3, result.root().children().size());
        assertEquals(2, result.root().cutBondsProduced().size());
        assertEquals(List.of(0, 1), child(result.root(), "alkyl").atomIndices());
        assertEquals(List.of(2), child(result.root(), "linker").atomIndices());
        assertEquals(List.of(3), child(result.root(), "head").atomIndices());
        assertEquals(1, child(result.root(), "head").boundaryBonds().size());
    }

    @Test
    void recursiveRulesRecordCanonicalPathAndRuleHistory() throws Exception {
        DecompositionConfig config = DecompositionConfig.of(List.of(
                DecompositionRule.of("split_root", null, "CCO", Map.of(0, "alkyl", 1, "linker", 2, "head")),
                DecompositionRule.of("alkyl_wrong", "alkyl", "CO", Map.of(0, "carbon", 1, "oxygen")),
                DecompositionRule.of("split_alkyl", "alkyl", "NC", Map.of(0, "amine", 1, "methylene"))
        ));

        DecompositionResult result = DecompositionEngine.evaluate(config, "amino_ethanol_fragment", parse("NCCO"));
        DecompositionNode alkyl = child(result.root(), "alkyl");

        assertTrue(result.successful());
        assertEquals(RuleApplicationStatus.APPLIED_UNIQUE, alkyl.status());
        assertEquals("split_alkyl", alkyl.appliedRuleId());
        assertEquals(List.of("split_root"), alkyl.ruleHistory());
        assertEquals(2, alkyl.ruleAttempts().size());
        assertEquals(RuleApplicationStatus.NO_MATCH, alkyl.ruleAttempts().get(0).status());
        assertEquals("root.alkyl.amine", child(alkyl, "amine").path());
        assertEquals(List.of("split_root", "split_alkyl"), child(alkyl, "amine").ruleHistory());
    }

    @Test
    void equivalentRigorousMatchesCollapseToOneEffectiveAssignment() throws Exception {
        DecompositionConfig config = DecompositionConfig.of(List.of(
                DecompositionRule.of("label_ethane", null, "CC", Map.of(0, "whole", 1, "whole"))
        ));

        DecompositionResult result = DecompositionEngine.evaluate(config, "ethane", parse("CC"));

        assertTrue(result.successful());
        assertEquals(RuleApplicationStatus.APPLIED_UNIQUE, result.root().status());
        assertTrue(result.root().ruleAttempts().getFirst().matchCount() > 1);
        assertEquals(1, result.root().ruleAttempts().getFirst().distinctAssignmentCount());
    }

    @Test
    void distinctEffectiveAssignmentsAreNonUnique() throws Exception {
        DecompositionConfig config = DecompositionConfig.of(List.of(
                DecompositionRule.of("split_any_three_carbons", null, "CCC", Map.of(0, "left", 1, "middle", 2, "right"))
        ));

        DecompositionResult result = DecompositionEngine.evaluate(config, "pentane", parse("CCCCC"));

        assertFalse(result.successful());
        assertEquals(RuleApplicationStatus.MATCHED_NON_UNIQUE, result.root().status());
        assertTrue(result.root().ruleAttempts().getFirst().distinctAssignmentCount() > 1);
    }

    @Test
    void componentContainingMultipleLabelTypesIsInvalid() throws Exception {
        DecompositionConfig config = DecompositionConfig.of(List.of(
                DecompositionRule.of("labels_without_cut", null, "CCO", Map.of(0, "left", 2, "right"))
        ));

        DecompositionResult result = DecompositionEngine.evaluate(config, "ethanol", parse("CCO"));

        assertFalse(result.successful());
        assertEquals(RuleApplicationStatus.INVALID_RULE_OR_ASSIGNMENT, result.root().status());
        assertTrue(result.root().ruleAttempts().getFirst().message().contains("multiple label types"));
    }

    @Test
    void repeatedDisconnectedOutputLabelIsInvalid() throws Exception {
        DecompositionConfig config = DecompositionConfig.of(List.of(
                DecompositionRule.of("same_label_twice", null, "CCOCC", Map.of(
                        0, "side",
                        1, "cut1",
                        2, "middle",
                        3, "cut2",
                        4, "side"
                ))
        ));

        DecompositionResult result = DecompositionEngine.evaluate(config, "ether", parse("CCOCC"));

        assertFalse(result.successful());
        assertEquals(RuleApplicationStatus.INVALID_RULE_OR_ASSIGNMENT, result.root().status());
        assertTrue(result.root().ruleAttempts().getFirst().message().contains("multiple disconnected components"));
    }

    @Test
    void datasetEvaluatorReportsCoverageAndRootNoMatchWitnesses() throws Exception {
        DecompositionConfig config = DecompositionConfig.of(List.of(
                DecompositionRule.of("split_root", null, "CCO", Map.of(0, "alkyl", 1, "linker", 2, "head"))
        ));

        DecompositionDatasetEvaluation evaluation = DecompositionDatasetEvaluator.evaluate(config, List.of(
                new DecompositionInputMolecule("matched", parse("CCCO")),
                new DecompositionInputMolecule("unmatched", parse("C"))
        ));

        assertEquals(2, evaluation.moleculeCount());
        assertEquals(1, evaluation.successfulCount());
        assertEquals(1, evaluation.rootNoMatchCount());
        assertEquals(0.5, evaluation.coverage());
        assertNotNull(evaluation.terminalFragmentFrequencies().get("alkyl"));
    }

    private static DecompositionNode child(DecompositionNode node, String label) {
        return node.children().stream()
                .filter(child -> label.equals(child.label()))
                .findFirst()
                .orElseThrow();
    }

    private static StereoMolecule parse(String smiles) throws Exception {
        StereoMolecule molecule = new StereoMolecule();
        new SmilesParser().parse(molecule, smiles);
        molecule.ensureHelperArrays(StereoMolecule.cHelperRings);
        return molecule;
    }
}
