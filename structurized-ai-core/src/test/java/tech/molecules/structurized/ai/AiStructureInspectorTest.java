package tech.molecules.structurized.ai;

import org.junit.jupiter.api.Test;
import tech.molecules.structurized.ai.inspect.OclStructureInspectionService;
import tech.molecules.structurized.ai.inspect.StructureInspectionService;
import tech.molecules.structurized.ai.model.AtomInspection;
import tech.molecules.structurized.ai.model.AtomRef;
import tech.molecules.structurized.ai.model.BondInspection;
import tech.molecules.structurized.ai.model.BondRef;
import tech.molecules.structurized.ai.model.ChemOperationException;
import tech.molecules.structurized.ai.model.RegisterStructureRequest;
import tech.molecules.structurized.ai.model.StructureInspection;
import tech.molecules.structurized.ai.model.StructureRecord;
import tech.molecules.structurized.ai.model.StructureRef;
import tech.molecules.structurized.ai.render.CompactStructureRenderer;
import tech.molecules.structurized.ai.repository.InMemoryStructureRepositoryService;
import tech.molecules.structurized.ai.repository.StructureRepositoryService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiStructureInspectorTest {

    @Test
    void registersStructureIntoDefaultRepositoryWithStableIds() {
        StructureRepositoryService repositories = new InMemoryStructureRepositoryService();
        StructureRecord record = repositories.registerStructure(new RegisterStructureRequest(
                "c1ccccc1",
                null,
                null,
                "benzene",
                Map.of("source", "unit-test")
        ));
        StructureInspectionService inspections = new OclStructureInspectionService(repositories);

        StructureInspection inspection = inspections.inspectStructure(record.ref());

        assertEquals("session", record.repositoryId());
        assertEquals("s1", record.structureId());
        assertEquals(1, record.componentCount());
        assertEquals(6, record.atomCount());
        assertEquals(6, record.bondCount());
        assertEquals("a1", inspection.atoms().getFirst().atomId());
        assertEquals("b1", inspection.bonds().getFirst().bondId());
        assertTrue(inspection.atoms().stream().allMatch(AtomInspection::aromatic));
        assertTrue(inspection.bonds().stream().allMatch(BondInspection::ringBond));
    }

    @Test
    void focusedAtomAndBondInspectionUseSnapshotIds() {
        StructureRepositoryService repositories = new InMemoryStructureRepositoryService();
        StructureRecord record = repositories.registerStructure(new RegisterStructureRequest("CCO"));
        StructureInspectionService inspections = new OclStructureInspectionService(repositories);
        StructureRef ref = record.ref();

        AtomInspection oxygen = inspections.inspectAtom(new AtomRef(ref, "a3"));
        BondInspection carbonOxygen = inspections.inspectBond(new BondRef(ref, "b2"));

        assertEquals("O", oxygen.element());
        assertEquals(8, oxygen.atomicNumber());
        assertEquals("c1", oxygen.componentId());
        assertEquals(1, oxygen.heavyAtomDegree());
        assertEquals("a2", oxygen.neighborAtoms().getFirst());
        assertEquals("a2", carbonOxygen.atom1());
        assertEquals("a3", carbonOxygen.atom2());
        assertEquals(1, carbonOxygen.order());
        assertFalse(carbonOxygen.ringBond());
        assertNull(carbonOxygen.smallestRingSize());
    }

    @Test
    void disconnectedComponentsAreReportedBySize() {
        StructureRepositoryService repositories = new InMemoryStructureRepositoryService();
        StructureRecord record = repositories.registerStructure(new RegisterStructureRequest("CCO.O"));
        StructureInspection inspection = new OclStructureInspectionService(repositories).inspectStructure(record.ref());

        assertEquals(2, inspection.components().size());
        assertEquals("c1", inspection.components().get(0).componentId());
        assertEquals(3, inspection.components().get(0).heavyAtomCount());
        assertEquals("c2", inspection.components().get(1).componentId());
        assertEquals(1, inspection.components().get(1).heavyAtomCount());
        assertEquals("c2", inspection.atoms().get(3).componentId());
    }

    @Test
    void compactRendererEmitsCompleteGraphTables() {
        StructureRepositoryService repositories = new InMemoryStructureRepositoryService();
        StructureRecord record = repositories.registerStructure(new RegisterStructureRequest("CCO"));
        StructureInspection inspection = new OclStructureInspectionService(repositories).inspectStructure(record.ref());

        String compact = new CompactStructureRenderer().render(inspection);

        assertTrue(compact.contains("STRUCTURE session:s1"));
        assertTrue(compact.contains("COMPONENTS"));
        assertTrue(compact.contains("ATOMS"));
        assertTrue(compact.contains("BONDS"));
        assertTrue(compact.contains("a1  C"));
        assertTrue(compact.contains("b1  a1-a2"));
    }

    @Test
    void rejectsInvalidSmilesWithChemErrorCode() {
        StructureRepositoryService repositories = new InMemoryStructureRepositoryService();

        ChemOperationException error = assertThrows(
                ChemOperationException.class,
                () -> repositories.registerStructure(new RegisterStructureRequest("not_a_smiles"))
        );

        assertEquals("invalid_smiles", error.code());
        assertNotNull(error.getCause());
    }

    @Test
    void rejectsUnknownAtomAndDuplicateStructureId() {
        StructureRepositoryService repositories = new InMemoryStructureRepositoryService();
        StructureRecord record = repositories.registerStructure(new RegisterStructureRequest(
                "CCO",
                "session",
                "ethanol",
                null,
                Map.of()
        ));
        StructureInspectionService inspections = new OclStructureInspectionService(repositories);

        assertThrows(
                ChemOperationException.class,
                () -> inspections.inspectAtom(new AtomRef(record.ref(), "a99"))
        );
        ChemOperationException duplicate = assertThrows(
                ChemOperationException.class,
                () -> repositories.registerStructure(new RegisterStructureRequest("CC", "session", "ethanol", null, Map.of()))
        );
        assertEquals("duplicate_structure_id", duplicate.code());
    }
}
