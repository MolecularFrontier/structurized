package tech.molecules.structurized.ai.mcp;

public final class McpStdioServer {
    private McpStdioServer() {}

    public static void main(String[] args) throws Exception {
        McpJsonRpcHandler handler = McpJsonRpcHandler.createDefault();
        handler.runStdio(System.in, System.out);
    }
}
