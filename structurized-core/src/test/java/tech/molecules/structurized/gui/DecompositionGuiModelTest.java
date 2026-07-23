package tech.molecules.structurized.gui;

import org.junit.jupiter.api.Test;
import tech.molecules.structurized.decomposition.DecompositionConfig;
import tech.molecules.structurized.decomposition.DecompositionRule;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecompositionGuiModelTest {

    @Test
    void buildsResultRowsAndFragmentSummaries() {
        DecompositionConfig config = DecompositionConfig.of(List.of(
                DecompositionRule.of("split_root", null, "CCO", Map.of(0, "alkyl", 1, "linker", 2, "head"))
        ));

        DecompositionGuiModel.RunModel model = DecompositionGuiModel.evaluate(List.of(
                new SmilesInputReader.SmilesRecord("CCCO", "butanol_fragment"),
                new SmilesInputReader.SmilesRecord("C", "methane"),
                new SmilesInputReader.SmilesRecord("not_smiles", "bad")
        ), config);

        assertEquals(3, model.totalInputRows());
        assertEquals(1, model.parseErrorCount());
        assertEquals(2, model.evaluation().moleculeCount());
        assertEquals("SUCCESS", model.moleculeRows().get(0).statusText());
        assertEquals("NO_MATCH", model.moleculeRows().get(1).statusText());
        assertEquals("PARSE_ERROR", model.moleculeRows().get(2).statusText());
        assertTrue(model.moleculeRows().get(0).terminalPaths().contains("root.alkyl"));
        assertFalse(model.fragmentRows().isEmpty());
        assertTrue(model.summaryText().contains("Input rows: 3"));
    }

    @Test
    void reportsNonUniqueAndInvalidStatuses() {
        DecompositionGuiModel.RunModel nonUnique = DecompositionGuiModel.evaluate(List.of(
                new SmilesInputReader.SmilesRecord("CCCCC", "pentane")
        ), DecompositionConfig.of(List.of(
                DecompositionRule.of("split_any_three_carbons", null, "CCC", Map.of(0, "left", 1, "middle", 2, "right"))
        )));

        DecompositionGuiModel.RunModel invalid = DecompositionGuiModel.evaluate(List.of(
                new SmilesInputReader.SmilesRecord("CCO", "ethanol")
        ), DecompositionConfig.of(List.of(
                DecompositionRule.of("labels_without_cut", null, "CCO", Map.of(0, "left", 2, "right"))
        )));

        assertEquals("NON_UNIQUE", nonUnique.moleculeRows().getFirst().statusText());
        assertEquals("INVALID", invalid.moleculeRows().getFirst().statusText());
        assertTrue(invalid.moleculeRows().getFirst().problemSummary().contains("multiple label types"));
    }

    @Test
    void detailTextIncludesTreeAttemptsAndCutBonds() {
        DecompositionConfig config = DecompositionConfig.of(List.of(
                DecompositionRule.of("split_root", null, "CCO", Map.of(0, "alkyl", 1, "linker", 2, "head"))
        ));
        DecompositionGuiModel.RunModel model = DecompositionGuiModel.evaluate(List.of(
                new SmilesInputReader.SmilesRecord("CCCO", "butanol_fragment")
        ), config);

        String detail = DecompositionGuiModel.detailText(model.moleculeRows().getFirst());

        assertTrue(detail.contains("Molecule ID: butanol_fragment"));
        assertTrue(detail.contains("root.alkyl"));
        assertTrue(detail.contains("attempt split_root"));
        assertTrue(detail.contains("cut bond"));
    }
}
