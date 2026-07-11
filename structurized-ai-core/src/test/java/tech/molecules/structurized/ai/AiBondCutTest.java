package tech.molecules.structurized.ai;

import org.junit.jupiter.api.Test;
import tech.molecules.structurized.ai.inspect.OclStructureInspectionService;
import tech.molecules.structurized.ai.inspect.StructureInspectionService;
import tech.molecules.structurized.ai.model.BondCutResult;
import tech.molecules.structurized.ai.model.ChemOperationException;
import tech.molecules.structurized.ai.model.CutBondsRequest;
import tech.molecules.structurized.ai.model.CutFragment;
import tech.molecules.structurized.ai.model.CutFragmentAttachment;
import tech.molecules.structurized.ai.model.RegisterStructureRequest;
import tech.molecules.structurized.ai.model.StructureRecord;
import tech.molecules.structurized.ai.repository.InMemoryStructureRepositoryService;
import tech.molecules.structurized.ai.repository.StructureRepositoryService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiBondCutTest {

    @Test
    void cutsSingleAcyclicBondIntoTwoMappedFragments() {
        TestContext ctx = context("CCOC");

        BondCutResult result = ctx.inspections.cutBonds(new CutBondsRequest(ctx.record.ref(), List.of("b2")));

        assertEquals(List.of(), result.warnings());
        assertEquals(1, result.cuts().size());
        assertEquals("b2", result.cuts().getFirst().bondId());
        assertEquals(1, result.cuts().getFirst().attachmentId());
        assertFalse(result.cuts().getFirst().ringBond());
        assertFalse(result.cuts().getFirst().aromatic());
        assertEquals(2, result.fragments().size());

        CutFragment ethyl = result.fragments().get(0);
        assertEquals("f1", ethyl.fragmentId());
        assertEquals(List.of("a1", "a2"), ethyl.atomIds());
        assertEquals(List.of("b1"), ethyl.bondIds());
        assertEquals("[?:1]CC", ethyl.smiles());
        assertEquals(List.of(new CutFragmentAttachment(1, "a2", "a3", "b2")), ethyl.attachments());

        CutFragment methoxy = result.fragments().get(1);
        assertEquals(List.of("a3", "a4"), methoxy.atomIds());
        assertEquals("[?:1]OC", methoxy.smiles());
        assertEquals(List.of(new CutFragmentAttachment(1, "a3", "a2", "b2")), methoxy.attachments());
    }

    @Test
    void twoAcyclicCutsExtractMiddleSegment() {
        TestContext ctx = context("CCOCC");

        BondCutResult result = ctx.inspections.cutBonds(new CutBondsRequest(ctx.record.ref(), List.of("b2", "b3")));

        assertEquals(List.of(), result.warnings());
        assertEquals(2, result.cuts().size());
        assertEquals(3, result.fragments().size());
        assertEquals(List.of("a1", "a2"), result.fragments().get(0).atomIds());
        assertEquals(List.of("a4", "a5"), result.fragments().get(1).atomIds());
        assertEquals(List.of("a3"), result.fragments().get(2).atomIds());
        assertEquals("[?:2]O[?:1]", result.fragments().get(2).smiles());
        assertEquals(List.of(
                new CutFragmentAttachment(1, "a3", "a2", "b2"),
                new CutFragmentAttachment(2, "a3", "a4", "b3")
        ), result.fragments().get(2).attachments());
    }

    @Test
    void preExistingDisconnectedComponentsArePreservedAndWarned() {
        TestContext ctx = context("CC.O");

        BondCutResult result = ctx.inspections.cutBonds(new CutBondsRequest(ctx.record.ref(), List.of("b1")));

        assertTrue(result.warnings().contains("Parent structure contains pre-existing disconnected components."));
        assertEquals(3, result.fragments().size());
        assertEquals(List.of("a3"), result.fragments().get(2).atomIds());
        assertEquals("O", result.fragments().get(2).smiles());
        assertEquals(List.of(), result.fragments().get(2).attachments());
    }

    @Test
    void rejectsDuplicateAndUnknownBondIds() {
        TestContext ctx = context("CCOC");

        ChemOperationException duplicate = assertThrows(
                ChemOperationException.class,
                () -> ctx.inspections.cutBonds(new CutBondsRequest(ctx.record.ref(), List.of("b2", "b2")))
        );
        assertEquals("duplicate_bond_id", duplicate.code());

        ChemOperationException unknown = assertThrows(
                ChemOperationException.class,
                () -> ctx.inspections.cutBonds(new CutBondsRequest(ctx.record.ref(), List.of("b99")))
        );
        assertEquals("bond_not_found", unknown.code());
    }

    @Test
    void singleRingBondCutIsAcceptedAndReportsNonDisconnectingFragment() {
        TestContext ctx = context("C1CCCCC1");

        BondCutResult result = ctx.inspections.cutBonds(new CutBondsRequest(ctx.record.ref(), List.of("b1")));

        assertEquals(1, result.fragments().size());
        assertEquals(List.of("a1", "a2", "a3", "a4", "a5", "a6"), result.fragments().getFirst().atomIds());
        assertEquals(List.of("b2", "b3", "b4", "b5", "b6"), result.fragments().getFirst().bondIds());
        assertEquals("[?:1]C1C([?:1])CCCC1", result.fragments().getFirst().smiles());
        assertEquals(List.of(
                new CutFragmentAttachment(1, "a1", "a2", "b1"),
                new CutFragmentAttachment(1, "a2", "a1", "b1")
        ), result.fragments().getFirst().attachments());
        assertTrue(result.warnings().contains("Cut bond b1 is a ring bond and did not disconnect the graph by itself."));
        assertTrue(result.warnings().contains("Requested cuts did not increase connected component count."));
    }

    @Test
    void twoRingBondCutsCanDisconnectRingIntoTwoFragments() {
        TestContext ctx = context("C1CCCCC1");

        BondCutResult result = ctx.inspections.cutBonds(new CutBondsRequest(ctx.record.ref(), List.of("b1", "b4")));

        assertEquals(2, result.fragments().size());
        assertEquals(List.of("a1", "a5", "a6"), result.fragments().get(0).atomIds());
        assertEquals(List.of("a2", "a3", "a4"), result.fragments().get(1).atomIds());
        assertEquals("[?:2]CCC[?:1]", result.fragments().get(0).smiles());
        assertEquals("[?:2]CCC[?:1]", result.fragments().get(1).smiles());
        assertFalse(result.warnings().contains("Requested cuts did not increase connected component count."));
    }

    @Test
    void aromaticRingBondCutIsAcceptedWithWarnings() {
        TestContext ctx = context("c1ccccc1");

        BondCutResult result = ctx.inspections.cutBonds(new CutBondsRequest(ctx.record.ref(), List.of("b1")));

        assertEquals(1, result.fragments().size());
        assertTrue(result.cuts().getFirst().ringBond());
        assertTrue(result.cuts().getFirst().aromatic());
        assertEquals("[?:1]=c(cccc1)c1=[?:1]", result.fragments().getFirst().smiles());
        assertTrue(result.warnings().contains("Cut bond b1 is aromatic or delocalized; fragment SMILES may require special interpretation."));
    }

    @Test
    void cutOutputIsDeterministicAcrossRepeatedCalls() {
        TestContext ctx = context("C1CCCCC1");
        CutBondsRequest request = new CutBondsRequest(ctx.record.ref(), List.of("b1", "b4"));

        BondCutResult first = ctx.inspections.cutBonds(request);
        BondCutResult second = ctx.inspections.cutBonds(request);

        assertEquals(first, second);
    }

    private static TestContext context(String smiles) {
        StructureRepositoryService repositories = new InMemoryStructureRepositoryService();
        StructureRecord record = repositories.registerStructure(new RegisterStructureRequest(smiles));
        return new TestContext(record, new OclStructureInspectionService(repositories));
    }

    private record TestContext(StructureRecord record, StructureInspectionService inspections) {}
}
