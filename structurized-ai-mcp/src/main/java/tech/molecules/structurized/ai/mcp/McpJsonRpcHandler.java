package tech.molecules.structurized.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tech.molecules.structurized.ai.model.ChemOperationException;
import tech.molecules.structurized.ai.prism.PrismBridgeService;
import tech.molecules.structurized.ai.repository.StructureRepositoryService;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class McpJsonRpcHandler {
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final ObjectMapper mapper;
    private final McpChemistryTools chemistryTools;

    private McpJsonRpcHandler(ObjectMapper mapper, McpChemistryTools chemistryTools) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.chemistryTools = Objects.requireNonNull(chemistryTools, "chemistryTools");
    }

    public static McpJsonRpcHandler createDefault() {
        ObjectMapper mapper = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        return new McpJsonRpcHandler(mapper, McpChemistryTools.createDefault(mapper));
    }

    public static McpJsonRpcHandler create(StructureRepositoryService repositories, PrismBridgeService prism) {
        ObjectMapper mapper = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        return new McpJsonRpcHandler(mapper, McpChemistryTools.create(mapper, repositories, prism));
    }

    public String handleJson(String json) throws Exception {
        JsonNode request;
        try {
            request = mapper.readTree(json);
        } catch (Exception e) {
            return mapper.writeValueAsString(error(null, -32700, "Parse error", null));
        }
        JsonNode id = request.get("id");
        if (id == null) {
            handleNotification(request);
            return null;
        }
        if (!request.isObject() || !request.hasNonNull("method") || !request.get("method").isTextual()) {
            return mapper.writeValueAsString(error(id, -32600, "Invalid Request", null));
        }
        try {
            Object result = handleRequest(request.get("method").asText(), objectParams(request));
            return mapper.writeValueAsString(success(id, result));
        } catch (MethodNotFoundException e) {
            return mapper.writeValueAsString(error(id, -32601, e.getMessage(), errorData("method_not_found", e.getMessage())));
        } catch (ChemOperationException e) {
            return mapper.writeValueAsString(error(id, -32000, e.getMessage(), errorData(e.code(), e.getMessage())));
        } catch (IllegalArgumentException e) {
            return mapper.writeValueAsString(error(id, -32602, e.getMessage(), errorData("invalid_arguments", e.getMessage())));
        } catch (Exception e) {
            return mapper.writeValueAsString(error(id, -32603, "Internal error", errorData("internal_chemistry_error", e.getMessage())));
        }
    }

    public void runStdio(InputStream input, OutputStream output) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
             PrintWriter writer = new PrintWriter(output, true, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String response = handleJson(line);
                if (response != null) {
                    writer.println(response);
                    writer.flush();
                }
            }
        }
    }

    private Object handleRequest(String method, ObjectNode params) throws Exception {
        return switch (method) {
            case "initialize" -> initializeResult();
            case "ping" -> Map.of();
            case "tools/list" -> toolsListResult();
            case "tools/call" -> toolsCallResult(params);
            default -> throw new MethodNotFoundException("Unsupported JSON-RPC method: " + method);
        };
    }

    private void handleNotification(JsonNode request) {
        // MCP notifications such as notifications/initialized do not require a response.
    }

    private ObjectNode objectParams(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null || params.isNull()) {
            return mapper.createObjectNode();
        }
        if (!params.isObject()) {
            throw new IllegalArgumentException("params must be an object");
        }
        return (ObjectNode) params;
    }

    private Map<String, Object> initializeResult() {
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("tools", Map.of());
        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", "structurized-ai-mcp");
        serverInfo.put("version", "0.3.3-SNAPSHOT");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", PROTOCOL_VERSION);
        result.put("capabilities", capabilities);
        result.put("serverInfo", serverInfo);
        return result;
    }

    private Map<String, Object> toolsListResult() {
        List<Map<String, Object>> tools = chemistryTools.tools().stream()
                .map(tool -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name", tool.name());
                    entry.put("description", tool.description());
                    entry.put("inputSchema", tool.inputSchema());
                    return entry;
                })
                .toList();
        return Map.of("tools", tools);
    }

    private ObjectNode toolsCallResult(ObjectNode params) throws Exception {
        String name = requiredText(params, "name");
        JsonNode argumentsNode = params.get("arguments");
        ObjectNode arguments;
        if (argumentsNode == null || argumentsNode.isNull()) {
            arguments = mapper.createObjectNode();
        } else if (argumentsNode.isObject()) {
            arguments = (ObjectNode) argumentsNode;
        } else {
            throw new IllegalArgumentException("tools/call arguments must be an object");
        }
        try {
            McpChemistryTools.ToolCallResult result = chemistryTools.call(name, arguments);
            return toolResult(result.text(), result.structuredContent(), result.isError());
        } catch (ChemOperationException e) {
            ObjectNode structured = mapper.createObjectNode();
            structured.put("status", "error");
            structured.put("code", e.code());
            structured.put("message", e.getMessage());
            return toolResult(e.getMessage(), structured, true);
        }
    }

    private ObjectNode toolResult(String text, JsonNode structuredContent, boolean isError) {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode textContent = content.addObject();
        textContent.put("type", "text");
        textContent.put("text", text == null ? "" : text);
        result.set("structuredContent", structuredContent == null ? mapper.createObjectNode() : structuredContent);
        if (isError) {
            result.put("isError", true);
        }
        return result;
    }

    private static String requiredText(ObjectNode params, String name) {
        JsonNode node = params.get(name);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new IllegalArgumentException("Missing required string param: " + name);
        }
        return node.asText();
    }

    private ObjectNode success(JsonNode id, Object result) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", mapper.valueToTree(result));
        return response;
    }

    private ObjectNode error(JsonNode id, int code, String message, JsonNode data) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id == null) {
            response.putNull("id");
        } else {
            response.set("id", id);
        }
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message == null ? "" : message);
        if (data != null) {
            error.set("data", data);
        }
        return response;
    }

    private ObjectNode errorData(String code, String message) {
        ObjectNode data = mapper.createObjectNode();
        data.put("code", code == null ? "internal_chemistry_error" : code);
        data.put("message", message == null ? "" : message);
        return data;
    }
    private static final class MethodNotFoundException extends Exception {
        private MethodNotFoundException(String message) {
            super(message);
        }
    }

}
