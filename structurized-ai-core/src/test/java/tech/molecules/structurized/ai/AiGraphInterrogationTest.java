package tech.molecules.structurized.ai;

import org.junit.jupiter.api.Test;
import tech.molecules.structurized.ai.inspect.OclStructureInspectionService;
import tech.molecules.structurized.ai.inspect.StructureInspectionService;
import tech.molecules.structurized.ai.model.AtomEnvironmentInspection;
import tech.molecules.structurized.ai.model.AtomRef;
import tech.molecules.structurized.ai.model.BoundaryAttachment;
import tech.molecules.structurized.ai.model.ChemOperationException;
import tech.molecules.structurized.ai.model.RegisterStructureRequest;
import tech.molecules.structurized.ai.model.RingSystemInspection;
import tech.molecules.structurized.ai.model.ShortestPathResult;
import tech.molecules.structurized.ai.model.StructureRecord;
import tech.molecules.structurized.ai.repository.InMemoryStructureRepositoryService;
import tech.molecules.structurized.ai.repository.StructureRepositoryService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiGraphInterrogationTest {

    @Test
    void atomEnvironmentReturnsInducedGraphAndBoundaryAttachments() {
        TestContext ctx = context("Cc1ccccc1");

        AtomEnvironmentInspection environment = ctx.inspections.inspectAtomEnvironment(new AtomRef(ctx.record.ref(), "a2"), 1);

        assertEquals("a2", environment.centerAtom());
        assertEquals(1, environment.radius());
        assertEquals(List.of("a1", "a2", "a3", "a7"), environment.atomIds());
        assertEquals(List.of("b1", "b2", "b7"), environment.bondIds());
        assertEquals("[?:1]C:C(C):C=[?:2]", environment.environmentSmiles());
        assertEquals(List.of(
                new BoundaryAttachment(1, "a3", "a4", "b3", 1),
                new BoundaryAttachment(2, "a7", "a6", "b6", 2)
        ), environment.boundaryAttachments());
    }

    @Test
    void atomEnvironmentSupportsRadiusZeroAndRejectsInvalidRadius() {
        TestContext ctx = context("Cc1ccccc1");

        AtomEnvironmentInspection environment = ctx.inspections.inspectAtomEnvironment(new AtomRef(ctx.record.ref(), "a2"), 0);

        assertEquals(List.of("a2"), environment.atomIds());
        assertEquals(List.of(), environment.bondIds());
        assertEquals("[?:3]C([?:1])=[?:2]", environment.environmentSmiles());
        assertEquals(3, environment.boundaryAttachments().size());

        ChemOperationException error = assertThrows(
                ChemOperationException.class,
                () -> ctx.inspections.inspectAtomEnvironment(new AtomRef(ctx.record.ref(), "a2"), 5)
        );
        assertEquals("invalid_radius", error.code());
    }

    @Test
    void benzeneRingSystemIncludesAllRingAtomsAndMappedExternalAttachment() {
        TestContext ctx = context("Cc1ccccc1");

        RingSystemInspection ring = ctx.inspections.inspectRingSystem(new AtomRef(ctx.record.ref(), "a2"));

        assertTrue(ring.inRingSystem());
        assertEquals("a2", ring.atomId());
        assertEquals(List.of("a2", "a3", "a4", "a5", "a6", "a7"), ring.atomIds());
        assertEquals(List.of("b2", "b3", "b4", "b5", "b6", "b7"), ring.bondIds());
        assertEquals(List.of(6), ring.ringSizes());
        assertEquals(6, ring.aromaticAtomCount());
        assertEquals(6, ring.aromaticBondCount());
        assertEquals("[?:1]c1ccccc1", ring.ringSystemSmiles());
        assertEquals(1, ring.attachments().size());
        assertEquals("a2", ring.attachments().getFirst().ringAtom());
        assertEquals("a1", ring.attachments().getFirst().externalAtom());
        assertEquals("b1", ring.attachments().getFirst().bondId());
        assertEquals("ring_atoms_connected_by_ring_bonds", ring.algorithm());
    }

    @Test
    void biphenylHasSeparateRingSystems() {
        TestContext ctx = context("c1ccccc1-c2ccccc2");

        RingSystemInspection first = ctx.inspections.inspectRingSystem(new AtomRef(ctx.record.ref(), "a1"));
        RingSystemInspection second = ctx.inspections.inspectRingSystem(new AtomRef(ctx.record.ref(), "a7"));

        assertEquals(List.of("a1", "a2", "a3", "a4", "a5", "a6"), first.atomIds());
        assertEquals(List.of("a7", "a8", "a9", "a10", "a11", "a12"), second.atomIds());
        assertEquals(1, first.attachments().size());
        assertEquals("a6", first.attachments().getFirst().ringAtom());
        assertEquals("a7", first.attachments().getFirst().externalAtom());
    }

    @Test
    void naphthaleneFusedRingsAreOneRingSystemWithJunctionAtoms() {
        TestContext ctx = context("c1ccc2ccccc2c1");

        RingSystemInspection ring = ctx.inspections.inspectRingSystem(new AtomRef(ctx.record.ref(), "a1"));

        assertTrue(ring.inRingSystem());
        assertEquals(10, ring.atomIds().size());
        assertEquals(11, ring.bondIds().size());
        assertEquals(List.of("a4", "a9"), ring.junctionAtoms());
        assertEquals(List.of(6), ring.ringSizes());
        assertEquals(List.of(), ring.attachments());
        assertEquals("c(cc1)cc2c1cccc2", ring.ringSystemSmiles());
    }

    @Test
    void acyclicAtomReturnsNoRingSystem() {
        TestContext ctx = context("CCO");

        RingSystemInspection ring = ctx.inspections.inspectRingSystem(new AtomRef(ctx.record.ref(), "a2"));

        assertFalse(ring.inRingSystem());
        assertEquals("a2", ring.atomId());
        assertEquals(List.of(), ring.atomIds());
        assertNull(ring.ringSystemSmiles());
    }

    @Test
    void shortestPathReportsDeterministicPathAndRotatableCandidates() {
        TestContext ctx = context("CCCO");

        ShortestPathResult path = ctx.inspections.findShortestPath(
                new AtomRef(ctx.record.ref(), "a1"),
                new AtomRef(ctx.record.ref(), "a4")
        );

        assertEquals(3, path.topologicalDistance());
        assertEquals(List.of("a1", "a2", "a3", "a4"), path.atomPath());
        assertEquals(List.of("b1", "b2", "b3"), path.bondPath());
        assertEquals(List.of("b2"), path.rotatableCandidateBonds());
        assertEquals(0, path.ringSystemTransitions());
        assertFalse(path.alternativeShortestPathsExist());
    }

    @Test
    void shortestPathReportsAlternativeRingPaths() {
        TestContext ctx = context("c1ccccc1");

        ShortestPathResult path = ctx.inspections.findShortestPath(
                new AtomRef(ctx.record.ref(), "a1"),
                new AtomRef(ctx.record.ref(), "a4")
        );

        assertEquals(3, path.topologicalDistance());
        assertEquals(List.of("a1", "a2", "a3", "a4"), path.atomPath());
        assertTrue(path.alternativeShortestPathsExist());
    }

    @Test
    void shortestPathRejectsDisconnectedComponents() {
        TestContext ctx = context("CC.O");

        ChemOperationException error = assertThrows(
                ChemOperationException.class,
                () -> ctx.inspections.findShortestPath(new AtomRef(ctx.record.ref(), "a1"), new AtomRef(ctx.record.ref(), "a3"))
        );

        assertEquals("atoms_not_connected", error.code());
    }

    @Test
    void graphInterrogationOutputIsDeterministicAcrossRepeatedCalls() {
        TestContext ctx = context("Cc1ccccc1");

        AtomRef atom = new AtomRef(ctx.record.ref(), "a2");
        AtomEnvironmentInspection firstEnvironment = ctx.inspections.inspectAtomEnvironment(atom, 1);
        AtomEnvironmentInspection secondEnvironment = ctx.inspections.inspectAtomEnvironment(atom, 1);
        RingSystemInspection firstRing = ctx.inspections.inspectRingSystem(atom);
        RingSystemInspection secondRing = ctx.inspections.inspectRingSystem(atom);

        assertEquals(firstEnvironment, secondEnvironment);
        assertEquals(firstRing, secondRing);
    }

    private static TestContext context(String smiles) {
        StructureRepositoryService repositories = new InMemoryStructureRepositoryService();
        StructureRecord record = repositories.registerStructure(new RegisterStructureRequest(smiles));
        return new TestContext(record, new OclStructureInspectionService(repositories));
    }

    private record TestContext(StructureRecord record, StructureInspectionService inspections) {}
}
