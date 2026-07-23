package tech.molecules.structurized.gui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SmilesInputReaderTest {

    @Test
    void readsSimpleSmilesLinesWithOptionalNamesAndHeader() {
        List<String> smiles = SmilesInputReader.parseSmilesLines(List.of(
                "smiles name",
                "# comment",
                "",
                "c1ccccc1 benzene",
                "CCO,ethanol",
                "CCN"
        ));

        assertEquals(List.of("c1ccccc1", "CCO", "CCN"), smiles);
    }

    @Test
    void readsSmilesRecordsWithOptionalIds() {
        List<SmilesInputReader.SmilesRecord> records = SmilesInputReader.parseSmilesRecords(List.of(
                "smiles name",
                "CCO ethanol",
                "CCN,ethylamine",
                "CCC"
        ));

        assertEquals("CCO", records.get(0).smiles());
        assertEquals("ethanol", records.get(0).moleculeId());
        assertEquals("ethylamine", records.get(1).moleculeId());
        assertNull(records.get(2).moleculeId());
    }
}
