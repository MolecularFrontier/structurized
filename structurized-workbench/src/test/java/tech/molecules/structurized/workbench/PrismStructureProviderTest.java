package tech.molecules.structurized.workbench;

import org.junit.jupiter.api.Test;
import tech.molecules.structurized.prism.provider.SubjectRecord;
import tech.molecules.structurized.prism.provider.inmemory.InMemoryPrismDataset;
import tech.molecules.structurized.workbench.model.PrismStructureProvider;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrismStructureProviderTest {

    @Test
    void parsesValidSmilesAndReportsInvalidSmiles() {
        InMemoryPrismDataset dataset = InMemoryPrismDataset.builder()
                .addSubjectRecord(SubjectRecord.builder().subjectId("ok").smiles("Cc1ccccc1").build())
                .addSubjectRecord(SubjectRecord.builder().subjectId("bad").smiles("not-a-smiles").build())
                .addSubjectRecord(SubjectRecord.builder().subjectId("missing").build())
                .build();

        PrismStructureProvider provider = PrismStructureProvider.from(dataset);

        assertTrue(provider.findStructure("ok").isPresent());
        assertFalse(provider.findStructure("bad").isPresent());
        assertFalse(provider.findStructure("missing").isPresent());
        assertTrue(provider.parseErrorsBySubjectId().containsKey("bad"));
    }
}
