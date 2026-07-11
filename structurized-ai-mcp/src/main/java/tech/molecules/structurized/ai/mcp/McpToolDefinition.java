package tech.molecules.structurized.ai.mcp;

import java.util.Map;

record McpToolDefinition(
        String name,
        String description,
        Map<String, Object> inputSchema
) {}
