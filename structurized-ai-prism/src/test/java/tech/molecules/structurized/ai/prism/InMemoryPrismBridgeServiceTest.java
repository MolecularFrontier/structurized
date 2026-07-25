package tech.molecules.structurized.ai.prism;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.molecules.structurized.ai.model.RegisterStructureRequest;
import tech.molecules.structurized.ai.model.SubstructureSearchRequest;
import tech.molecules.structurized.ai.repository.InMemoryStructureRepositoryService;
import tech.molecules.structurized.ai.repository.StructureRepositoryService;
import tech.molecules.structurized.ai.search.OclStructureSearchService;
import tech.molecules.structurized.ai.search.StructureSearchService;
import tech.molecules.structurized.prism.result.NumericResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class InMemoryPrismBridgeServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsPrismDatasetAndListsSubjectSetsEndpointsAndSubjects() throws Exception {
        Path dataset = prismDataset();
        TestContext ctx = context();

        PrismDatasetSummary opened = ctx.prism.openDataset(new OpenPrismDatasetRequest(dataset, "demo", "Demo dataset"));
        PrismDatasetInfo info = ctx.prism.getDatasetInfo("demo");
        List<PrismSubjectSummary> subjects = ctx.prism.listSubjects("demo", "series:Kinase:A", 0, 10, true);

        assertEquals("demo", opened.datasetId());
        assertEquals(4, opened.subjectCount());
        assertEquals(3, opened.structureSubjectCount());
        assertTrue(info.subjectSets().stream().anyMatch(set -> set.subjectSetId().equals("series:Kinase:A")));
        assertEquals(List.of("pIC50"), info.endpoints().stream().map(PrismEndpointSummary::endpointId).toList());
        assertEquals(2, subjects.size());
        assertEquals("CMP-001", subjects.getFirst().subjectId());
        assertEquals("chemist-a", subjects.getFirst().metadata().get("chemist"));
    }

    @Test
    void opensManagedSessionAndCreatesSessionBackedRowSets() throws Exception {
        Path dataset = prismDataset();
        TestContext ctx = context();

        PrismDatasetSummary opened = ctx.prism.openDataset(new OpenPrismDatasetRequest(dataset, "demo", "Demo dataset"));
        PrismSessionInfo info = ctx.prism.getSessionInfo("demo");
        PrismRowSetSummary hits = ctx.prism.createRowSetFromSubjectSet(
                new CreatePrismRowSetFromSubjectSetRequest("demo", "hits", null, null, null));
        PrismRowSetSummary potent = ctx.prism.createEndpointRowSet(
                new CreatePrismEndpointRowSetRequest("demo", "pIC50", "potent", null, "gte", 7.0, null, null, null, null));
        PrismRowSetSummary combined = ctx.prism.combineRowSets(
                new CombinePrismRowSetsRequest("demo", "combined", "Combined", "", "intersect", List.of("hits", "potent")));
        PrismRowSetMembersView members = ctx.prism.getRowSetMembers("demo", "combined", 0, 10);
        PrismRowSetStructureCollection structures = ctx.prism.rowSetStructures("demo", "combined");

        assertEquals("demo", opened.sessionId());
        assertEquals("demo", opened.datasetId());
        assertEquals("demo", info.summary().sessionId());
        assertEquals(4, info.summary().totalRowCount());
        assertEquals("hits", hits.rowSetId());
        assertEquals(2, hits.rowCount());
        assertEquals(1, potent.rowCount());
        assertEquals(1, combined.rowCount());
        assertEquals("CMP-001", members.members().getFirst().rowId());
        assertEquals(1, structures.structureCount());
        assertEquals("c1ccncc1", structures.structures().getFirst().smiles());
    }

    @Test
    void opensMoonshotPrismPackAndDescribesColumnsForAgents() throws Exception {
        Path moonshot = moonshotPrismPack();
        assumeTrue(Files.isDirectory(moonshot), "Moonshot PrismPack example is not available in the sibling prism checkout.");
        TestContext ctx = context();

        PrismSessionSummary opened = ctx.prism.openPack(new OpenPrismPackRequest(moonshot, "moonshot", "Moonshot"));
        PrismSessionInfo info = ctx.prism.getSessionInfo("moonshot");
        List<PrismColumnSummary> columns = ctx.prism.listColumns("moonshot");
        PrismSessionAgentDescription description = ctx.prism.describeSessionForAgent("moonshot");
        PrismRowSetMembersView members = ctx.prism.getRowSetMembers("moonshot", "all", 0, 3);
        PrismRowSetStructureCollection structures = ctx.prism.rowSetStructures("moonshot", "all");

        assertEquals("moonshot", opened.sessionId());
        assertTrue(opened.totalRowCount() > 0);
        assertEquals("moonshot", info.summary().sessionId());
        assertTrue(columns.stream().anyMatch(column -> column.columnId().equals("smiles")));
        assertTrue(description.structureColumns().stream().anyMatch(column -> column.columnId().equals("smiles")));
        assertTrue(description.endpointColumns().stream().anyMatch(column -> column.columnId().equals("mpro_fluorescence_pIC50")));
        assertTrue(description.semanticTypeCounts().containsKey("chemical_structure"));
        assertEquals(0, ctx.prism.listSubjectSets("moonshot").size());
        assertEquals(3, members.members().size());
        assertTrue(members.members().getFirst().fields().containsKey("prism.column.smiles"));
        assertTrue(structures.structureCount() > 0);
    }

    @Test
    void materializesSubjectSetAsChemistryRepositoryAndKeepsEndpointValuesInPrism() throws Exception {
        Path dataset = prismDataset();
        TestContext ctx = context();
        ctx.prism.openDataset(new OpenPrismDatasetRequest(dataset, "demo", "Demo dataset"));

        MaterializePrismSubjectSetResult materialized = ctx.prism.materializeSubjectSet(
                new MaterializePrismSubjectSetRequest("demo", "series:Kinase:A", null, null)
        );

        assertEquals("prism:demo:series:Kinase:A", materialized.repositoryId());
        assertEquals(2, materialized.subjectsSeen());
        assertEquals(2, materialized.structuresImported());
        assertEquals(0, materialized.missingSmiles());
        assertEquals(0, materialized.invalidSmiles());
        assertEquals(2, ctx.repositories.listStructures(materialized.repositoryId(), 0, 10).size());
        assertEquals("Kinase", ctx.repositories.getStructure(ctx.repositories.listStructures(materialized.repositoryId(), 0, 10).getFirst().ref()).record().fields().get("prism.project"));

        var search = ctx.search.searchSubstructure(new SubstructureSearchRequest(
                "c1ccncc1", "smiles", List.of(materialized.repositoryId()), "all", 100, 1, true
        ));
        assertEquals(1, search.summary().matchingStructures());
        assertEquals("CMP-001", search.matches().getFirst().structureId());

        List<PrismEndpointValue> values = ctx.prism.getEndpointValues("demo", List.of("CMP-001"), List.of("pIC50"));
        assertEquals(1, values.size());
        NumericResult result = assertInstanceOf(NumericResult.class, values.getFirst().result());
        assertEquals(7.2, result.getMean());
    }

    @Test
    void materializationReportsMissingAndInvalidSmilesWithoutFailingImport() throws Exception {
        Path dataset = prismDataset();
        TestContext ctx = context();
        ctx.prism.openDataset(new OpenPrismDatasetRequest(dataset, "demo", "Demo dataset"));

        MaterializePrismSubjectSetResult materialized = ctx.prism.materializeSubjectSet(
                new MaterializePrismSubjectSetRequest("demo", null, "all_demo", "All demo")
        );

        assertEquals(4, materialized.subjectsSeen());
        assertEquals(2, materialized.structuresImported());
        assertEquals(1, materialized.missingSmiles());
        assertEquals(1, materialized.invalidSmiles());
        assertEquals(2, materialized.skippedSubjects().size());
        assertTrue(materialized.skippedSubjects().stream().anyMatch(skip -> skip.reason().equals("missing_smiles")));
        assertTrue(materialized.skippedSubjects().stream().anyMatch(skip -> skip.reason().equals("invalid_smiles")));
    }

    @Test
    void listDatasetsAndEndpointValuesAreDeterministic() throws Exception {
        Path dataset = prismDataset();
        TestContext ctx = context();
        ctx.prism.openDataset(new OpenPrismDatasetRequest(dataset, "demo", "Demo dataset"));

        assertEquals(List.of("demo"), ctx.prism.listDatasets().stream().map(PrismDatasetSummary::datasetId).toList());
        assertFalse(ctx.prism.getEndpointValues("demo", List.of("CMP-002"), List.of("pIC50")).isEmpty());
    }

    private TestContext context() {
        StructureRepositoryService repositories = new InMemoryStructureRepositoryService();
        return new TestContext(repositories, new InMemoryPrismBridgeService(repositories), new OclStructureSearchService(repositories));
    }

    private static Path moonshotPrismPack() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path[] candidates = {
                cwd.resolve("../prism/examples/moonshot-medchem.prismpack").normalize(),
                cwd.resolve("../../prism/examples/moonshot-medchem.prismpack").normalize()
        };
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return candidates[0];
    }

    private Path prismDataset() throws Exception {
        Path dir = tempDir.resolve("prism-tsv");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("endpoints.prism.tsv"), String.join("\n",
                "endpoint_id\tname\tpath\tdatatype\tendpoint_type\tevaluation_mode\tunit\tscale\tdomain_lower_bound\tdomain_upper_bound\tdescription",
                "pIC50\tpIC50\tassay/pIC50\tNUMERIC\tMEASURED\tIMMEDIATE\tpIC50\tLOG\t0\t14\tBiochemical potency",
                ""
        ));
        Files.writeString(dir.resolve("subjects.prism.tsv"), String.join("\n",
                "subject_id\tstructure_id\tbatch_id\tproject\tseries\tsmiles\tchemist",
                "CMP-001\tS-001\tB-001\tKinase\tA\tc1ccncc1\tchemist-a",
                "CMP-002\tS-002\tB-002\tKinase\tA\tCCN\tchemist-b",
                "CMP-003\tS-003\tB-003\tKinase\tB\t\tchemist-c",
                "CMP-004\tS-004\tB-004\tKinase\tB\tnotasmiles\tchemist-d",
                ""
        ));
        Files.writeString(dir.resolve("values.prism.tsv"), String.join("\n",
                "subject_id\tendpoint_id\tstate\tmean\tn\traw_values",
                "CMP-001\tpIC50\tVALUE\t7.2\t3\t7.1|7.2|7.3",
                "CMP-002\tpIC50\tVALUE\t6.1\t1\t6.1",
                "CMP-003\tpIC50\tNOT_MEASURED\t\t\t",
                ""
        ));
        Files.writeString(dir.resolve("subject_sets.prism.tsv"), String.join("\n",
                "subject_set_id\tname\tset_type\tsubject_set_scope\tparent_set_id\tdescription",
                "hits\tHits\tCUSTOM\tSCREEN\t\tPrimary hits",
                ""
        ));
        Files.writeString(dir.resolve("subject_set_memberships.prism.tsv"), String.join("\n",
                "subject_set_id\tsubject_id",
                "hits\tCMP-001",
                "hits\tCMP-002",
                ""
        ));
        return dir;
    }

    private record TestContext(StructureRepositoryService repositories, PrismBridgeService prism, StructureSearchService search) {}
}
