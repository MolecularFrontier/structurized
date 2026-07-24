package tech.molecules.structurized.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpJsonRpcHandlerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void initializeReturnsServerCapabilities() throws Exception {
        McpJsonRpcHandler handler = McpJsonRpcHandler.createDefault();

        JsonNode response = call(handler, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}} ");

        assertEquals("2.0", response.get("jsonrpc").asText());
        assertEquals("2024-11-05", response.at("/result/protocolVersion").asText());
        assertEquals("structurized-ai-mcp", response.at("/result/serverInfo/name").asText());
        assertEquals("0.2.1", response.at("/result/serverInfo/version").asText());
        assertTrue(response.at("/result/capabilities/tools").isObject());
    }

    @Test
    void initializedNotificationHasNoResponse() throws Exception {
        McpJsonRpcHandler handler = McpJsonRpcHandler.createDefault();

        String response = handler.handleJson("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

        assertNull(response);
    }

    @Test
    void toolsListExposesCoreChemistryTools() throws Exception {
        McpJsonRpcHandler handler = McpJsonRpcHandler.createDefault();

        JsonNode response = call(handler, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
        JsonNode tools = response.at("/result/tools");

        assertEquals(47, tools.size());
        assertTrue(hasTool(tools, "register_structure"));
        assertTrue(hasTool(tools, "inspect_structure"));
        assertTrue(hasTool(tools, "list_artifacts"));
        assertTrue(hasTool(tools, "get_artifact_info"));
        assertTrue(hasTool(tools, "get_structurized_tool_guide"));
        assertTrue(hasTool(tools, "search_substructure"));
        assertTrue(hasTool(tools, "cut_bonds"));
        assertTrue(hasTool(tools, "open_prism_dataset"));
        assertTrue(hasTool(tools, "materialize_prism_subject_set"));
        assertTrue(hasTool(tools, "create_decomposition_config"));
        assertTrue(hasTool(tools, "evaluate_decomposition"));
        assertTrue(hasTool(tools, "get_decomposition_result"));
        assertTrue(hasTool(tools, "get_decomposition_fragment_summary"));
        assertTrue(hasTool(tools, "get_decomposition_fragment_histogram"));
        assertTrue(hasTool(tools, "cluster_structures"));
        assertTrue(hasTool(tools, "list_clusterings"));
        assertTrue(hasTool(tools, "get_clustering"));
        assertTrue(hasTool(tools, "get_cluster"));
        assertTrue(hasTool(tools, "get_cluster_members"));
        assertTrue(hasTool(tools, "get_selection"));
        assertTrue(hasTool(tools, "create_endpoint_selection"));
        assertTrue(hasTool(tools, "combine_selections"));
        assertTrue(hasTool(tools, "get_selection_members"));
        assertTrue(hasTool(tools, "summarize_selection_by_endpoint"));
        assertTrue(hasTool(tools, "summarize_clusters_by_endpoint"));
        assertTrue(toolDescription(tools, "validate_decomposition_config").contains("SMARTS compilation"));
        assertTrue(toolDescription(tools, "create_decomposition_config").contains("zero-based SMARTS query atom indices"));
        assertEquals("object", tools.get(0).at("/inputSchema/type").asText());
    }

    @Test
    void canClusterStructuresThroughToolCalls() throws Exception {
        McpJsonRpcHandler handler = McpJsonRpcHandler.createDefault();
        call(handler, request(1, "register_structure", """
                {"smiles":"c1ccccc1","structure_id":"benzene_a","label":"Benzene A"}
                """));
        call(handler, request(2, "register_structure", """
                {"smiles":"CCO","structure_id":"ethanol","label":"Ethanol"}
                """));
        call(handler, request(3, "register_structure", """
                {"smiles":"c1ccccc1","structure_id":"benzene_b","label":"Benzene B"}
                """));

        JsonNode clustered = call(handler, request(4, "cluster_structures", """
                {"clustering_id":"rough1","repository_id":"session","threshold":1.0,"max_cross_neighbors":2}
                """));
        assertEquals("rough1", clustered.at("/result/structuredContent/clusteringId").asText());
        assertEquals("skelspheres", clustered.at("/result/structuredContent/descriptor").asText());
        assertEquals("greedy_leaders", clustered.at("/result/structuredContent/strategy").asText());
        assertEquals(3, clustered.at("/result/structuredContent/moleculeCount").asInt());
        assertEquals(2, clustered.at("/result/structuredContent/clusterCount").asInt());

        JsonNode listed = call(handler, request(5, "list_clusterings", "{}"));
        assertEquals("rough1", listed.at("/result/structuredContent/0/clusteringId").asText());

        JsonNode summary = call(handler, request(6, "get_clustering", """
                {"clustering_id":"rough1","include_singletons":true}
                """));
        assertEquals("cluster_1", summary.at("/result/structuredContent/clusters/0/clusterId").asText());
        assertEquals("benzene_a", summary.at("/result/structuredContent/clusters/0/representativeStructureId").asText());
        assertEquals(2, summary.at("/result/structuredContent/clusters/0/size").asInt());

        JsonNode cluster = call(handler, request(7, "get_cluster", """
                {"clustering_id":"rough1","cluster_id":"cluster_1"}
                """));
        assertEquals("cluster_1", cluster.at("/result/structuredContent/cluster/clusterId").asText());
        assertEquals("benzene_b", cluster.at("/result/structuredContent/cluster/exampleMembers/1/structureId").asText());
        assertEquals(1.0, cluster.at("/result/structuredContent/cluster/exampleMembers/1/similarityToRepresentative").asDouble());

        JsonNode members = call(handler, request(8, "get_cluster_members", """
                {"clustering_id":"rough1","cluster_id":"cluster_1","limit":1,"create_selection":true,"selection_id":"benzene_cluster"}
                """));
        assertEquals(2, members.at("/result/structuredContent/cluster/totalMembers").asInt());
        assertEquals(1, members.at("/result/structuredContent/cluster/members").size());
        assertEquals("benzene_cluster", members.at("/result/structuredContent/selection/selectionId").asText());

        JsonNode fileMembers = call(handler, request(9, "get_cluster_members", """
                {"clustering_id":"rough1","cluster_id":"cluster_1","limit":2,"output_target":"file","output_name":"clusters/benzene.json"}
                """));
        Path memberArtifact = Path.of(fileMembers.at("/result/structuredContent/artifact/path").asText());
        assertTrue(Files.exists(memberArtifact));
        assertEquals("clusters/benzene.json", fileMembers.at("/result/structuredContent/artifact/relativePath").asText());
        assertEquals(2, fileMembers.at("/result/structuredContent/summary/returnedMembers").asInt());
        assertTrue(fileMembers.at("/result/structuredContent/cluster/members").isMissingNode());
        assertEquals("benzene_b", mapper.readTree(memberArtifact.toFile()).at("/cluster/members/1/structureId").asText());
    }

    @Test
    void clusterEndpointSummaryDefaultsToPagedNonSingletonResponse() throws Exception {
        McpJsonRpcHandler handler = McpJsonRpcHandler.createDefault();
        Path dataset = prismDataset();
        String path = dataset.toString().replace("\\", "\\\\");
        call(handler, request(1, "open_prism_dataset", "{\"path\":\"" + path + "\",\"dataset_id\":\"demo\"}"));
        call(handler, request(2, "register_structure", """
                {"smiles":"c1ccncc1","structure_id":"pyridine_a","fields":{"prism.subject_id":"CMP-001"}}
                """));
        call(handler, request(3, "register_structure", """
                {"smiles":"c1ccncc1","structure_id":"pyridine_b","fields":{"prism.subject_id":"CMP-001"}}
                """));
        call(handler, request(4, "register_structure", """
                {"smiles":"CCO","structure_id":"ethanol","fields":{"prism.subject_id":"CMP-002"}}
                """));
        call(handler, request(5, "cluster_structures", """
                {"clustering_id":"endpoint_clusters","repository_id":"session","threshold":1.0}
                """));

        JsonNode defaultSummary = call(handler, request(6, "summarize_clusters_by_endpoint", """
                {"clustering_id":"endpoint_clusters","dataset_id":"demo","endpoint_id":"pIC50"}
                """));
        assertFalse(defaultSummary.at("/result/structuredContent/includeSingletons").asBoolean());
        assertEquals(1, defaultSummary.at("/result/structuredContent/totalClusters").asInt());
        assertEquals(1, defaultSummary.at("/result/structuredContent/returnedClusters").asInt());
        assertEquals(50, defaultSummary.at("/result/structuredContent/limit").asInt());
        assertEquals(2, defaultSummary.at("/result/structuredContent/clusters/0/size").asInt());

        JsonNode pagedWithSingletons = call(handler, request(7, "summarize_clusters_by_endpoint", """
                {"clustering_id":"endpoint_clusters","dataset_id":"demo","endpoint_id":"pIC50","include_singletons":true,"offset":1,"limit":1}
                """));
        assertTrue(pagedWithSingletons.at("/result/structuredContent/includeSingletons").asBoolean());
        assertEquals(2, pagedWithSingletons.at("/result/structuredContent/totalClusters").asInt());
        assertEquals(1, pagedWithSingletons.at("/result/structuredContent/returnedClusters").asInt());
        assertEquals(1, pagedWithSingletons.at("/result/structuredContent/offset").asInt());
        assertEquals(1, pagedWithSingletons.at("/result/structuredContent/limit").asInt());
        assertEquals(1, pagedWithSingletons.at("/result/structuredContent/clusters/0/size").asInt());

        JsonNode fileSummary = call(handler, request(8, "summarize_clusters_by_endpoint", """
                {"clustering_id":"endpoint_clusters","dataset_id":"demo","endpoint_id":"pIC50","include_singletons":true,"limit":1,"output_target":"file","output_name":"summaries/endpoint-clusters.json"}
                """));
        assertEquals(2, fileSummary.at("/result/structuredContent/summary/totalClusters").asInt());
        assertEquals(1, fileSummary.at("/result/structuredContent/summary/returnedClusters").asInt());
        assertTrue(fileSummary.at("/result/structuredContent/clusters").isMissingNode());
        Path artifact = Path.of(fileSummary.at("/result/structuredContent/artifact/path").asText());
        assertEquals(2, mapper.readTree(artifact.toFile()).at("/clusters").size());
    }

    @Test
    void canUseEmbeddedGuideAndRichDecompositionValidation() throws Exception {
        McpJsonRpcHandler handler = McpJsonRpcHandler.createDefault();

        JsonNode guide = call(handler, request(1, "get_structurized_tool_guide", "{\"topic\":\"decomposition_rules\"}"));
        assertTrue(guide.at("/result/structuredContent/markdown").asText().contains("atom-map numbers"));
        assertTrue(guide.at("/result/structuredContent/markdown").asText().contains("evaluate_decomposition"));

        JsonNode validation = call(handler, request(2, "validate_decomposition_config", """
                {
                  "config": {
                    "version":"series-decomposition-v1",
                    "rules":[
                      {
                        "id":"wrong_amide",
                        "labelToSplit":null,
                        "smarts":"[C:1](=O)[NX3:2]",
                        "atomLabels":{"1":"acyl","2":"amine"}
                      }
                    ]
                  }
                }
                """));
        assertFalse(validation.at("/result/structuredContent/valid").asBoolean());
        assertEquals("schema_and_query_graph", validation.at("/result/structuredContent/validationScope").asText());
        assertTrue(validation.at("/result/structuredContent/problems/0").asText().contains("multiple label types"));
        assertTrue(validation.at("/result/structuredContent/warnings/0").asText().contains("evaluate_decomposition"));

        JsonNode corrected = call(handler, request(3, "validate_decomposition_config", """
                {
                  "config": {
                    "version":"series-decomposition-v1",
                    "rules":[
                      {
                        "id":"amide",
                        "labelToSplit":null,
                        "smarts":"[C:1](=O)[NX3:2]",
                        "atomLabels":{"0":"acyl","2":"amine"}
                      }
                    ]
                  }
                }
                """));
        assertTrue(corrected.at("/result/structuredContent/valid").asBoolean());
        assertEquals(3, corrected.at("/result/structuredContent/ruleDiagnostics/0/queryAtomCount").asInt());
    }

    @Test
    void canCreateEvaluateAndInspectDecompositionThroughToolCalls() throws Exception {
        McpJsonRpcHandler handler = McpJsonRpcHandler.createDefault();
        call(handler, request(1, "register_structure", """
                {"smiles":"CCCO","structure_id":"butanol","label":"Butanol fragment"}
                """));
        call(handler, request(2, "register_structure", """
                {"smiles":"C","structure_id":"methane","label":"Methane"}
                """));

        String configArgs = """
                {
                  "config_id":"demo_split",
                  "label":"Demo split",
                  "config":{
                    "version":"series-decomposition-v1",
                    "rules":[
                      {
                        "id":"split_root",
                        "labelToSplit":null,
                        "smarts":"CCO",
                        "atomLabels":{"0":"alkyl","1":"linker","2":"head"}
                      }
                    ]
                  }
                }
                """;
        JsonNode created = call(handler, request(3, "create_decomposition_config", configArgs));
        assertEquals("demo_split", created.at("/result/structuredContent/configId").asText());
        assertEquals(1, created.at("/result/structuredContent/ruleCount").asInt());

        JsonNode evaluated = call(handler, request(4, "evaluate_decomposition", """
                {"evaluation_id":"eval1","config_id":"demo_split","repository_id":"session"}
                """));
        assertEquals("eval1", evaluated.at("/result/structuredContent/evaluationId").asText());
        assertEquals(2, evaluated.at("/result/structuredContent/moleculeCount").asInt());
        assertEquals(1, evaluated.at("/result/structuredContent/successfulCount").asInt());
        assertEquals(1, evaluated.at("/result/structuredContent/rootNoMatchCount").asInt());

        JsonNode summary = call(handler, request(5, "get_decomposition_evaluation", """
                {"evaluation_id":"eval1","include_results":true}
                """));
        assertEquals("SUCCESS", summary.at("/result/structuredContent/results/0/status").asText());
        assertTrue(summary.at("/result/structuredContent/results/0/terminalPaths").toString().contains("root.alkyl"));

        JsonNode result = call(handler, request(6, "get_decomposition_result", """
                {"evaluation_id":"eval1","structure_id":"butanol"}
                """));
        assertEquals("butanol", result.at("/result/structuredContent/structureId").asText());
        assertEquals("SUCCESS", result.at("/result/structuredContent/status").asText());
        assertEquals("a1", result.at("/result/structuredContent/root/children/0/atomIds/0").asText());
        assertTrue(result.at("/result/structuredContent/root/cutBonds").size() >= 1);

        JsonNode failures = call(handler, request(7, "get_decomposition_failures", """
                {"evaluation_id":"eval1"}
                """));
        assertEquals("methane", failures.at("/result/structuredContent/groups/NO_MATCH/0/structureId").asText());

        JsonNode fragments = call(handler, request(8, "get_decomposition_fragment_summary", """
                {"evaluation_id":"eval1"}
                """));
        assertTrue(fragments.at("/result/structuredContent/rows").size() >= 3);
        assertTrue(fragments.toString().contains("root.head"));

        JsonNode fragmentFile = call(handler, request(9, "get_decomposition_fragment_summary", """
                {"evaluation_id":"eval1","include_details":true,"output_target":"file","output_name":"decomp/fragments.json"}
                """));
        Path fragmentArtifact = Path.of(fragmentFile.at("/result/structuredContent/artifact/path").asText());
        assertTrue(Files.exists(fragmentArtifact));
        assertTrue(fragmentFile.at("/result/structuredContent/rows").isMissingNode());
        assertTrue(mapper.readTree(fragmentArtifact.toFile()).at("/rows/0/examples/0/atomIds").isArray());

        JsonNode searchSelection = call(handler, request(10, "search_substructure", """
                {"query":"CCO","query_type":"smiles","repository_ids":["session"],"output_mode":"ids","create_selection":true,"selection_id":"alcohols"}
                """));
        assertEquals("alcohols", searchSelection.at("/result/structuredContent/selection/selectionId").asText());
        call(handler, request(11, "search_substructure", """
                {"query":"C","query_type":"smiles","repository_ids":["session"],"output_mode":"ids","create_selection":true,"selection_id":"carbon_hits"}
                """));
        JsonNode combined = call(handler, request(12, "combine_selections", """
                {"operation":"intersect","selection_ids":["alcohols","carbon_hits"],"selection_id":"alcohol_carbon_hits"}
                """));
        assertEquals("alcohol_carbon_hits", combined.at("/result/structuredContent/summary/selectionId").asText());
        assertEquals(1, combined.at("/result/structuredContent/summary/memberCount").asInt());
        JsonNode subtract = call(handler, request(13, "combine_selections", """
                {"operation":"subtract","selection_ids":["carbon_hits","alcohols"],"selection_id":"carbon_without_alcohols"}
                """));
        assertEquals(1, subtract.at("/result/structuredContent/summary/memberCount").asInt());
        assertEquals("methane", subtract.at("/result/structuredContent/examples/0/structureId").asText());

        JsonNode selectedEvaluation = call(handler, request(14, "evaluate_decomposition", """
                {"evaluation_id":"eval_selection","config_id":"demo_split","selection_id":"alcohol_carbon_hits"}
                """));
        assertEquals(1, selectedEvaluation.at("/result/structuredContent/moleculeCount").asInt());
        assertEquals(1, selectedEvaluation.at("/result/structuredContent/successfulCount").asInt());
        assertEquals("session", selectedEvaluation.at("/result/structuredContent/repositoryId").asText());

        JsonNode selectedEvaluationWithRepository = call(handler, request(15, "evaluate_decomposition", """
                {"evaluation_id":"eval_selection_with_repo","config_id":"demo_split","repository_id":"session","selection_id":"alcohols"}
                """));
        assertEquals(1, selectedEvaluationWithRepository.at("/result/structuredContent/moleculeCount").asInt());

        JsonNode mixedScope = call(handler, request(16, "evaluate_decomposition", """
                {"evaluation_id":"eval_bad_scope","config_id":"demo_split","selection_id":"alcohols","structure_ids":["butanol"]}
                """));
        assertTrue(mixedScope.at("/result/isError").asBoolean());
        assertEquals("invalid_decomposition_scope", mixedScope.at("/result/structuredContent/code").asText());

        JsonNode mismatchedRepository = call(handler, request(17, "evaluate_decomposition", """
                {"evaluation_id":"eval_bad_repo","config_id":"demo_split","repository_id":"other","selection_id":"alcohols"}
                """));
        assertTrue(mismatchedRepository.at("/result/isError").asBoolean());
        assertEquals("selection_repository_mismatch", mismatchedRepository.at("/result/structuredContent/code").asText());
    }

    @Test
    void fragmentHistogramSupportsEndpointStatsPagingAndFileOutput() throws Exception {
        McpJsonRpcHandler handler = McpJsonRpcHandler.createDefault();
        Path dataset = prismDataset();
        String path = dataset.toString().replace("\\", "\\\\");
        call(handler, request(1, "open_prism_dataset", "{\"path\":\"" + path + "\",\"dataset_id\":\"demo\"}"));
        call(handler, request(2, "register_structure", """
                {"smiles":"CCCO","structure_id":"butanol_a","fields":{"prism.subject_id":"CMP-001"}}
                """));
        call(handler, request(3, "register_structure", """
                {"smiles":"CCCO","structure_id":"butanol_b","fields":{"prism.subject_id":"CMP-002"}}
                """));
        call(handler, request(4, "register_structure", """
                {"smiles":"NCCO","structure_id":"aminoethanol","fields":{"prism.subject_id":"CMP-002"}}
                """));
        call(handler, request(5, "create_decomposition_config", """
                {
                  "config_id":"hist_split",
                  "config":{
                    "version":"series-decomposition-v1",
                    "rules":[{"id":"split_root","labelToSplit":null,"smarts":"CCO","atomLabels":{"0":"alkyl","1":"linker","2":"head"}}]
                  }
                }
                """));
        call(handler, request(6, "evaluate_decomposition", """
                {"evaluation_id":"hist_eval","config_id":"hist_split","repository_id":"session"}
                """));

        JsonNode histogram = call(handler, request(7, "get_decomposition_fragment_histogram", """
                {"evaluation_id":"hist_eval","label":"alkyl","dataset_id":"demo","endpoint_id":"pIC50","threshold":7.0,"limit":1,"example_limit":2}
                """));
        assertEquals("root.alkyl", histogram.at("/result/structuredContent/path").asText());
        assertEquals(2, histogram.at("/result/structuredContent/totalFragments").asInt());
        assertEquals(1, histogram.at("/result/structuredContent/returnedFragments").asInt());
        assertEquals(2, histogram.at("/result/structuredContent/rows/0/support").asInt());
        assertEquals(2, histogram.at("/result/structuredContent/rows/0/exampleStructureIds").size());
        assertTrue(histogram.at("/result/structuredContent/rows/0/structureIds").isMissingNode());
        assertEquals(2, histogram.at("/result/structuredContent/rows/0/endpoint/measuredCount").asInt());
        assertEquals(6.65, histogram.at("/result/structuredContent/rows/0/endpoint/median").asDouble(), 0.0001);
        assertEquals(1, histogram.at("/result/structuredContent/rows/0/endpoint/thresholdHitCount").asInt());

        JsonNode fileHistogram = call(handler, request(8, "get_decomposition_fragment_histogram", """
                {"evaluation_id":"hist_eval","path":"root.alkyl","dataset_id":"demo","endpoint_id":"pIC50","limit":1,"output_target":"file","output_name":"decomp/alkyl-histogram.json"}
                """));
        assertEquals(2, fileHistogram.at("/result/structuredContent/summary/totalFragments").asInt());
        assertEquals(1, fileHistogram.at("/result/structuredContent/summary/returnedFragments").asInt());
        assertTrue(fileHistogram.at("/result/structuredContent/rows").isMissingNode());
        Path artifact = Path.of(fileHistogram.at("/result/structuredContent/artifact/path").asText());
        assertEquals(2, mapper.readTree(artifact.toFile()).at("/rows").size());
    }

    @Test
    void canRegisterInspectAndSearchThroughToolCalls() throws Exception {
        McpJsonRpcHandler handler = McpJsonRpcHandler.createDefault();

        JsonNode register = call(handler, request(1, "register_structure", "{\"smiles\":\"c1ccncc1\",\"structure_id\":\"pyridine\",\"label\":\"Pyridine\"}"));
        assertEquals("pyridine", register.at("/result/structuredContent/structureId").asText());

        JsonNode inspect = call(handler, request(2, "inspect_structure", "{\"repository_id\":\"session\",\"structure_id\":\"pyridine\"}"));
        assertTrue(inspect.at("/result/content/0/text").asText().contains("STRUCTURE session:pyridine"));
        assertEquals(6, inspect.at("/result/structuredContent/atoms").size());

        JsonNode search = call(handler, request(3, "search_substructure", "{\"query\":\"c1ccncc1\",\"query_type\":\"smiles\"}"));
        assertEquals("count", search.at("/result/structuredContent/outputMode").asText());
        assertEquals(1, search.at("/result/structuredContent/summary/matchingStructures").asInt());
        assertEquals(0, search.at("/result/structuredContent/matches").size());

        JsonNode fullSearch = call(handler, request(4, "search_substructure", "{\"query\":\"c1ccncc1\",\"query_type\":\"smiles\",\"output_mode\":\"full\",\"limit\":5}"));
        assertEquals("pyridine", fullSearch.at("/result/structuredContent/matches/0/structureId").asText());

        JsonNode fileSearch = call(handler, request(5, "search_substructure", "{\"query\":\"c1ccncc1\",\"query_type\":\"smiles\",\"output_mode\":\"full\",\"limit\":5,\"output_target\":\"file\",\"output_name\":\"search/pyridine.json\"}"));
        Path searchArtifact = Path.of(fileSearch.at("/result/structuredContent/artifact/path").asText());
        assertTrue(Files.exists(searchArtifact));
        assertTrue(fileSearch.at("/result/structuredContent/matches").isMissingNode());
        assertEquals("pyridine", mapper.readTree(searchArtifact.toFile()).at("/matches/0/structureId").asText());

        JsonNode artifactInfo = call(handler, request(6, "get_artifact_info", "{\"artifact_id\":\"" + fileSearch.at("/result/structuredContent/artifact/artifactId").asText() + "\"}"));
        assertEquals("search/pyridine.json", artifactInfo.at("/result/structuredContent/relativePath").asText());

        JsonNode artifacts = call(handler, request(7, "list_artifacts", "{}"));
        assertTrue(artifacts.at("/result/structuredContent").size() >= 1);
    }


    @Test
    void canOpenPrismMaterializeSearchAndFetchEndpointValues() throws Exception {
        McpJsonRpcHandler handler = McpJsonRpcHandler.createDefault();
        Path dataset = prismDataset();
        String path = dataset.toString().replace("\\", "\\\\");

        JsonNode open = call(handler, request(10, "open_prism_dataset", "{\"path\":\"" + path + "\",\"dataset_id\":\"demo\",\"label\":\"Demo\"}"));
        assertEquals("demo", open.at("/result/structuredContent/datasetId").asText());
        assertEquals(2, open.at("/result/structuredContent/structureSubjectCount").asInt());

        JsonNode sets = call(handler, request(11, "list_prism_subject_sets", "{\"dataset_id\":\"demo\"}"));
        assertTrue(sets.at("/result/structuredContent").toString().contains("series:Kinase:A"));

        JsonNode materialized = call(handler, request(12, "materialize_prism_subject_set", "{\"dataset_id\":\"demo\",\"subject_set_id\":\"series:Kinase:A\"}"));
        String repositoryId = materialized.at("/result/structuredContent/repositoryId").asText();
        assertEquals("prism:demo:series:Kinase:A", repositoryId);
        assertEquals(2, materialized.at("/result/structuredContent/structuresImported").asInt());

        JsonNode search = call(handler, request(13, "search_substructure", "{\"query\":\"c1ccncc1\",\"repository_ids\":[\"" + repositoryId + "\"],\"output_mode\":\"ids\",\"create_selection\":true,\"selection_id\":\"pyridines\"}"));
        assertEquals("CMP-001", search.at("/result/structuredContent/matches/0/structureId").asText());
        assertEquals("pyridines", search.at("/result/structuredContent/selection/selectionId").asText());

        JsonNode values = call(handler, request(14, "get_prism_endpoint_values", "{\"dataset_id\":\"demo\",\"subject_ids\":[\"CMP-001\"],\"endpoint_ids\":[\"pIC50\"]}"));
        assertEquals("pIC50", values.at("/result/structuredContent/0/endpointId").asText());
        assertEquals(7.2, values.at("/result/structuredContent/0/result/mean").asDouble());

        JsonNode selectionStats = call(handler, request(15, "summarize_selection_by_endpoint", "{\"selection_id\":\"pyridines\",\"dataset_id\":\"demo\",\"endpoint_ids\":[\"pIC50\"],\"threshold\":7.0}"));
        assertEquals(1, selectionStats.at("/result/structuredContent/endpoints/0/measuredCount").asInt());
        assertEquals(7.2, selectionStats.at("/result/structuredContent/endpoints/0/median").asDouble());
        assertEquals(1, selectionStats.at("/result/structuredContent/endpoints/0/thresholdHitCount").asInt());

        JsonNode selectionMembers = call(handler, request(16, "get_selection_members", "{\"selection_id\":\"pyridines\",\"output_target\":\"file\",\"output_name\":\"selections/pyridines.json\"}"));
        Path selectionArtifact = Path.of(selectionMembers.at("/result/structuredContent/artifact/path").asText());
        assertTrue(Files.exists(selectionArtifact));
        assertEquals("CMP-001", mapper.readTree(selectionArtifact.toFile()).at("/members/0/structureId").asText());

        JsonNode potent = call(handler, request(17, "create_endpoint_selection", "{\"dataset_id\":\"demo\",\"repository_id\":\"" + repositoryId + "\",\"endpoint_id\":\"pIC50\",\"operator\":\"gte\",\"value\":7.0,\"selection_id\":\"potent\"}"));
        assertEquals("potent", potent.at("/result/structuredContent/summary/selectionId").asText());
        assertEquals(1, potent.at("/result/structuredContent/summary/memberCount").asInt());
        assertEquals("CMP-001", potent.at("/result/structuredContent/examples/0/structureId").asText());

        JsonNode potentPyridines = call(handler, request(18, "create_endpoint_selection", "{\"dataset_id\":\"demo\",\"base_selection_id\":\"pyridines\",\"endpoint_id\":\"pIC50\",\"operator\":\"gt\",\"value\":7.0,\"selection_id\":\"potent_pyridines\"}"));
        assertEquals("potent_pyridines", potentPyridines.at("/result/structuredContent/summary/selectionId").asText());
        assertEquals(1, potentPyridines.at("/result/structuredContent/summary/memberCount").asInt());

        JsonNode intersection = call(handler, request(19, "combine_selections", "{\"operation\":\"intersect\",\"selection_ids\":[\"potent\",\"pyridines\"],\"selection_id\":\"potent_pyridine_intersection\"}"));
        assertEquals("potent_pyridine_intersection", intersection.at("/result/structuredContent/summary/selectionId").asText());
        assertEquals(1, intersection.at("/result/structuredContent/summary/memberCount").asInt());

        JsonNode missingScope = call(handler, request(20, "create_endpoint_selection", "{\"dataset_id\":\"demo\",\"endpoint_id\":\"pIC50\",\"operator\":\"gte\",\"value\":7.0}"));
        assertTrue(missingScope.at("/result/isError").asBoolean());
        assertEquals("invalid_endpoint_selection_scope", missingScope.at("/result/structuredContent/code").asText());

        JsonNode repositoryMismatch = call(handler, request(21, "create_endpoint_selection", "{\"dataset_id\":\"demo\",\"repository_id\":\"other\",\"base_selection_id\":\"pyridines\",\"endpoint_id\":\"pIC50\",\"operator\":\"gte\",\"value\":7.0}"));
        assertTrue(repositoryMismatch.at("/result/isError").asBoolean());
        assertEquals("selection_repository_mismatch", repositoryMismatch.at("/result/structuredContent/code").asText());

        JsonNode badOperator = call(handler, request(22, "create_endpoint_selection", "{\"dataset_id\":\"demo\",\"repository_id\":\"" + repositoryId + "\",\"endpoint_id\":\"pIC50\",\"operator\":\"approximately\",\"value\":7.0}"));
        assertTrue(badOperator.at("/result/isError").asBoolean());
        assertEquals("invalid_endpoint_filter_operator", badOperator.at("/result/structuredContent/code").asText());

        call(handler, request(23, "cluster_structures", "{\"clustering_id\":\"prism_clusters\",\"repository_id\":\"" + repositoryId + "\",\"threshold\":0.0}"));
        JsonNode clusterStats = call(handler, request(24, "summarize_clusters_by_endpoint", "{\"clustering_id\":\"prism_clusters\",\"dataset_id\":\"demo\",\"endpoint_id\":\"pIC50\",\"output_target\":\"file\",\"output_name\":\"summaries/clusters.json\"}"));
        Path clusterStatsArtifact = Path.of(clusterStats.at("/result/structuredContent/artifact/path").asText());
        assertTrue(Files.exists(clusterStatsArtifact));
        assertEquals("pIC50", mapper.readTree(clusterStatsArtifact.toFile()).at("/clusters/0/endpoint/endpointId").asText());
    }


    @Test
    void unsafeArtifactOutputNameReturnsToolError() throws Exception {
        McpJsonRpcHandler handler = McpJsonRpcHandler.createDefault();
        call(handler, request(1, "register_structure", "{\"smiles\":\"CCO\",\"structure_id\":\"ethanol\"}"));

        JsonNode response = call(handler, request(2, "search_substructure", "{\"query\":\"CC\",\"output_mode\":\"ids\",\"output_target\":\"file\",\"output_name\":\"../bad.json\"}"));

        assertTrue(response.at("/result/isError").asBoolean());
        assertEquals("invalid_artifact_path", response.at("/result/structuredContent/code").asText());
    }

    @Test
    void toolChemistryFailuresReturnMcpToolErrorResult() throws Exception {
        McpJsonRpcHandler handler = McpJsonRpcHandler.createDefault();

        JsonNode response = call(handler, request(1, "inspect_atom", "{\"repository_id\":\"session\",\"structure_id\":\"missing\",\"atom_id\":\"a1\"}"));

        assertFalse(response.has("error"));
        assertTrue(response.at("/result/isError").asBoolean());
        assertEquals("structure_not_found", response.at("/result/structuredContent/code").asText());
    }

    @Test
    void unknownJsonRpcMethodReturnsProtocolError() throws Exception {
        McpJsonRpcHandler handler = McpJsonRpcHandler.createDefault();

        JsonNode response = call(handler, "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"unknown\",\"params\":{}}");

        assertEquals(-32601, response.at("/error/code").asInt());
        assertEquals("method_not_found", response.at("/error/data/code").asText());
    }


    private Path prismDataset() throws Exception {
        Path dir = tempDir.resolve("prism-tsv");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("endpoints.prism.tsv"), String.join("\n",
                "endpoint_id\tname\tpath\tdatatype\tendpoint_type\tevaluation_mode\tunit\tscale",
                "pIC50\tpIC50\tassay/pIC50\tNUMERIC\tMEASURED\tIMMEDIATE\tpIC50\tLOG",
                ""
        ));
        Files.writeString(dir.resolve("subjects.prism.tsv"), String.join("\n",
                "subject_id\tstructure_id\tbatch_id\tproject\tseries\tsmiles",
                "CMP-001\tS-001\tB-001\tKinase\tA\tc1ccncc1",
                "CMP-002\tS-002\tB-002\tKinase\tA\tCCN",
                ""
        ));
        Files.writeString(dir.resolve("values.prism.tsv"), String.join("\n",
                "subject_id\tendpoint_id\tstate\tmean\tn",
                "CMP-001\tpIC50\tVALUE\t7.2\t3",
                "CMP-002\tpIC50\tVALUE\t6.1\t1",
                ""
        ));
        return dir;
    }

    private JsonNode call(McpJsonRpcHandler handler, String request) throws Exception {
        String response = handler.handleJson(request);
        assertNotNull(response);
        return mapper.readTree(response);
    }

    private static String request(int id, String toolName, String argsJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\"" + toolName + "\",\"arguments\":" + argsJson + "}}";
    }

    private static String toolDescription(JsonNode tools, String name) {
        for (JsonNode tool : tools) {
            if (name.equals(tool.get("name").asText())) {
                return tool.get("description").asText();
            }
        }
        return "";
    }

    private static boolean hasTool(JsonNode tools, String name) {
        for (JsonNode tool : tools) {
            if (name.equals(tool.get("name").asText())) {
                return true;
            }
        }
        return false;
    }
}
