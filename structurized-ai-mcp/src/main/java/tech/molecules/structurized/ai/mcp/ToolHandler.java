package tech.molecules.structurized.ai.mcp;

import com.fasterxml.jackson.databind.node.ObjectNode;

@FunctionalInterface
interface ToolHandler {
    Object call(ObjectNode args) throws Exception;
}
