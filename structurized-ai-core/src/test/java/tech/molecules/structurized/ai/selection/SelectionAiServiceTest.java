package tech.molecules.structurized.ai.selection;

import org.junit.jupiter.api.Test;
import tech.molecules.structurized.ai.model.ChemOperationException;
import tech.molecules.structurized.ai.model.RegisterStructureRequest;
import tech.molecules.structurized.ai.repository.InMemoryStructureRepositoryService;
import tech.molecules.structurized.ai.repository.StructureRepositoryService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SelectionAiServiceTest {
    @Test
    void storesSelectionHandlesWithExamplesAndPagedMembers() {
        StructureRepositoryService repositories = new InMemoryStructureRepositoryService();
        repositories.registerStructure(new RegisterStructureRequest("CCO", "session", "ethanol", "Ethanol", Map.of("prism.subject_id", "S1")));
        repositories.registerStructure(new RegisterStructureRequest("CCN", "session", "ethylamine", "Ethylamine", Map.of("prism.subject_id", "S2")));
        SelectionAiService service = new SelectionAiService(repositories);

        SelectionAiService.SelectionRecord record = service.createSelection("sel1", "session", "test", "unit", List.of("ethanol", "ethylamine"));
        SelectionAiService.SelectionView view = service.getSelection("sel1");
        SelectionAiService.SelectionMembersView members = service.getMembers("sel1", 1, 1);

        assertEquals("sel1", record.selectionId());
        assertEquals(2, view.summary().memberCount());
        assertEquals(2, view.examples().size());
        assertEquals(List.of("ethylamine"), members.members().stream().map(SelectionAiService.SelectionMember::structureId).toList());
        assertEquals("S2", members.members().getFirst().fields().get("prism.subject_id"));
    }

    @Test
    void duplicateSelectionFailsExplicitly() {
        StructureRepositoryService repositories = new InMemoryStructureRepositoryService();
        repositories.registerStructure(new RegisterStructureRequest("CCO", "session", "ethanol", "Ethanol", Map.of()));
        SelectionAiService service = new SelectionAiService(repositories);
        service.createSelection("sel1", "session", "test", "unit", List.of("ethanol"));

        ChemOperationException exception = assertThrows(
                ChemOperationException.class,
                () -> service.createSelection("sel1", "session", "test", "unit", List.of("ethanol"))
        );

        assertEquals("duplicate_selection", exception.code());
    }
}
