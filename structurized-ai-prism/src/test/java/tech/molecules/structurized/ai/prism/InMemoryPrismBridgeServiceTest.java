package tech.molecules.structurized.ai.prism;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.molecules.structurized.ai.model.ChemOperationException;
import tech.molecules.structurized.ai.model.RegisterStructureRequest;
import tech.molecules.structurized.ai.model.SubstructureSearchRequest;
import tech.molecules.structurized.ai.repository.InMemoryStructureRepositoryService;
import tech.molecules.structurized.ai.repository.StructureRepositoryService;
import tech.molecules.structurized.ai.search.OclStructureSearchService;
import tech.molecules.structurized.ai.search.StructureSearchService;
import tech.molecules.structurized.prism.prediction.PredictionCapability;
import tech.molecules.structurized.prism.result.NumericResult;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void opensExamplePrismPackAndDescribesColumnsForAgents() throws Exception {
        Path pack = examplePrismPack();
        TestContext ctx = context();

        PrismSessionSummary opened = ctx.prism.openPack(new OpenPrismPackRequest(pack, "example_pack", "Example pack"));
        PrismSessionInfo info = ctx.prism.getSessionInfo("example_pack");
        List<PrismColumnSummary> columns = ctx.prism.listColumns("example_pack");
        PrismSessionAgentDescription description = ctx.prism.describeSessionForAgent("example_pack");
        PrismRowSetMembersView members = ctx.prism.getRowSetMembers("example_pack", "all", 0, 3);
        PrismRowSetStructureCollection structures = ctx.prism.rowSetStructures("example_pack", "all");
        PrismRowSetSummary potent = ctx.prism.createColumnRowSet(new CreatePrismColumnRowSetRequest(
                "example_pack", "all", "potent_example", "Potent example compounds", null,
                "pIC50", "numeric_range", 7.0, null,
                List.of(), null, null, null, false
        ));

        assertEquals("example_pack", opened.sessionId());
        assertEquals(3, opened.totalRowCount());
        assertEquals("example_pack", info.summary().sessionId());
        assertTrue(columns.stream().anyMatch(column -> column.columnId().equals("smiles")));
        assertTrue(description.structureColumns().stream().anyMatch(column -> column.columnId().equals("smiles")));
        assertTrue(description.endpointColumns().stream().anyMatch(column -> column.columnId().equals("pIC50")));
        assertTrue(description.semanticTypeCounts().containsKey("chemical_structure"));
        assertEquals(0, ctx.prism.listSubjectSets("example_pack").size());
        assertEquals(3, members.members().size());
        assertTrue(members.members().getFirst().fields().containsKey("prism.column.smiles"));
        assertEquals(3, structures.structureCount());
        assertEquals(1, potent.rowCount());
    }

    @Test
    void createsRuntimeColumnRowSetsWithEngineFilterSemantics() throws Exception {
        TestContext ctx = context();
        ctx.prism.openDataset(new OpenPrismDatasetRequest(prismDataset(), "demo", "Demo dataset"));

        PrismRowSetSummary numeric = ctx.prism.createColumnRowSet(new CreatePrismColumnRowSetRequest(
                "demo", "all", "potent_runtime", null, null,
                "pIC50", "numeric_range", 7.0, null,
                List.of(), null, null, null, false
        ));
        PrismRowSetSummary category = ctx.prism.createColumnRowSet(new CreatePrismColumnRowSetRequest(
                "demo", "all", "series_a_runtime", null, null,
                "series", "category_include", null, null,
                List.of("A"), null, null, null, false
        ));
        PrismRowSetSummary regex = ctx.prism.createColumnRowSet(new CreatePrismColumnRowSetRequest(
                "demo", "series_a_runtime", "regex_runtime", null, null,
                "subject_id", "text_pattern", null, null,
                List.of(), "CMP-00[12]", "regex", true, false
        ));
        PrismRowSetSummary missing = ctx.prism.createColumnRowSet(new CreatePrismColumnRowSetRequest(
                "demo", "all", "missing_structure_runtime", null, null,
                "smiles", "missing", null, null,
                List.of(), null, null, null, null
        ));

        assertEquals(1, numeric.rowCount());
        assertEquals(2, category.rowCount());
        assertEquals(2, regex.rowCount());
        assertEquals(1, missing.rowCount());
        assertEquals("CMP-001", ctx.prism.getRowSetMembers("demo", "potent_runtime", 0, 10).members().getFirst().rowId());
        assertEquals("CMP-003", ctx.prism.getRowSetMembers("demo", "missing_structure_runtime", 0, 10).members().getFirst().rowId());
    }

    @Test
    void clusteringReportsSkippedRowsAndRejectsAnEmptyStructureScope() throws Exception {
        TestContext ctx = context();
        ctx.prism.openDataset(new OpenPrismDatasetRequest(prismDataset(), "skip_demo", "Skip demo"));

        PrismClusteringSummary clustered = ctx.prism.clusterRowSet(new ClusterPrismRowSetRequest(
                "skip_demo", "all", "valid_only", null,
                null, 0.8, 1, false
        ));
        PrismRowSetSummary missing = ctx.prism.createColumnRowSet(new CreatePrismColumnRowSetRequest(
                "skip_demo", "all", "missing_only", null, null,
                "smiles", "missing", null, null,
                List.of(), null, null, null, null
        ));
        ChemOperationException exception = assertThrows(ChemOperationException.class,
                () -> ctx.prism.clusterRowSet(new ClusterPrismRowSetRequest(
                        "skip_demo", "missing_only", "empty", null,
                        null, null, null, true
                )));

        assertEquals(2, clustered.inputMoleculeCount());
        assertEquals(2, clustered.skippedRowCount());
        assertTrue(clustered.skippedRows().stream().anyMatch(row -> row.reason().equals("missing_structure")));
        assertTrue(clustered.skippedRows().stream().anyMatch(row -> row.reason().equals("invalid_structure")));
        assertEquals(1, missing.rowCount());
        assertEquals("no_clusterable_prism_rows", exception.code());
        assertEquals(1, ctx.prism.listAnalyses("skip_demo").size());
    }

    @Test
    void publishesClusteringAsAReusableGroupingEvenWhenColumnsStayHidden() throws Exception {
        TestContext ctx = context();
        ctx.prism.openDataset(new OpenPrismDatasetRequest(
                clusteringDataset(),
                "grouping_demo",
                "Grouping demo"
        ));

        PrismClusteringSummary clustered = ctx.prism.clusterRowSet(new ClusterPrismRowSetRequest(
                "grouping_demo",
                "all",
                "hidden_clusters",
                "Hidden clusters",
                null,
                1.0,
                2,
                false
        ));
        List<PrismGroupingSummary> summaries = ctx.prism.listGroupings("grouping_demo");
        PrismGroupingView grouping = ctx.prism.getGrouping(
                "grouping_demo",
                "hidden_clusters",
                0,
                10
        );
        String groupId = grouping.groups().getFirst().groupId();
        PrismRowSetSummary rows = ctx.prism.createGroupRowSet(new CreatePrismGroupRowSetRequest(
                "grouping_demo",
                "hidden_clusters",
                groupId,
                "generic_group_rows",
                null,
                null
        ));

        assertTrue(clustered.analysis().publishedColumnIds().isEmpty());
        assertEquals(1, summaries.size());
        assertEquals("hidden_clusters.cluster_id", summaries.getFirst().facetColumnId());
        assertEquals("EXCLUSIVE", summaries.getFirst().mode());
        assertEquals(clustered.clusterCount(), grouping.totalGroups());
        assertEquals(grouping.groups().getFirst().memberCount(), rows.rowCount());
        assertEquals("hidden_clusters", rows.provenance().get("groupingId"));
        assertTrue(ctx.prism.listColumns("grouping_demo").stream()
                .anyMatch(column -> column.columnId().equals("hidden_clusters.cluster_id")));
    }
    @Test
    void clustersPrismRowsByRowIdentityAndPublishesSessionProjections() throws Exception {
        TestContext ctx = context();
        ctx.prism.openDataset(new OpenPrismDatasetRequest(clusteringDataset(), "cluster_demo", "Cluster demo"));

        PrismClusteringSummary clustered = ctx.prism.clusterRowSet(new ClusterPrismRowSetRequest(
                "cluster_demo", "all", "rough", "Rough clusters",
                null, 1.0, 2, true
        ));
        PrismClusteringView view = ctx.prism.getClustering("cluster_demo", "rough", true, 0, 10);
        PrismClusterMembersView members = ctx.prism.getClusterMembers(
                "cluster_demo", "rough", "cluster_1", 0, 10
        );
        PrismRowSetSummary clusterSet = ctx.prism.createClusterRowSet(new CreatePrismClusterRowSetRequest(
                "cluster_demo", "rough", "cluster_1", "benzene_cluster", null, null
        ));
        PrismRowSetMembersView publishedMembers = ctx.prism.getRowSetMembers(
                "cluster_demo", "benzene_cluster", 0, 10
        );

        assertEquals(1L, clustered.analysis().sourceRevision());
        assertEquals(2L, clustered.analysis().resultRevision());
        assertEquals(List.of("rough.cluster_id", "rough.similarity_to_representative"),
                clustered.analysis().publishedColumnIds());
        assertEquals(3, clustered.inputMoleculeCount());
        assertEquals(2, clustered.clusterCount());
        assertEquals(1, ctx.prism.listAnalyses("cluster_demo").size());
        assertEquals(2, view.totalClusters());
        assertEquals(2, view.clusters().getFirst().size());
        assertEquals(List.of("ROW-A", "ROW-B"), members.members().stream().map(PrismClusterMember::rowId).toList());
        assertEquals(List.of("S-BENZENE", "S-BENZENE"),
                members.members().stream().map(PrismClusterMember::structureId).toList());
        assertEquals(2, clusterSet.rowCount());
        assertEquals("rough", clusterSet.provenance().get("analysisId"));
        assertEquals("cluster_1", publishedMembers.members().getFirst().fields().get("prism.column.rough.cluster_id"));
        assertEquals(3L, ctx.prism.getSessionInfo("cluster_demo").summary().revision());
    }
    @Test
    void minesMmpPairsAsReusablePrismGraph() throws Exception {
        TestContext ctx = context();
        ctx.prism.openDataset(new OpenPrismDatasetRequest(mmpDataset(), "mmp_demo", "MMP demo"));

        PrismMmpGraphSummary mined = ctx.prism.mineMmpGraph(new MinePrismMmpGraphRequest(
                "mmp_demo",
                "all",
                "smiles",
                "pIC50",
                "mmp_network",
                "MMP network",
                1,
                1,
                4,
                1.0,
                null,
                null
        ));
        List<PrismGraphSummary> graphs = ctx.prism.listGraphs("mmp_demo");
        PrismGraphSummary summary = ctx.prism.summarizeGraph("mmp_demo", "mmp_network");
        PrismGraphNeighborhood neighborhood = ctx.prism.inspectGraphNeighborhood(
                "mmp_demo", "mmp_network", "TOLUENE", 10);
        PrismRowSetSummary rowSet = ctx.prism.createGraphNeighborhoodRowSet(
                new CreatePrismGraphNeighborhoodRowSetRequest(
                        "mmp_demo", "mmp_network", "TOLUENE", true,
                        "toluene_mmp_neighbors", null, null));

        assertEquals("mmp_network", mined.graph().graphId());
        assertEquals("chemistry.mmp", mined.graph().graphType());
        assertEquals("structurized-mmp", mined.graph().pluginId());
        assertEquals(2, mined.validStructureCount());
        assertTrue(mined.pairCount() > 0);
        assertEquals(List.of("mmp_network"), graphs.stream().map(PrismGraphSummary::graphId).toList());
        assertEquals(mined.pairCount(), summary.edgeCount());
        assertEquals("TOLUENE", neighborhood.center().rowId());
        assertTrue(neighborhood.neighborCount() > 0);
        assertEquals("ETHYLBENZENE", neighborhood.neighbors().getFirst().row().rowId());
        assertFalse(neighborhood.neighbors().getFirst().edges().isEmpty());

        PrismGraphShortestPath distanceOnly = ctx.prism.findGraphShortestPath(
                "mmp_demo", "mmp_network", "TOLUENE", "ETHYLBENZENE", false, 0, 2);
        assertTrue(distanceOnly.connected());
        assertEquals(1, distanceOnly.distance());
        assertEquals("connected", distanceOnly.reason());
        assertTrue(distanceOnly.pathRows().isEmpty());
        assertTrue(distanceOnly.steps().isEmpty());

        PrismGraphShortestPath withPath = ctx.prism.findGraphShortestPath(
                "mmp_demo", "mmp_network", "TOLUENE", "ETHYLBENZENE", true, 0, 2);
        assertEquals(List.of("TOLUENE", "ETHYLBENZENE"), withPath.pathRows().stream().map(PrismRowMember::rowId).toList());
        assertEquals(1, withPath.steps().size());
        assertTrue(withPath.steps().getFirst().rawEdgeCount() > 0);
        assertFalse(withPath.steps().getFirst().exampleTransforms().isEmpty());

        PrismGraphShortestPath sameRow = ctx.prism.findGraphShortestPath(
                "mmp_demo", "mmp_network", "TOLUENE", "TOLUENE", true, 0, 2);
        assertTrue(sameRow.connected());
        assertEquals(0, sameRow.distance());
        assertEquals("same_row", sameRow.reason());
        assertEquals(List.of("TOLUENE"), sameRow.pathRows().stream().map(PrismRowMember::rowId).toList());

        assertEquals(2, rowSet.rowCount());
        assertEquals("mmp_network", rowSet.provenance().get("graphId"));
    }

    @Test
    void evaluatesPredictionsAsSessionArtifactsAndColumns() throws Exception {
        TestContext ctx = context();
        ctx.prism.openDataset(new OpenPrismDatasetRequest(prismDataset(), "prediction_demo", "Prediction demo"));

        List<PredictionCapability> capabilities = ctx.prism.listPredictionCapabilities("prediction_demo", "pIC50");
        assertTrue(capabilities.stream()
                .anyMatch(capability -> capability.capabilityId().equals("reference/pic50")));
        assertEquals("reference/pic50", ctx.prism.describePredictionCapability(
                "prediction_demo", "reference/pic50").workflowId());
        PredictionRunSummary summary = ctx.prism.evaluatePrismPrediction(new EvaluatePrismPredictionRequest(
                "prediction_demo",
                "all",
                "pred1",
                "Reference potency predictions",
                "pIC50",
                null,
                "ALL",
                true,
                true,
                true,
                true
        ));
        PredictionRunView run = ctx.prism.getPredictionRun("prediction_demo", "pred1", 0, 10);
        List<String> columnIds = ctx.prism.listColumns("prediction_demo").stream()
                .map(PrismColumnSummary::columnId)
                .toList();

        assertEquals("pred1", summary.analysis().analysisId());
        assertEquals("prediction_run", summary.analysis().type());
        assertEquals("reference/pic50", summary.capabilityId());
        assertEquals("reference", summary.providerId());
        assertEquals("reference/pic50", summary.workflowId());
        assertEquals(3, summary.inputCount());
        assertEquals(3, summary.valueCount());
        assertEquals(3, run.totalValues());
        assertTrue(columnIds.contains("pred1.pIC50_predicted.prediction"));
        assertTrue(columnIds.contains("pred1.pIC50_predicted.status"));
        assertTrue(columnIds.contains("pred1.pIC50_predicted.uncertainty"));
        assertTrue(columnIds.contains("pred1.pIC50_predicted.applicability"));
        assertEquals(1, ctx.prism.listAnalyses("prediction_demo").size());
        assertEquals(2L, ctx.prism.getSessionInfo("prediction_demo").summary().revision());
    }

    @Test
    void exposesPrismPackPredictionCapabilitiesThroughManagedSessions() throws Exception {
        TestContext ctx = context();
        ctx.prism.openPack(new OpenPrismPackRequest(predictionPack(), "apy_pack", "APY pack"));

        List<PredictionCapability> capabilities = ctx.prism.listPredictionCapabilities("apy_pack", "hlm_clint");
        PredictionCapability capability = ctx.prism.describePredictionCapability("apy_pack", "apy.hlm.production");

        assertEquals(1, capabilities.size());
        assertEquals("apy.hlm.production", capability.capabilityId());
        assertEquals("apy", capability.providerId());
        assertEquals("apy://hlm-production", capability.workflowId());
        assertEquals("hlm_clint.predicted", capability.predictedEndpointId());
        assertEquals("HLM", capability.metadata().get("assay"));
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

    @Test
    void managesOrderedMoleculeAndFragmentDocumentsInOneSessionChange() throws Exception {
        TestContext ctx = context();
        ctx.prism.openDataset(new OpenPrismDatasetRequest(prismDataset(), "demo", "Demo dataset"));
        ManagedPrismSession managed = ctx.registry.require("demo");
        long before = managed.revision();

        PrismMoleculeListSummary list = ctx.prism.createMoleculeList(
                new CreatePrismMoleculeListRequest("demo", "ideas", "Less-basic analogues"));
        long afterList = managed.revision();
        PrismMoleculeListView populated = ctx.prism.addMolecules(new AddPrismMoleculesRequest(
                "demo", list.listId(), List.of(
                new PrismMoleculeInput("Candidate", "molecule", "CCN"),
                new PrismMoleculeInput("Exit vector query", "fragment", "[c,n]1ccccc1[*]")
        )));

        assertEquals(before + 1, afterList);
        assertEquals(afterList + 1, managed.revision());
        assertEquals(List.of("Candidate", "Exit vector query"),
                populated.documents().stream().map(PrismMoleculeDocumentSummary::title).toList());
        assertEquals(List.of("molecule", "fragment"),
                populated.documents().stream().map(PrismMoleculeDocumentSummary::mode).toList());
        assertFalse(populated.documents().getFirst().structure().isBlank());
        assertFalse(populated.documents().getLast().structure().isBlank());
    }

    private TestContext context() {
        StructureRepositoryService repositories = new InMemoryStructureRepositoryService();
        InMemoryPrismSessionRegistry registry = new InMemoryPrismSessionRegistry();
        return new TestContext(
                repositories,
                new InMemoryPrismBridgeService(repositories, registry),
                new OclStructureSearchService(repositories),
                registry
        );
    }

    private static Path examplePrismPack() throws Exception {
        URL resource = InMemoryPrismBridgeServiceTest.class.getClassLoader()
                .getResource("prism-fixtures/example.prismpack/prism-pack.json");
        if (resource == null) {
            throw new IllegalStateException("Missing PrismPack test fixture");
        }
        return Path.of(resource.toURI()).getParent();
    }

    private Path clusteringDataset() throws Exception {
        Path dir = tempDir.resolve("clustering-prism-tsv");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("endpoints.prism.tsv"), String.join("\n",
                "endpoint_id\tname\tpath\tdatatype\tendpoint_type\tevaluation_mode\tunit\tscale\tdomain_lower_bound\tdomain_upper_bound\tdescription",
                ""
        ));
        Files.writeString(dir.resolve("subjects.prism.tsv"), String.join("\n",
                "subject_id\tstructure_id\tbatch_id\tproject\tseries\tsmiles",
                "ROW-A\tS-BENZENE\tB-001\tDemo\tA\tc1ccccc1",
                "ROW-B\tS-BENZENE\tB-002\tDemo\tA\tc1ccccc1",
                "ROW-C\tS-ETHANOL\tB-003\tDemo\tB\tCCO",
                ""
        ));
        Files.writeString(dir.resolve("values.prism.tsv"), String.join("\n",
                "subject_id\tendpoint_id\tstate\tmean\tn\traw_values",
                ""
        ));
        Files.writeString(dir.resolve("subject_sets.prism.tsv"), String.join("\n",
                "subject_set_id\tname\tset_type\tsubject_set_scope\tparent_set_id\tdescription",
                ""
        ));
        Files.writeString(dir.resolve("subject_set_memberships.prism.tsv"), String.join("\n",
                "subject_set_id\tsubject_id",
                ""
        ));
        return dir;
    }

    private Path mmpDataset() throws Exception {
        Path dir = tempDir.resolve("mmp-prism-tsv");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("endpoints.prism.tsv"), String.join("\n",
                "endpoint_id\tname\tpath\tdatatype\tendpoint_type\tevaluation_mode\tunit\tscale\tdomain_lower_bound\tdomain_upper_bound\tdescription",
                "pIC50\tpIC50\tassay/pIC50\tNUMERIC\tMEASURED\tIMMEDIATE\tpIC50\tLOG\t0\t14\tBiochemical potency",
                ""
        ));
        Files.writeString(dir.resolve("subjects.prism.tsv"), String.join("\n",
                "subject_id\tstructure_id\tbatch_id\tproject\tseries\tsmiles",
                "TOLUENE\tS-TOL\tB-001\tDemo\tA\tCc1ccccc1",
                "ETHYLBENZENE\tS-ETH\tB-002\tDemo\tA\tCCc1ccccc1",
                ""
        ));
        Files.writeString(dir.resolve("values.prism.tsv"), String.join("\n",
                "subject_id\tendpoint_id\tstate\tmean\tn\traw_values",
                "TOLUENE\tpIC50\tVALUE\t1.0\t1\t1.0",
                "ETHYLBENZENE\tpIC50\tVALUE\t3.5\t1\t3.5",
                ""
        ));
        Files.writeString(dir.resolve("subject_sets.prism.tsv"), String.join("\n",
                "subject_set_id\tname\tset_type\tsubject_set_scope\tparent_set_id\tdescription",
                ""
        ));
        Files.writeString(dir.resolve("subject_set_memberships.prism.tsv"), String.join("\n",
                "subject_set_id\tsubject_id",
                ""
        ));
        return dir;
    }

    private Path predictionPack() throws Exception {
        Path dir = tempDir.resolve("prediction-pack.prismpack");
        Files.createDirectories(dir.resolve("data"));
        Files.createDirectories(dir.resolve("schema"));
        Files.createDirectories(dir.resolve("semantics"));
        Files.writeString(dir.resolve("prism-pack.json"), """
                {"prismPackVersion":"0.2","dataframe":{"path":"data/dataframe.tsv","schema":"schema/dataframe.schema.json"},"molecules":"semantics/molecules.json","endpoints":"semantics/endpoints.json","predictions":"semantics/predictions.json"}
                """);
        Files.writeString(dir.resolve("schema/dataframe.schema.json"), """
                {"columns":[{"name":"compound_id","type":"string"},{"name":"smiles","type":"string","semanticType":"chemical_structure"},{"name":"hlm_clint","type":"number","endpointId":"hlm_clint"}]}
                """);
        Files.writeString(dir.resolve("data/dataframe.tsv"), """
                compound_id	smiles	hlm_clint
                CMP-1	CCN	12.0
                """);
        Files.writeString(dir.resolve("semantics/molecules.json"), """
                {"primaryStructureColumn":"smiles","structureFormat":"smiles","compoundIdColumn":"compound_id"}
                """);
        Files.writeString(dir.resolve("semantics/endpoints.json"), """
                {"endpoints":[{"id":"hlm_clint","column":"hlm_clint","displayName":"HLM CLint","unit":"uL/min/mg","direction":"lower_is_better"}]}
                """);
        Files.writeString(dir.resolve("semantics/predictions.json"), """
                {"capabilities":[{"capabilityId":"apy.hlm.production","endpointId":"hlm_clint","predictedEndpointId":"hlm_clint.predicted","displayName":"APY HLM production","providerId":"apy","workflowId":"apy://hlm-production","workflowVersion":"production","status":"available","priority":100,"structureColumn":"smiles","structureFormat":"smiles","metadata":{"assay":"HLM"}}]}
                """);
        return dir;
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

    private record TestContext(
            StructureRepositoryService repositories,
            PrismBridgeService prism,
            StructureSearchService search,
            InMemoryPrismSessionRegistry registry
    ) {}
}
