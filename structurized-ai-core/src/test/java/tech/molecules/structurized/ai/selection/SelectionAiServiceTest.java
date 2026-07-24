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
    void combinesSelectionsWithSetOperations() {
        StructureRepositoryService repositories = new InMemoryStructureRepositoryService();
        repositories.registerStructure(new RegisterStructureRequest("CCO", "session", "ethanol", "Ethanol", Map.of()));
        repositories.registerStructure(new RegisterStructureRequest("CCN", "session", "ethylamine", "Ethylamine", Map.of()));
        repositories.registerStructure(new RegisterStructureRequest("c1ccccc1", "session", "benzene", "Benzene", Map.of()));
        SelectionAiService service = new SelectionAiService(repositories);
        service.createSelection("oxygen", "session", "test", "oxygen", List.of("ethanol"));
        service.createSelection("small", "session", "test", "small", List.of("ethanol", "ethylamine"));
        service.createSelection("aryl", "session", "test", "aryl", List.of("benzene"));

        SelectionAiService.SelectionView intersect = service.combineSelections("oxygen_small", "intersect", List.of("oxygen", "small"));
        SelectionAiService.SelectionView union = service.combineSelections("all_three", "merge", List.of("oxygen", "small", "aryl"));
        SelectionAiService.SelectionView subtract = service.combineSelections("small_without_oxygen", "subtract", List.of("small", "oxygen"));

        assertEquals(1, intersect.summary().memberCount());
        assertEquals(List.of("ethanol"), service.getMembers("oxygen_small", 0, 10).members().stream().map(SelectionAiService.SelectionMember::structureId).toList());
        assertEquals(3, union.summary().memberCount());
        assertEquals(List.of("ethylamine"), subtract.examples().stream().map(SelectionAiService.SelectionMember::structureId).toList());
    }

    @Test
    void combineSelectionsRequiresSameRepository() {
        StructureRepositoryService repositories = new InMemoryStructureRepositoryService();
        repositories.createRepository(new tech.molecules.structurized.ai.model.CreateRepositoryRequest("other", "Other", null, true));
        repositories.registerStructure(new RegisterStructureRequest("CCO", "session", "ethanol", "Ethanol", Map.of()));
        repositories.registerStructure(new RegisterStructureRequest("CCN", "other", "ethylamine", "Ethylamine", Map.of()));
        SelectionAiService service = new SelectionAiService(repositories);
        service.createSelection("session_sel", "session", "test", "session", List.of("ethanol"));
        service.createSelection("other_sel", "other", "test", "other", List.of("ethylamine"));

        ChemOperationException exception = assertThrows(
                ChemOperationException.class,
                () -> service.combineSelections("bad", "union", List.of("session_sel", "other_sel"))
        );

        assertEquals("selection_repository_mismatch", exception.code());
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
