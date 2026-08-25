package tech.molecules.structurized.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
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
        assertEquals("0.3.8", response.at("/result/serverInfo/version").asText());
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

        assertEquals(103, tools.size());
        assertTrue(hasTool(tools, "register_structure"));
        assertTrue(hasTool(tools, "inspect_structure"));
        assertTrue(hasTool(tools, "list_artifacts"));
        assertTrue(hasTool(tools, "get_artifact_info"));
        assertTrue(hasTool(tools, "open_mmp_artifact"));
        assertTrue(hasTool(tools, "list_mmp_artifacts"));
        assertTrue(hasTool(tools, "describe_mmp_artifact"));
        assertTrue(hasTool(tools, "recommend_mmp_transformations"));
        assertTrue(hasTool(tools, "get_structurized_tool_guide"));
        assertTrue(hasTool(tools, "search_substructure"));
        assertTrue(hasTool(tools, "compare_structures"));
        assertTrue(hasTool(tools, "cut_bonds"));
        assertTrue(hasTool(tools, "open_prism_snapshot"));
        assertTrue(hasTool(tools, "reload_prism_snapshot"));
        assertTrue(hasTool(tools, "describe_prism_snapshot"));
        assertTrue(hasTool(tools, "list_prism_sessions"));
        assertFalse(hasTool(tools, "get_prism_session_info"));
        assertTrue(hasTool(tools, "list_prism_columns"));
        assertTrue(hasTool(tools, "describe_prism_session_for_agent"));
        assertTrue(hasTool(tools, "define_prism_endpoint_score"));
        assertTrue(hasTool(tools, "list_prism_endpoint_scores"));
        assertTrue(hasTool(tools, "export_prism_snapshot"));
        assertTrue(hasTool(tools, "get_prism_report_schema"));
        assertTrue(hasTool(tools, "validate_prism_report"));
        assertTrue(hasTool(tools, "publish_prism_report"));
        assertTrue(hasTool(tools, "save_prism_report"));
        assertTrue(hasTool(tools, "list_prediction_capabilities"));
        assertTrue(hasTool(tools, "describe_prediction_capability"));
        assertTrue(hasTool(tools, "evaluate_prism_prediction"));
        assertTrue(hasTool(tools, "get_prediction_run"));
        assertTrue(hasTool(tools, "list_prism_molecule_lists"));
        assertTrue(hasTool(tools, "get_prism_molecule_list"));
        assertTrue(hasTool(tools, "create_prism_molecule_list"));
        assertTrue(hasTool(tools, "add_prism_molecules"));
        assertTrue(hasTool(tools, "list_prism_live_evaluators"));
        assertTrue(hasTool(tools, "configure_prism_live_evaluator"));
        assertTrue(hasTool(tools, "list_prism_live_evaluations"));
        assertTrue(hasTool(tools, "run_prism_live_evaluator"));
        assertTrue(hasTool(tools, "list_prism_row_sets"));
        assertTrue(hasTool(tools, "get_prism_row_set_members"));
        assertTrue(hasTool(tools, "create_prism_endpoint_row_set"));
        assertTrue(hasTool(tools, "create_prism_column_row_set"));
        assertTrue(hasTool(tools, "combine_prism_row_sets"));
        assertTrue(hasTool(tools, "get_prism_row_set_structures"));
        assertTrue(hasTool(tools, "cluster_prism_row_set"));
        assertTrue(hasTool(tools, "list_prism_analyses"));
        assertTrue(hasTool(tools, "get_prism_clustering"));
        assertTrue(hasTool(tools, "get_prism_cluster_members"));
        assertTrue(hasTool(tools, "create_prism_cluster_row_set"));
        assertTrue(hasTool(tools, "list_prism_groupings"));
        assertTrue(hasTool(tools, "get_prism_grouping"));
        assertTrue(hasTool(tools, "create_prism_group_row_set"));
        assertTrue(hasTool(tools, "list_prism_graphs"));
        assertTrue(hasTool(tools, "summarize_prism_graph"));
        assertTrue(hasTool(tools, "analyze_prism_graph"));
        assertTrue(hasTool(tools, "export_prism_graph"));
        assertTrue(hasTool(tools, "inspect_prism_graph_neighborhood"));
        assertTrue(hasTool(tools, "find_prism_graph_shortest_path"));
        assertTrue(hasTool(tools, "summarize_prism_mmp_transforms"));
        assertTrue(hasTool(tools, "create_prism_graph_neighborhood_row_set"));
        assertTrue(hasTool(tools, "mine_prism_mmp_graph"));
        assertTrue(hasTool(tools, "mine_prism_similarity_graph"));
        assertTrue(hasTool(tools, "discover_prism_scaffolds"));
        assertTrue(hasTool(tools, "analyze_prism_scaffold"));
        assertTrue(hasTool(tools, "materialize_prism_scaffold_analysis"));
        assertTrue(hasTool(tools, "get_prism_scaffold_projection"));
        assertTrue(hasTool(tools, "create_prism_scaffold_bucket_row_set"));
        assertTrue(hasTool(tools, "export_prism_scaffold_projection"));
        assertTrue(hasTool(tools, "summarize_prism_row_set_by_columns"));
        assertTrue(hasTool(tools, "summarize_prism_grouping_by_columns"));
        assertTrue(hasTool(tools, "materialize_prism_row_set"));
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
        assertTrue(hasTool(tools, "get_prism_endpoint_results"));
        assertTrue(hasTool(tools, "combine_selections"));
        assertTrue(hasTool(tools, "get_selection_members"));
        assertTrue(hasTool(tools, "summarize_selection_by_endpoint"));
        assertTrue(hasTool(tools, "export_selection_table"));
        assertTrue(hasTool(tools, "summarize_clusters_by_endpoint"));
        assertTrue(toolDescription(tools, "validate_decomposition_config").contains("SMARTS compilation"));
        assertTrue(toolDescription(tools, "create_decomposition_config").contains("zero-based SMARTS query atom indices"));
        assertTrue(toolDescription(tools, "analyze_prism_scaffold").contains("[cH:1]"));
        assertEquals("object", tools.get(0).at("/inputSchema/type").asText());
    }

    @Test
    void canCompareStructuresThroughToolCalls() throws Exception {
        McpJsonRpcHandler handler = McpJsonRpcHandler.createDefault();

        JsonNode summary = call(handler, request(1, "compare_structures", """
                {"left_smiles":"Cc1ccccc1","right_smiles":"Fc1ccccc1"}
                """));
        assertEquals("SUCCESS", summary.at("/result/structuredContent/status").asText(), summary.toPrettyString());
        assertEquals(6, summary.at("/result/structuredContent/sharedCoreAtomCount").asInt());
        assertEquals(1, summary.at("/result/structuredContent/changeGroupCount").asInt());
        assertEquals("REPLACEMENT", summary.at("/result/structuredContent/changeTypes/0").asText());
        assertTrue(summary.at("/result/structuredContent/summaryText").asText().contains("6-atom core"));
        assertTrue(summary.at("/result/structuredContent/sharedCoreSmiles").isMissingNode());

        JsonNode identical = call(handler, request(2, "compare_structures", """
                {"left_smiles":"c1ccccc1","right_smiles":"c1ccccc1"}
                """));
        assertEquals("NO_CHANGE", identical.at("/result/structuredContent/status").asText());

        call(handler, request(3, "register_structure", """
                {"smiles":"Cc1ccccc1","structure_id":"toluene"}
                """));
        call(handler, request(4, "register_structure", """
                {"smiles":"Fc1ccccc1","structure_id":"fluorobenzene"}
                """));
        JsonNode compact = call(handler, request(5, "compare_structures", """
                {
                  "left_repository_id":"session",
                  "left_structure_id":"toluene",
                  "right_repository_id":"session",
                  "right_structure_id":"fluorobenzene",
                  "output_mode":"compact"
                }
                """));
        assertEquals("SUCCESS", compact.at("/result/structuredContent/summary/status").asText(), compact.toPrettyString());
        assertTrue(compact.at("/result/structuredContent/sharedCoreSmiles").isTextual());
        assertEquals("REPLACEMENT", compact.at("/result/structuredContent/changeGroups/0/type").asText());
        assertTrue(compact.at("/result/structuredContent/changeGroups/0/transformText").asText().contains("->"));
        assertTrue(compact.at("/result/structuredContent/changeGroups/0/extensionPoints/0/dummyLabel").asText().startsWith("*"));
        assertTrue(compact.at("/result/structuredContent/changeGroups/0/removedIdcode").isMissingNode());

        String path = mmpDataset().toString().replace("\\", "\\\\");
        call(handler, request(6, "open_prism_snapshot", "{\"path\":\"" + path + "\",\"session_id\":\"compare_prism\"}"));
        JsonNode full = call(handler, request(7, "compare_structures", """
                {
                  "session_id":"compare_prism",
                  "left_row_id":"TOLUENE",
                  "right_row_id":"ETHYLBENZENE",
                  "output_mode":"full",
                  "include_atom_mappings":true
                }
                """));
        assertEquals("SUCCESS", full.at("/result/structuredContent/summary/status").asText(), full.toPrettyString());
        assertTrue(full.at("/result/structuredContent/sharedCoreIdcode").isTextual());
        assertTrue(full.at("/result/structuredContent/atomMappings").isArray());
        assertTrue(full.at("/result/structuredContent/changeGroups/0/fullSignatureId").isTextual());
    }

    @Test
    void agentCanCreateAndPopulatePrismMoleculeLists() throws Exception {
        McpJsonRpcHandler handler = McpJsonRpcHandler.createDefault();
        String path = prismDataset().toString().replace("\\", "\\\\");
        call(handler, request(1, "open_prism_snapshot", "{\"path\":\"" + path + "\",\"session_id\":\"molecules\"}"));

        JsonNode created = call(handler, request(2, "create_prism_molecule_list", """
                {"session_id":"molecules","list_id":"ideas","title":"Proposed analogues"}
                """));
        assertEquals("ideas", created.at("/result/structuredContent/listId").asText());

        JsonNode added = call(handler, request(3, "add_prism_molecules", """
                {
                  "session_id":"molecules",
                  "list_id":"ideas",
                  "molecules":[
                    {"title":"Candidate","structure":"CCN"},
                    {"title":"Query","mode":"fragment","structure":"[c,n]1ccccc1[*]"}
                  ]
                }
                """));
        assertEquals(2, added.at("/result/structuredContent/documents").size());
        assertEquals("molecule", added.at("/result/structuredContent/documents/0/mode").asText());
        assertEquals("fragment", added.at("/result/structuredContent/documents/1/mode").asText());

        JsonNode listed = call(handler, request(4, "list_prism_molecule_lists", """
                {"session_id":"molecules"}
                """));
        assertEquals(2, listed.at("/result/structuredContent").size());
        assertEquals("ideas", listed.at("/result/structuredContent/1/listId").asText());
    }

    @Test
    void agentCanConfigureAndRunPrismLiveEvaluators() throws Exception {
        McpJsonRpcHandler handler = McpJsonRpcHandler.createDefault();
        String path = prismDataset().toString().replace("\\", "\\\\");
        call(handler, request(1, "open_prism_snapshot", "{\"path\":\"" + path + "\",\"session_id\":\"live\"}"));
        JsonNode added = call(handler, request(2, "add_prism_molecules", """
                {
                  "session_id":"live",
                  "list_id":"scratchpad",
                  "molecules":[{"title":"Ethanol","structure":"CCO"}]
                }
                """));
        String documentId = added.at("/result/structuredContent/documents/0/documentId").asText();

        JsonNode evaluators = call(handler, request(3, "list_prism_live_evaluators", """
                {"session_id":"live"}
                """));
        assertEquals(2, evaluators.at("/result/structuredContent").size());

        JsonNode configured = call(handler, request(4, "configure_prism_live_evaluator", """
                {
                  "session_id":"live",
                  "binding_id":"ocl.basic_properties",
                  "mode":"manual",
                  "quiet_period_ms":0
                }
                """));
        assertEquals("manual", configured.at("/result/structuredContent/mode").asText());

        JsonNode queued = call(handler, request(5, "run_prism_live_evaluator", """
                {
                  "session_id":"live",
                  "binding_id":"ocl.basic_properties",
                  "document_id":"%s",
                  "expected_document_revision":1
                }
                """.formatted(documentId)));
        assertEquals(documentId, queued.at("/result/structuredContent/documentId").asText(), queued.toPrettyString());

        JsonNode evaluations = null;
        JsonNode basicProperties = null;
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(3).toNanos();
        do {
            evaluations = call(handler, request(6, "list_prism_live_evaluations",
                    "{\"session_id\":\"live\",\"document_id\":\"" + documentId + "\"}"));
            for (JsonNode evaluation : evaluations.at("/result/structuredContent")) {
                if ("ocl.basic_properties".equals(evaluation.path("bindingId").asText())) {
                    basicProperties = evaluation;
                    break;
                }
            }
            if (basicProperties != null && "succeeded".equals(basicProperties.path("status").asText())) break;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        assertEquals("chemistry.ocl.basic_properties.v1",
                basicProperties == null ? "" : basicProperties.path("schemaId").asText(),
                evaluations == null ? "no evaluation response" : evaluations.toPrettyString());
    }

    @Test
    void agentCanEvaluatePrismPredictions() throws Exception {
        McpJsonRpcHandler handler = McpJsonRpcHandler.createDefault();
        String path = prismDataset().toString().replace("\\", "\\\\");
        call(handler, request(1, "open_prism_snapshot", "{\"path\":\"" + path + "\",\"session_id\":\"predict\"}"));

        JsonNode capabilities = call(handler, request(2, "list_prediction_capabilities", "{\"session_id\":\"predict\",\"endpoint_id\":\"pIC50\"}"));
        assertEquals("reference/pic50", capabilities.at("/result/structuredContent/0/capabilityId").asText());

        JsonNode capability = call(handler, request(3, "describe_prediction_capability", "{\"session_id\":\"predict\",\"capability_id\":\"reference/pic50\"}"));
        assertEquals("pIC50.predicted", capability.at("/result/structuredContent/predictedEndpointId").asText());

        JsonNode evaluated = call(handler, request(4, "evaluate_prism_prediction", """
                {
                  "session_id":"predict",
                  "prediction_run_id":"pred_mcp",
                  "endpoint_id":"pIC50",
                  "mode":"ALL"
                }
                """));
        assertEquals("pred_mcp", evaluated.at("/result/structuredContent/analysis/analysisId").asText(), evaluated.toPrettyString());
        assertTrue(evaluated.at("/result/structuredContent/valueCount").asInt() > 0);
        assertTrue(evaluated.at("/result/structuredContent/publishedColumnIds").toString().contains("pred_mcp.pIC50_predicted.prediction"));

        JsonNode run = call(handler, request(5, "get_prediction_run", """
                {"session_id":"predict","prediction_run_id":"pred_mcp","limit":1}
                """));
        assertEquals("pred_mcp", run.at("/result/structuredContent/summary/analysis/analysisId").asText());
        assertEquals(1, run.at("/result/structuredContent/values").size());

        JsonNode columns = call(handler, request(6, "list_prism_columns", "{\"session_id\":\"predict\"}"));
        assertTrue(columns.at("/result/structuredContent").toString().contains("pred_mcp.pIC50_predicted.status"));
    }

    @Test
    void canDefineListAndExportReportReadyEndpointScores() throws Exception {
        McpJsonRpcHandler handler = McpJsonRpcHandler.createDefault();
        String source = examplePrismPack().toString().replace("\\", "\\\\");
        Path outputPath = tempDir.resolve("scored-analysis.prismpack");
        String exportedPath = outputPath.toString().replace("\\", "\\\\");
        call(handler, request(1, "open_prism_snapshot",
                "{\"path\":\"" + source + "\",\"session_id\":\"scored\"}"));

        JsonNode defined = call(handler, request(2, "define_prism_endpoint_score", """
                {
                  "session_id": "scored",
                  "score_id": "potency_desirability",
                  "endpoint_id": "pIC50",
                  "display_name": "Potency desirability",
                  "points": [
                    {"x": 5.0, "score": 0.0},
                    {"x": 8.0, "score": 1.0}
                  ]
                }
                """));
        assertFalse(defined.at("/result/structuredContent/reused").asBoolean());
        assertEquals("score__potency_desirability",
                defined.at("/result/structuredContent/score/outputColumnId").asText());

        JsonNode repeated = call(handler, request(3, "define_prism_endpoint_score", """
                {
                  "session_id": "scored",
                  "score_id": "potency_desirability",
                  "endpoint_id": "pIC50",
                  "points": [{"x": 5.0, "score": 0.0}, {"x": 8.0, "score": 1.0}]
                }
                """));
        assertTrue(repeated.at("/result/structuredContent/reused").asBoolean());

        JsonNode scores = call(handler, request(4, "list_prism_endpoint_scores", "{\"session_id\":\"scored\"}"));
        assertEquals(1, scores.at("/result/structuredContent").size());
        assertEquals("pIC50", scores.at("/result/structuredContent/0/sourceColumnId").asText());

        JsonNode exported = call(handler, request(5, "export_prism_snapshot",
                "{\"session_id\":\"scored\",\"output_path\":\"" + exportedPath + "\",\"title\":\"Scored analysis\"}"));
        assertEquals("score__potency_desirability",
                exported.at("/result/structuredContent/derivedColumnIds/0").asText());
        assertTrue(Files.isRegularFile(outputPath));

        call(handler, request(6, "open_prism_snapshot",
                "{\"path\":\"" + exportedPath + "\",\"session_id\":\"reopened_scored\"}"));
        JsonNode columns = call(handler, request(7, "list_prism_columns", "{\"session_id\":\"reopened_scored\"}"));
        assertTrue(columns.at("/result/structuredContent").toString().contains("score__potency_desirability"));
    }

    @Test
    void canUseSessionBackedPrismRowSetTools() throws Exception {
        McpJsonRpcHandler handler = McpJsonRpcHandler.createDefault();
        Path dataset = prismDataset();
        String path = dataset.toString().replace("\\", "\\\\");

        JsonNode opened = call(handler, request(1, "open_prism_snapshot", "{\"path\":\"" + path + "\",\"session_id\":\"demo\"}"));
        assertEquals("demo", opened.at("/result/structuredContent/sessionId").asText());
        assertEquals("demo", opened.at("/result/structuredContent/datasetId").asText());

        JsonNode sessions = call(handler, request(2, "list_prism_sessions", "{}"));
        assertEquals("demo", sessions.at("/result/structuredContent/0/sessionId").asText());

        JsonNode info = call(handler, request(3, "describe_prism_snapshot", "{\"session_id\":\"demo\"}"));
        assertEquals(2, info.at("/result/structuredContent/summary/totalRowCount").asInt());
        assertTrue(info.at("/result/structuredContent/rowSets").size() >= 1);
        assertTrue(info.at("/result/structuredContent/subjectSets").isMissingNode());

        JsonNode subjectSet = call(handler, request(4, "get_prism_row_set_members", """
                {"session_id":"demo","row_set_id":"series:A"}
                """));
        assertEquals("series:A", subjectSet.at("/result/structuredContent/summary/rowSetId").asText());
        assertEquals(2, subjectSet.at("/result/structuredContent/summary/rowCount").asInt());

        JsonNode potent = call(handler, request(5, "create_prism_endpoint_row_set", """
                {"session_id":"demo","endpoint_id":"pIC50","operator":"gte","value":7.0,"row_set_id":"potent"}
                """));
        assertEquals("potent", potent.at("/result/structuredContent/rowSetId").asText());
        assertEquals(1, potent.at("/result/structuredContent/rowCount").asInt());

        JsonNode recent = call(handler, request(6, "create_prism_endpoint_row_set", """
                {"session_id":"demo","endpoint_id":"pIC50","measured_after":"2026-06-01","row_set_id":"recent"}
                """));
        assertEquals(1, recent.at("/result/structuredContent/rowCount").asInt());

        JsonNode combined = call(handler, request(7, "combine_prism_row_sets", """
                {"session_id":"demo","operation":"union","row_set_ids":["potent","recent"],"row_set_id":"interesting"}
                """));
        assertEquals("interesting", combined.at("/result/structuredContent/rowSetId").asText());
        assertEquals(2, combined.at("/result/structuredContent/rowCount").asInt());

        JsonNode members = call(handler, request(8, "get_prism_row_set_members", """
                {"session_id":"demo","row_set_id":"interesting","offset":0,"limit":1}
                """));
        assertEquals(2, members.at("/result/structuredContent/summary/rowCount").asInt());
        assertEquals(1, members.at("/result/structuredContent/members").size());
        assertEquals("CMP-001", members.at("/result/structuredContent/members/0/rowId").asText());

        JsonNode structures = call(handler, request(9, "get_prism_row_set_structures", """
                {"session_id":"demo","row_set_id":"interesting"}
                """));
        assertEquals(2, structures.at("/result/structuredContent/structureCount").asInt());
        assertEquals("CMP-001", structures.at("/result/structuredContent/structures/0/rowId").asText());
        assertEquals("c1ccncc1", structures.at("/result/structuredContent/structures/0/smiles").asText());
    }

    @Test
    void canMineAnalyzeInspectAndExportPrismMmpGraphsThroughToolCalls() throws Exception {
        McpJsonRpcHandler handler = McpJsonRpcHandler.createDefault();
        String path = mmpDataset().toString().replace("\\", "\\\\");
        call(handler, request(1, "open_prism_snapshot", "{\"path\":\"" + path + "\",\"session_id\":\"mmp_demo\"}"));

        JsonNode mined = call(handler, request(2, "mine_prism_mmp_graph", """
                {"session_id":"mmp_demo","row_set_id":"all","structure_column_id":"smiles","value_column_id":"pIC50","graph_id":"mmp_network"}
                """));
        assertEquals("mmp_network", mined.at("/result/structuredContent/graph/graphId").asText(), mined.toPrettyString());
        assertEquals(1, mined.at("/result/structuredContent/configuration/maxCuts").asInt());
        assertEquals(1, mined.at("/result/structuredContent/configuration/minTransformSupport").asInt());
        assertEquals(16, mined.at("/result/structuredContent/configuration/maxVariableHeavyAtoms").asInt());
        assertEquals(0.3, mined.at("/result/structuredContent/configuration/maxVariableToMolHeavyAtomFraction").asDouble(), 0.0001);
        assertTrue(mined.at("/result/structuredContent/pairCount").asInt() > 0);

        JsonNode analysis = call(handler, request(3, "analyze_prism_graph", """
                {"session_id":"mmp_demo","graph_id":"mmp_network","limit":5}
                """));
        assertEquals(2, analysis.at("/result/structuredContent/sourceRowCount").asInt());
        assertEquals(2, analysis.at("/result/structuredContent/connectedRowCount").asInt());
        assertEquals(0, analysis.at("/result/structuredContent/isolatedSourceRowCount").asInt());
        assertTrue(analysis.at("/result/structuredContent/topDegreeRows/0/degree").asInt() > 0);

        JsonNode stats = call(handler, request(4, "inspect_prism_graph_neighborhood", """
                {"session_id":"mmp_demo","graph_id":"mmp_network","center_row_id":"TOLUENE"}
                """));
        assertEquals("stats", stats.at("/result/structuredContent/outputMode").asText());
        assertEquals("TOLUENE", stats.at("/result/structuredContent/center/rowId").asText());
        assertTrue(stats.at("/result/structuredContent/neighborCount").asInt() > 0);
        assertTrue(stats.at("/result/structuredContent/neighbors").isMissingNode());

        JsonNode compact = call(handler, request(5, "inspect_prism_graph_neighborhood", """
                {"session_id":"mmp_demo","graph_id":"mmp_network","center_row_id":"TOLUENE","output_mode":"compact","limit":1}
                """));
        assertEquals("compact", compact.at("/result/structuredContent/outputMode").asText());
        assertEquals(1, compact.at("/result/structuredContent/returnedNeighbors").asInt());
        assertEquals("ETHYLBENZENE", compact.at("/result/structuredContent/neighbors/0/rowId").asText());
        assertFalse(compact.at("/result/structuredContent/neighbors/0/edges/0/properties").isObject());
        assertTrue(compact.at("/result/structuredContent/neighbors/0/edges/0/transformId").isTextual());
        assertTrue(compact.at("/result/structuredContent/neighbors/0/edges/0/transformText").isTextual());
        assertFalse(compact.at("/result/structuredContent/neighbors/0/edges/0/fromFragment").asText().isBlank());

        JsonNode collapsed = call(handler, request(55, "inspect_prism_graph_neighborhood", """
                {"session_id":"mmp_demo","graph_id":"mmp_network","center_row_id":"TOLUENE","output_mode":"collapsed","limit":1}
                """));
        assertEquals("collapsed", collapsed.at("/result/structuredContent/outputMode").asText());
        assertEquals(1, collapsed.at("/result/structuredContent/returnedNeighbors").asInt());
        assertTrue(collapsed.at("/result/structuredContent/neighbors/0/rawEdgeCount").asInt() >= 1);
        assertTrue(collapsed.at("/result/structuredContent/neighbors/0/exampleTransforms/0/transformText").isTextual());

        JsonNode transforms = call(handler, request(56, "summarize_prism_mmp_transforms", """
                {"session_id":"mmp_demo","graph_id":"mmp_network","limit":5}
                """));
        assertTrue(transforms.at("/result/structuredContent/totalTransforms").asInt() > 0);
        assertTrue(transforms.at("/result/structuredContent/transforms/0/transformText").isTextual());
        assertTrue(transforms.at("/result/structuredContent/transforms/0/supportCount").asInt() > 0);
        assertTrue(transforms.at("/result/structuredContent/transforms/0/medianDelta").isNumber());

        JsonNode distanceOnly = call(handler, request(57, "find_prism_graph_shortest_path", """
                {"session_id":"mmp_demo","graph_id":"mmp_network","source_row_id":"TOLUENE","target_row_id":"ETHYLBENZENE","max_depth":1}
                """));
        assertTrue(distanceOnly.at("/result/structuredContent/connected").asBoolean());
        assertEquals(1, distanceOnly.at("/result/structuredContent/distance").asInt());
        assertEquals("connected", distanceOnly.at("/result/structuredContent/reason").asText());
        assertEquals("stats", distanceOnly.at("/result/structuredContent/outputMode").asText());
        assertTrue(distanceOnly.at("/result/structuredContent/graph/metadata").isMissingNode());
        assertTrue(distanceOnly.at("/result/structuredContent/source/fields").isMissingNode());
        assertEquals(0, distanceOnly.at("/result/structuredContent/pathRows").size());
        assertEquals(0, distanceOnly.at("/result/structuredContent/steps").size());

        JsonNode pathResult = call(handler, request(58, "find_prism_graph_shortest_path", """
                {"session_id":"mmp_demo","graph_id":"mmp_network","source_row_id":"TOLUENE","target_row_id":"ETHYLBENZENE","include_path":true,"output_mode":"compact"}
                """));
        assertTrue(pathResult.at("/result/structuredContent/connected").asBoolean());
        assertEquals("compact", pathResult.at("/result/structuredContent/outputMode").asText());
        assertEquals("TOLUENE", pathResult.at("/result/structuredContent/pathRows/0/rowId").asText());
        assertEquals("ETHYLBENZENE", pathResult.at("/result/structuredContent/pathRows/1/rowId").asText());
        assertTrue(pathResult.at("/result/structuredContent/pathRows/0/fields").isMissingNode());
        assertEquals(1, pathResult.at("/result/structuredContent/steps").size());
        assertTrue(pathResult.at("/result/structuredContent/steps/0/rawEdgeCount").asInt() > 0);
        assertTrue(pathResult.at("/result/structuredContent/steps/0/exampleTransforms/0/transformText").isTextual());
        assertTrue(pathResult.at("/result/structuredContent/steps/0/exampleTransforms/0/keyFragment").isTextual());

        JsonNode fullPath = call(handler, request(59, "find_prism_graph_shortest_path", """
                {"session_id":"mmp_demo","graph_id":"mmp_network","source_row_id":"TOLUENE","target_row_id":"ETHYLBENZENE","include_path":true,"output_mode":"full"}
                """));
        assertTrue(fullPath.at("/result/structuredContent/graph/metadata").isObject());
        assertTrue(fullPath.at("/result/structuredContent/pathRows/0/fields").isObject());

        JsonNode badPathMode = call(handler, request(60, "find_prism_graph_shortest_path", """
                {"session_id":"mmp_demo","graph_id":"mmp_network","source_row_id":"TOLUENE","target_row_id":"ETHYLBENZENE","output_mode":"verbose"}
                """));
        assertTrue(badPathMode.at("/result/isError").asBoolean());
        assertEquals("invalid_graph_shortest_path_output_mode", badPathMode.at("/result/structuredContent/code").asText());

        JsonNode full = call(handler, request(6, "inspect_prism_graph_neighborhood", """
                {"session_id":"mmp_demo","graph_id":"mmp_network","center_row_id":"TOLUENE","output_mode":"full","limit":1}
                """));
        assertTrue(full.at("/result/structuredContent/neighbors/0/edges/0/properties/transformId").isTextual());
        assertTrue(full.at("/result/structuredContent/neighbors/0/edges/0/properties/transformText").isTextual());

        JsonNode rowSet = call(handler, request(7, "create_prism_graph_neighborhood_row_set", """
                {"session_id":"mmp_demo","graph_id":"mmp_network","center_row_id":"TOLUENE","max_depth":1,"row_set_id":"toluene_neighbors","create_shell_grouping":true,"shell_grouping_id":"toluene_neighbor_shells"}
                """));
        assertEquals("toluene_neighbors", rowSet.at("/result/structuredContent/rowSetId").asText());
        assertEquals(2, rowSet.at("/result/structuredContent/rowCount").asInt());
        assertEquals(1, rowSet.at("/result/structuredContent/provenance/maxDepth").asInt());
        assertEquals("toluene_neighbor_shells", rowSet.at("/result/structuredContent/provenance/shellGroupingId").asText());

        JsonNode exported = call(handler, request(8, "export_prism_graph", """
                {"session_id":"mmp_demo","graph_id":"mmp_network","format":"edges_tsv","output_name":"graphs/mmp_edges.tsv"}
                """));
        assertEquals("edges_tsv", exported.at("/result/structuredContent/summary/format").asText());
        assertTrue(exported.at("/result/structuredContent/summary/rowCount").asInt() > 0);
        assertTrue(exported.at("/result/structuredContent/tsv").isMissingNode());
        Path artifact = Path.of(exported.at("/result/structuredContent/artifact/path").asText());
        String tsv = Files.readString(artifact);
        assertTrue(tsv.startsWith("edge_id	source_row_id	target_row_id"));
        assertTrue(tsv.contains("relation_type	similarity	edge_source	descriptor"));
        assertTrue(tsv.contains("transform_id"));
        assertTrue(tsv.contains("transform_text"));
        assertTrue(tsv.contains("key_fragment"));
        assertTrue(tsv.contains("from_fragment"));
        assertTrue(tsv.contains("to_fragment"));

        JsonNode similarity = call(handler, request(61, "mine_prism_similarity_graph", """
                {"session_id":"mmp_demo","row_set_id":"all","structure_column_id":"smiles","graph_id":"similarity_network","mode":"knn","neighbor_count":1}
                """));
        assertEquals("similarity_network", similarity.at("/result/structuredContent/graph/graphId").asText(), similarity.toPrettyString());
        assertEquals("chemistry.similarity", similarity.at("/result/structuredContent/graph/graphType").asText());
        assertEquals(1, similarity.at("/result/structuredContent/edgeCount").asInt());
        assertEquals(1, similarity.at("/result/structuredContent/similarity/edgeCount").asInt());
        assertEquals(1, similarity.at("/result/structuredContent/similarity/edgeSourceCounts/knn").asInt());

        JsonNode similarityAnalysis = call(handler, request(62, "analyze_prism_graph", """
                {"session_id":"mmp_demo","graph_id":"similarity_network","limit":5}
                """));
        assertEquals(1, similarityAnalysis.at("/result/structuredContent/similarity/edgeCount").asInt());
        assertTrue(similarityAnalysis.at("/result/structuredContent/similarity/median").isNumber());

        JsonNode similarityNeighborhood = call(handler, request(63, "inspect_prism_graph_neighborhood", """
                {"session_id":"mmp_demo","graph_id":"similarity_network","center_row_id":"TOLUENE","output_mode":"compact","limit":1}
                """));
        assertEquals("chemical_similarity", similarityNeighborhood.at("/result/structuredContent/neighbors/0/edges/0/relationType").asText());
        assertEquals("knn", similarityNeighborhood.at("/result/structuredContent/neighbors/0/edges/0/edgeSource").asText());
        assertTrue(similarityNeighborhood.at("/result/structuredContent/neighbors/0/edges/0/similarity").isNumber());
    }

    @Test
    void canFilterAndClusterManagedPrismRowsThroughToolCalls() throws Exception {
        McpJsonRpcHandler handler = McpJsonRpcHandler.createDefault();
        Path dataset = prismDataset();
        String path = dataset.toString().replace("\\", "\\\\");

        call(handler, request(20, "open_prism_snapshot",
                "{\"path\":\"" + path + "\",\"session_id\":\"analysis_demo\"}"));

        JsonNode scope = call(handler, request(21, "create_prism_column_row_set", """
                {
                  "session_id":"analysis_demo",
                  "column_id":"pIC50",
                  "filter_type":"numeric_range",
                  "minimum":6.0,
                  "row_set_id":"measured"
                }
                """));
        assertEquals(2, scope.at("/result/structuredContent/rowCount").asInt());

        JsonNode clustered = call(handler, request(22, "cluster_prism_row_set", """
                {
                  "session_id":"analysis_demo",
                  "row_set_id":"measured",
                  "analysis_id":"rough",
                  "threshold":1.0
                }
                """));
        assertEquals("rough", clustered.at("/result/structuredContent/analysis/analysisId").asText(), clustered.toPrettyString());
        assertEquals(2, clustered.at("/result/structuredContent/clusterCount").asInt());
        assertEquals(3, clustered.at("/result/structuredContent/analysis/resultRevision").asInt());

        JsonNode analyses = call(handler, request(23, "list_prism_analyses",
                "{\"session_id\":\"analysis_demo\"}"));
        assertEquals("rough", analyses.at("/result/structuredContent/0/analysisId").asText());

        JsonNode groupings = call(handler, request(29, "list_prism_groupings",
                "{\"session_id\":\"analysis_demo\"}"));
        assertEquals("rough", groupings.at("/result/structuredContent/0/groupingId").asText());
        assertEquals("rough.cluster_id", groupings.at("/result/structuredContent/0/facetColumnId").asText());

        JsonNode grouping = call(handler, request(30, "get_prism_grouping", """
                {"session_id":"analysis_demo","grouping_id":"rough"}
                """));
        assertEquals(2, grouping.at("/result/structuredContent/totalGroups").asInt());
        assertEquals("cluster_1", grouping.at("/result/structuredContent/groups/0/groupId").asText());

        JsonNode rowSetSummary = call(handler, request(32, "summarize_prism_row_set_by_columns", """
                {
                  "session_id":"analysis_demo",
                  "row_set_id":"measured",
                  "column_ids":["pIC50","series"],
                  "threshold":7.0,
                  "top_values_limit":3
                }
                """));
        assertEquals(2, rowSetSummary.at("/result/structuredContent/rowSet/rowCount").asInt());
        assertEquals("pIC50", rowSetSummary.at("/result/structuredContent/columns/0/columnId").asText());
        assertEquals(2, rowSetSummary.at("/result/structuredContent/columns/0/validCount").asInt());
        assertEquals(6.65, rowSetSummary.at("/result/structuredContent/columns/0/numeric/median").asDouble(), 0.0001);
        assertEquals(1, rowSetSummary.at("/result/structuredContent/columns/0/numeric/thresholdHitCount").asInt());
        assertEquals("A", rowSetSummary.at("/result/structuredContent/columns/1/categorical/topValues/0/value").asText());

        JsonNode groupSummary = call(handler, request(33, "summarize_prism_grouping_by_columns", """
                {
                  "session_id":"analysis_demo",
                  "grouping_id":"rough",
                  "column_ids":["pIC50"],
                  "include_singletons":true,
                  "threshold":7.0,
                  "limit":1
                }
                """));
        assertEquals(2, groupSummary.at("/result/structuredContent/totalGroups").asInt());
        assertEquals(1, groupSummary.at("/result/structuredContent/returnedGroups").asInt());
        assertEquals("cluster_1", groupSummary.at("/result/structuredContent/groups/0/groupId").asText());
        assertEquals(1, groupSummary.at("/result/structuredContent/groups/0/columns/0/validCount").asInt());
        assertEquals(1, groupSummary.at("/result/structuredContent/groups/0/columns/0/numeric/thresholdHitCount").asInt());

        JsonNode genericGroupSet = call(handler, request(31, "create_prism_group_row_set", """
                {
                  "session_id":"analysis_demo",
                  "grouping_id":"rough",
                  "group_id":"cluster_1",
                  "row_set_id":"generic_cluster_one"
                }
                """));
        assertEquals(1, genericGroupSet.at("/result/structuredContent/rowCount").asInt());

        JsonNode columns = call(handler, request(24, "list_prism_columns",
                "{\"session_id\":\"analysis_demo\"}"));
        assertTrue(columns.at("/result/structuredContent").toString().contains("rough.cluster_id"));
        assertTrue(columns.at("/result/structuredContent").toString().contains("rough.similarity_to_representative"));

        JsonNode clustering = call(handler, request(25, "get_prism_clustering", """
                {"session_id":"analysis_demo","analysis_id":"rough","include_singletons":true}
                """));
        assertEquals(2, clustering.at("/result/structuredContent/totalClusters").asInt());
        assertEquals("cluster_1", clustering.at("/result/structuredContent/clusters/0/clusterId").asText());

        JsonNode members = call(handler, request(26, "get_prism_cluster_members", """
                {"session_id":"analysis_demo","analysis_id":"rough","cluster_id":"cluster_1"}
                """));
        assertEquals("CMP-001", members.at("/result/structuredContent/members/0/rowId").asText());

        JsonNode rowSet = call(handler, request(27, "create_prism_cluster_row_set", """
                {
                  "session_id":"analysis_demo",
                  "analysis_id":"rough",
                  "cluster_id":"cluster_1",
                  "row_set_id":"cluster_one"
                }
                """));
        assertEquals(1, rowSet.at("/result/structuredContent/rowCount").asInt());

        JsonNode published = call(handler, request(28, "get_prism_row_set_members", """
                {"session_id":"analysis_demo","row_set_id":"cluster_one"}
                """));
        assertEquals("cluster_1",
                published.at("/result/structuredContent/members/0/fields/prism.column.rough.cluster_id").asText());
    }
    @Test
    void canOpenExamplePrismPackAndInspectSessionThroughToolCalls() throws Exception {
        Path pack = examplePrismPack();
        McpJsonRpcHandler handler = McpJsonRpcHandler.createDefault();
        String path = pack.toString().replace("\\", "\\\\");

        JsonNode opened = call(handler, request(10, "open_prism_snapshot", """
                {"path":"%s","session_id":"example_pack","label":"Example pack"}
                """.formatted(path)));
        assertEquals("example_pack", opened.at("/result/structuredContent/sessionId").asText());
        assertEquals(3, opened.at("/result/structuredContent/totalRowCount").asInt());

        JsonNode columns = call(handler, request(11, "list_prism_columns", """
                {"session_id":"example_pack"}
                """));
        assertTrue(columns.at("/result/structuredContent").toString().contains("smiles"));
        assertTrue(columns.at("/result/structuredContent").toString().contains("pIC50"));
        assertTrue(columns.at("/result/structuredContent/3/raw/predictionWorkflowKey").isNull());

        JsonNode description = call(handler, request(12, "describe_prism_session_for_agent", """
                {"session_id":"example_pack"}
                """));
        assertEquals("example_pack", description.at("/result/structuredContent/summary/sessionId").asText());
        assertTrue(description.at("/result/structuredContent/structureColumns").size() >= 1);
        assertTrue(description.at("/result/structuredContent/endpointColumns").size() >= 1);

        JsonNode members = call(handler, request(13, "get_prism_row_set_members", """
                {"session_id":"example_pack","row_set_id":"all","limit":1}
                """));
        assertEquals(1, members.at("/result/structuredContent/members").size());
        assertTrue(members.at("/result/structuredContent/members/0/fields").toString().contains("prism.column.smiles"));
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
        call(handler, request(1, "open_prism_snapshot", "{\"path\":\"" + path + "\",\"session_id\":\"demo\"}"));
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
        call(handler, request(1, "open_prism_snapshot", "{\"path\":\"" + path + "\",\"session_id\":\"demo\"}"));
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
    void scaffoldSarWorkflowDiscoversProjectsAndExportsBuckets() throws Exception {
        McpJsonRpcHandler handler = McpJsonRpcHandler.createDefault();
        Path dataset = mmpDataset();
        String path = dataset.toString().replace("\\", "\\\\");
        call(handler, request(1, "open_prism_snapshot", "{\"path\":\"" + path + "\",\"session_id\":\"sar_demo\"}"));

        JsonNode discovery = call(handler, request(2, "discover_prism_scaffolds", """
                {"session_id":"sar_demo","row_set_id":"all","discovery_id":"benzene_discovery","min_scaffold_heavy_atoms":6,"min_support":2,"limit":5}
                """));
        assertEquals("benzene_discovery", discovery.at("/result/structuredContent/discoveryId").asText());
        assertTrue(discovery.at("/result/structuredContent/totalCandidates").asInt() >= 1);
        assertEquals("scaffold_1", discovery.at("/result/structuredContent/candidates/0/candidateId").asText());
        assertEquals(2, discovery.at("/result/structuredContent/candidates/0/supportCount").asInt());

        JsonNode analysis = call(handler, request(3, "analyze_prism_scaffold", """
                {"session_id":"sar_demo","row_set_id":"all","scaffold_analysis_id":"benzene_sar","scaffold_smiles":"[cH:1]1ccccc1","exit_atom_map_labels":{"1":"phenyl_exit"},"top_substituent_limit":5}
                """));
        assertEquals("benzene_sar", analysis.at("/result/structuredContent/scaffoldAnalysisId").asText());
        assertEquals(2, analysis.at("/result/structuredContent/matchedCount").asInt());
        assertTrue(analysis.at("/result/structuredContent/mappedScaffoldSmiles").asText().contains(":1"));
        assertEquals("phenyl_exit", analysis.at("/result/structuredContent/exitAtomMapLabels/0/label").asText());
        assertTrue(analysis.at("/result/structuredContent/matchedExampleRowIds").size() > 0);
        assertTrue(analysis.at("/result/structuredContent/observedExitVectorCount").asInt() >= 1);
        int scaffoldAtom = analysis.at("/result/structuredContent/exitAtomMapLabels/0/scaffoldAtom").asInt();

        JsonNode projection = call(handler, request(4, "get_prism_scaffold_projection", """
                {"scaffold_analysis_id":"benzene_sar","scaffold_atom_maps":[1],"column_ids":["pIC50"],"threshold":2.0,"limit":10,"example_limit":2}
                """));
        assertEquals(1, projection.at("/result/structuredContent/dimension").asInt());
        assertEquals("phenyl_exit", projection.at("/result/structuredContent/scaffoldExitVectors/0/label").asText());
        assertTrue(projection.at("/result/structuredContent/suppressedUnmatchedBucketCount").canConvertToInt());
        assertTrue(projection.at("/result/structuredContent/totalBuckets").asInt() >= 1);
        String bucketKey = projection.at("/result/structuredContent/rows/0/bucketKey").asText();
        assertTrue(projection.at("/result/structuredContent/rows/0/count").asInt() >= 1);
        assertTrue(projection.at("/result/structuredContent/rows/0/context/cleanMatchedContext").isBoolean());
        assertEquals("pIC50", projection.at("/result/structuredContent/rows/0/columnSummaries/0/columnId").asText());
        assertTrue(projection.at("/result/structuredContent/rows/0/columnSummaries/0/numeric/median").isNumber());

        JsonNode rowSet = call(handler, request(5, "create_prism_scaffold_bucket_row_set", """
                {"scaffold_analysis_id":"benzene_sar","scaffold_atoms":[SCAT],"bucket_key":"BUCKET","row_set_id":"first_benzene_bucket"}
                """.replace("SCAT", Integer.toString(scaffoldAtom)).replace("BUCKET", bucketKey.replace("\\", "\\\\").replace("\"", "\\\""))));
        assertEquals("first_benzene_bucket", rowSet.at("/result/structuredContent/rowSetId").asText());
        assertTrue(rowSet.at("/result/structuredContent/rowCount").asInt() >= 1);

        JsonNode export = call(handler, request(6, "export_prism_scaffold_projection", """
                {"scaffold_analysis_id":"benzene_sar","scaffold_atoms":[SCAT],"output_name":"scaffold/benzene-projection.tsv"}
                """.replace("SCAT", Integer.toString(scaffoldAtom))));
        Path artifact = Path.of(export.at("/result/structuredContent/artifact/path").asText());
        String tsv = Files.readString(artifact);
        assertTrue(tsv.startsWith("bucket_key\tcount\texample_row_ids"));
        assertTrue(tsv.contains(bucketKey));

        JsonNode guide = call(handler, request(7, "get_structurized_tool_guide", "{\"topic\":\"scaffold_sar_workflow\"}"));
        String scaffoldGuide = guide.at("/result/content/0/text").asText();
        assertTrue(scaffoldGuide.contains("Scaffold SAR Workflow"));
        assertTrue(scaffoldGuide.contains("exit_atom_map_labels"));
        assertTrue(scaffoldGuide.contains("cleanMatchedContext"));
        assertTrue(scaffoldGuide.contains("create_prism_scaffold_bucket_row_set"));
        assertTrue(scaffoldGuide.contains("discover_prism_scaffolds"));
        assertTrue(scaffoldGuide.contains("several endpoints"));
        assertTrue(scaffoldGuide.contains("Zero-hit diagnosis"));
        JsonNode materialized = call(handler, request(8, "materialize_prism_scaffold_analysis", """
                {"scaffold_analysis_id":"benzene_sar","output_prefix":"sar.benzene","scaffold_atom_maps":[1]}
                """));
        assertEquals("sar.benzene.matched",
                materialized.at("/result/structuredContent/matchedRowSetId").asText());
        assertEquals("sar.benzene.phenyl_exit",
                materialized.at("/result/structuredContent/dimensionColumnIds/phenyl_exit").asText());
        assertEquals(2, materialized.at("/result/structuredContent/matchedCount").asInt());
        assertFalse(materialized.at("/result/structuredContent/reused").asBoolean());

        JsonNode reused = call(handler, request(9, "materialize_prism_scaffold_analysis", """
                {"scaffold_analysis_id":"benzene_sar","output_prefix":"sar.benzene","scaffold_atom_maps":[1]}
                """));
        assertTrue(reused.at("/result/structuredContent/reused").asBoolean());
        JsonNode columns = call(handler, request(10, "list_prism_columns", """
                {"session_id":"sar_demo"}
                """));
        assertTrue(columns.at("/result/structuredContent").toString().contains("sar.benzene.phenyl_exit"));
        assertTrue(scaffoldGuide.contains("materialize_prism_scaffold_analysis"));
    }

    @Test
    void exportSelectionTableWritesTsvWithEndpointsAndDecompositionColumns() throws Exception {
        McpJsonRpcHandler handler = McpJsonRpcHandler.createDefault();
        Path dataset = prismDataset();
        String path = dataset.toString().replace("\\", "\\\\");
        call(handler, request(1, "open_prism_snapshot", "{\"path\":\"" + path + "\",\"session_id\":\"demo\"}"));
        call(handler, request(2, "register_structure", """
                {"smiles":"CCCO","structure_id":"butanol_a","label":"Butanol A","fields":{"prism.subject_id":"CMP-001","batch":"A1"}}
                """));
        call(handler, request(3, "register_structure", """
                {"smiles":"CCCO","structure_id":"butanol_b","label":"Butanol B","fields":{"prism.subject_id":"CMP-002","batch":"B1"}}
                """));
        call(handler, request(4, "search_substructure", """
                {"query":"CCO","query_type":"smiles","repository_ids":["session"],"output_mode":"ids","create_selection":true,"selection_id":"alcohol_export"}
                """));
        call(handler, request(5, "create_decomposition_config", """
                {
                  "config_id":"export_split",
                  "config":{
                    "version":"series-decomposition-v1",
                    "rules":[{"id":"split_root","labelToSplit":null,"smarts":"CCO","atomLabels":{"0":"alkyl","1":"linker","2":"head"}}]
                  }
                }
                """));
        call(handler, request(6, "evaluate_decomposition", """
                {"evaluation_id":"export_eval","config_id":"export_split","selection_id":"alcohol_export"}
                """));

        JsonNode exported = call(handler, request(7, "export_selection_table", """
                {"selection_id":"alcohol_export","dataset_id":"demo","endpoint_ids":["pIC50"],"decomposition_evaluation_id":"export_eval","include_fields":true,"include_subject_measurement_dates":true,"output_name":"exports/alcohols.tsv"}
                """));
        assertEquals(2, exported.at("/result/structuredContent/summary/rowCount").asInt());
        assertEquals(2, exported.at("/result/structuredContent/summary/selectedStructureCount").asInt());
        assertEquals("tsv", exported.at("/result/structuredContent/artifact/format").asText());
        assertTrue(exported.at("/result/structuredContent/summary/columns").toString().contains("decomp_root_alkyl_fragment_smiles"));
        Path artifact = Path.of(exported.at("/result/structuredContent/artifact/path").asText());
        String tsv = Files.readString(artifact);
        assertTrue(tsv.startsWith("structure_id	repository_id	subject_id"));
        assertTrue(tsv.contains("subject_first_measurement	subject_last_measurement	subject_measurement_endpoint_count"));
        assertTrue(tsv.contains("endpoint_id	result_type	numeric_state	value"));
        assertTrue(tsv.contains("first_measurement	last_measurement"));
        assertTrue(tsv.contains("decomposition_status"));
        assertTrue(tsv.contains("butanol_a	session	CMP-001"));
        assertTrue(tsv.contains("2025-01-01T08:00:00Z	2026-01-06T09:00:00Z	2"));
        assertTrue(tsv.contains("pIC50	NUMERIC	VALUE	7.2"));
        assertTrue(tsv.contains("2026-01-05T08:00:00Z	2026-01-06T09:00:00Z"));
        assertEquals(3, tsv.split("\n").length);

        JsonNode structureOnly = call(handler, request(8, "export_selection_table", """
                {"selection_id":"alcohol_export","include_smiles":false,"output_name":"exports/alcohols-structures.tsv"}
                """));
        assertEquals(2, structureOnly.at("/result/structuredContent/summary/rowCount").asInt());
        assertFalse(structureOnly.at("/result/structuredContent/summary/columns").toString().contains("canonical_smiles"));

        call(handler, request(9, "create_repository", """
                {"repository_id":"other","label":"Other"}
                """));
        call(handler, request(10, "register_structure", """
                {"smiles":"CCCO","repository_id":"other","structure_id":"other_butanol"}
                """));
        call(handler, request(11, "evaluate_decomposition", """
                {"evaluation_id":"other_eval","config_id":"export_split","repository_id":"other"}
                """));
        JsonNode mismatch = call(handler, request(12, "export_selection_table", """
                {"selection_id":"alcohol_export","decomposition_evaluation_id":"other_eval","output_name":"exports/mismatch.tsv"}
                """));
        assertTrue(mismatch.at("/result/isError").asBoolean());
        assertEquals("selection_repository_mismatch", mismatch.at("/result/structuredContent/code").asText());
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
    void canOpenSnapshotMaterializeRowSetSearchAndFetchEndpointResults() throws Exception {
        McpJsonRpcHandler handler = McpJsonRpcHandler.createDefault();
        Path dataset = prismDataset();
        String path = dataset.toString().replace("\\", "\\\\");

        JsonNode open = call(handler, request(10, "open_prism_snapshot", "{\"path\":\"" + path + "\",\"session_id\":\"demo\",\"label\":\"Demo\"}"));
        assertEquals("demo", open.at("/result/structuredContent/datasetId").asText());
        assertEquals(2, open.at("/result/structuredContent/structureSubjectCount").asInt());

        JsonNode description = call(handler, request(11, "describe_prism_snapshot", "{\"session_id\":\"demo\"}"));
        assertEquals("FULL", description.at("/result/structuredContent/capabilities/endpointResultFidelity").asText());

        JsonNode materialized = call(handler, request(12, "materialize_prism_row_set", "{\"session_id\":\"demo\",\"row_set_id\":\"series:Kinase:A\"}"));
        String repositoryId = materialized.at("/result/structuredContent/repositoryId").asText();
        assertEquals("prism:demo:series:Kinase:A", repositoryId);
        assertEquals("series:Kinase:A", materialized.at("/result/structuredContent/rowSetId").asText());
        assertEquals(2, materialized.at("/result/structuredContent/rowsSeen").asInt());
        assertEquals(2, materialized.at("/result/structuredContent/structuresImported").asInt());

        JsonNode search = call(handler, request(13, "search_substructure", "{\"query\":\"c1ccncc1\",\"repository_ids\":[\"" + repositoryId + "\"],\"output_mode\":\"ids\",\"create_selection\":true,\"selection_id\":\"pyridines\"}"));
        assertEquals("CMP-001", search.at("/result/structuredContent/matches/0/structureId").asText());
        assertEquals("pyridines", search.at("/result/structuredContent/selection/selectionId").asText());

        JsonNode values = call(handler, request(14, "get_prism_endpoint_results", "{\"session_id\":\"demo\",\"row_ids\":[\"CMP-001\"],\"endpoint_ids\":[\"pIC50\"]}"));
        assertEquals("CMP-001", values.at("/result/structuredContent/0/rowId").asText());
        assertTrue(values.at("/result/structuredContent/0/subjectId").isMissingNode());
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

        call(handler, request(3, "search_substructure", "{\"query\":\"CC\",\"output_mode\":\"ids\",\"create_selection\":true,\"selection_id\":\"unsafe_export_selection\"}"));
        JsonNode exportResponse = call(handler, request(4, "export_selection_table", "{\"selection_id\":\"unsafe_export_selection\",\"output_name\":\"../bad.tsv\"}"));
        assertTrue(exportResponse.at("/result/isError").asBoolean());
        assertEquals("invalid_artifact_path", exportResponse.at("/result/structuredContent/code").asText());
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
    void agentCanDiscoverValidateSaveAndPublishPrismReports() throws Exception {
        McpJsonRpcHandler handler = McpJsonRpcHandler.createDefault();

        JsonNode schema = call(handler, request(1, "get_prism_report_schema", "{}"));
        assertEquals(1, schema.at("/result/structuredContent/prismReportVersion").asInt());
        assertEquals(7, schema.at("/result/structuredContent/blockTypes").size());
        assertTrue(schema.at("/result/structuredContent/blockTypes").toString().contains("compound-cards"));

        var openArgs = mapper.createObjectNode();
        openArgs.put("path", prismDataset().toString());
        openArgs.put("session_id", "reports");
        call(handler, request(2, "open_prism_snapshot", mapper.writeValueAsString(openArgs)));

        var invalidArgs = mapper.createObjectNode();
        invalidArgs.put("session_id", "reports");
        invalidArgs.put("source", prismReport("pIC5O"));
        JsonNode invalid = call(handler, request(3, "validate_prism_report",
                mapper.writeValueAsString(invalidArgs)));
        assertFalse(invalid.at("/result/structuredContent/valid").asBoolean());
        assertTrue(invalid.at("/result/structuredContent/diagnostics").toString().contains("pIC50"));

        Path output = tempDir.resolve("agent-analysis.prism.md");
        var saveArgs = mapper.createObjectNode();
        saveArgs.put("session_id", "reports");
        saveArgs.put("source", prismReport("pIC50"));
        saveArgs.put("output_path", output.toString());
        JsonNode saved = call(handler, request(4, "save_prism_report",
                mapper.writeValueAsString(saveArgs)));
        assertTrue(saved.at("/result/structuredContent/saved").asBoolean(), saved.toPrettyString());
        assertTrue(Files.isRegularFile(output));

        var pathArgs = mapper.createObjectNode();
        pathArgs.put("session_id", "reports");
        pathArgs.put("path", output.toString());
        JsonNode validated = call(handler, request(5, "validate_prism_report",
                mapper.writeValueAsString(pathArgs)));
        assertTrue(validated.at("/result/structuredContent/valid").asBoolean(), validated.toPrettyString());

        JsonNode published = call(handler, request(6, "publish_prism_report",
                mapper.writeValueAsString(pathArgs)));
        assertTrue(published.at("/result/structuredContent/published").asBoolean(), published.toPrettyString());
        assertEquals("report:agent-analysis",
                published.at("/result/structuredContent/viewId").asText());

        JsonNode guide = call(handler, request(7, "get_structurized_tool_guide",
                "{\"topic\":\"report_workflow\"}"));
        assertTrue(guide.at("/result/structuredContent/markdown").asText()
                .contains("validate_prism_report"));
    }

    @Test
    void internalErrorsWithoutExceptionMessagesStillReturnActionableDiagnostics() {
        assertEquals(
                "Unexpected NullPointerException while handling tool list_prism_columns.",
                McpJsonRpcHandler.internalErrorMessage(
                        "tool list_prism_columns", new NullPointerException()));
    }

    @Test
    void unknownJsonRpcMethodReturnsProtocolError() throws Exception {
        McpJsonRpcHandler handler = McpJsonRpcHandler.createDefault();

        JsonNode response = call(handler, "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"unknown\",\"params\":{}}");

        assertEquals(-32601, response.at("/error/code").asInt());
        assertEquals("method_not_found", response.at("/error/data/code").asText());
    }

    private static Path examplePrismPack() throws Exception {
        URL resource = McpJsonRpcHandlerTest.class.getClassLoader()
                .getResource("prism-fixtures/example.prismpack/prism-pack.json");
        if (resource == null) {
            throw new IllegalStateException("Missing PrismPack test fixture");
        }
        return Path.of(resource.toURI()).getParent();
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

    private Path prismDataset() throws Exception {
        Path dir = tempDir.resolve("prism-tsv");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("endpoints.prism.tsv"), String.join("\n",
                "endpoint_id\tname\tpath\tdatatype\tendpoint_type\tevaluation_mode\tunit\tscale",
                "pIC50\tpIC50\tassay/pIC50\tNUMERIC\tMEASURED\tIMMEDIATE\tpIC50\tLOG",
                "logD\tlogD\tproperties/logD\tNUMERIC\tMEASURED\tIMMEDIATE\t\tABSOLUTE",
                ""
        ));
        Files.writeString(dir.resolve("subjects.prism.tsv"), String.join("\n",
                "subject_id\tstructure_id\tbatch_id\tproject\tseries\tsmiles",
                "CMP-001\tS-001\tB-001\tKinase\tA\tc1ccncc1",
                "CMP-002\tS-002\tB-002\tKinase\tA\tCCN",
                ""
        ));
        Files.writeString(dir.resolve("values.prism.tsv"), String.join("\n",
                "subject_id\tendpoint_id\tstate\tmean\tn\tfirst_measurement\tlast_measurement",
                "CMP-001\tpIC50\tVALUE\t7.2\t3\t2026-01-05T08:00:00Z\t2026-01-06T09:00:00Z",
                "CMP-001\tlogD\tVALUE\t2.5\t1\t2025-01-01T08:00:00Z\t2025-01-03T08:00:00Z",
                "CMP-002\tpIC50\tVALUE\t6.1\t1\t2026-07-01T08:00:00Z\t2026-07-02T09:00:00Z",
                "CMP-002\tlogD\tVALUE\t3.1\t1\t2026-07-03T08:00:00Z\t2026-07-04T09:00:00Z",
                ""
        ));
        Files.writeString(dir.resolve("subject_sets.prism.tsv"), String.join("\n",
                "subject_set_id\tname\tset_type\tsubject_set_scope\tparent_set_id\tdescription",
                "series:A\tSeries A\tSERIES\tPROJECT\t\tKinase series A",
                ""
        ));
        Files.writeString(dir.resolve("subject_set_memberships.prism.tsv"), String.join("\n",
                "subject_set_id\tsubject_id",
                "series:A\tCMP-001",
                "series:A\tCMP-002",
                ""
        ));
        return dir;
    }

    private static String prismReport(String valueColumn) {
        return """
                ---
                prismReportVersion: 1
                dataset: current
                title: Agent analysis
                ---

                # Agent analysis

                ~~~prism
                {
                  "type": "scatter",
                  "id": "activity-property",
                  "rowSet": "all",
                  "xColumn": "%s",
                  "yColumn": "logD"
                }
                ~~~
                """.formatted(valueColumn);
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
