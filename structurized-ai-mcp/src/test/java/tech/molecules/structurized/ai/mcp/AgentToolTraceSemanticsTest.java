package tech.molecules.structurized.ai.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import tech.molecules.structurized.ai.trace.AgentAttentionRole;
import tech.molecules.structurized.ai.trace.AgentElementKind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentToolTraceSemanticsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void graphNeighborhoodSeparatesFocusedCenterFromReturnedRows() throws Exception {
        ObjectNode arguments = (ObjectNode) mapper.readTree("""
                {"session_id":"project","center_row_id":"A19"}
                """);
        ObjectNode result = (ObjectNode) mapper.readTree("""
                {"center":{"rowId":"A19"},"neighbors":[{"rowId":"A21"},{"rowId":"A28"}]}
                """);

        var requested = AgentToolTraceSemantics.requestReferences("inspect_prism_graph_neighborhood", arguments);
        var returned = AgentToolTraceSemantics.resultReferences("inspect_prism_graph_neighborhood", arguments, result);

        assertEquals(1, requested.size());
        assertEquals(AgentAttentionRole.FOCUS, requested.getFirst().role());
        assertEquals(AgentElementKind.PRISM_ROW, requested.getFirst().kind());
        assertTrue(returned.stream().anyMatch(ref -> ref.elementId().equals("A21")
                && ref.role() == AgentAttentionRole.RETURNED));
        assertTrue(returned.stream().anyMatch(ref -> ref.elementId().equals("A28")
                && ref.role() == AgentAttentionRole.RETURNED));
    }

    @Test
    void addedMoleculeDocumentsAreProposed() throws Exception {
        ObjectNode arguments = (ObjectNode) mapper.readTree("""
                {"session_id":"project","list_id":"ideas","molecules":[{"structure":"CCN"}]}
                """);
        ObjectNode result = (ObjectNode) mapper.readTree("""
                {"sessionId":"project","documents":[{"documentId":"candidate-7","smiles":"CCN"}]}
                """);

        var references = AgentToolTraceSemantics.resultReferences("add_prism_molecules", arguments, result);

        assertEquals(1, references.size());
        assertEquals(AgentElementKind.PRISM_MOLECULE_DOCUMENT, references.getFirst().kind());
        assertEquals(AgentAttentionRole.PROPOSED, references.getFirst().role());
        assertEquals("candidate-7", references.getFirst().elementId());
    }

    @Test
    void unclassifiedToolsDoNotCopyIdentifierLookingPayloadFields() throws Exception {
        ObjectNode arguments = (ObjectNode) mapper.readTree("{\"structure_id\":\"private\"}");
        ObjectNode result = (ObjectNode) mapper.readTree("{\"rowId\":\"also-private\"}");

        assertTrue(AgentToolTraceSemantics.requestReferences("list_repositories", arguments).isEmpty());
        assertTrue(AgentToolTraceSemantics.resultReferences("list_repositories", arguments, result).isEmpty());
    }
}
