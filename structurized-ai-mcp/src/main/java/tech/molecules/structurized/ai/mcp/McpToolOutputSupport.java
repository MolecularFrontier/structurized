package tech.molecules.structurized.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tech.molecules.structurized.ai.model.ChemOperationException;

final class McpToolOutputSupport {
    private final McpArtifactService artifacts;

    McpToolOutputSupport(McpArtifactService artifacts) {
        this.artifacts = artifacts;
    }

    Object maybeFile(ObjectNode args, String sourceTool, Object responsePayload, Object summary, Integer rowCount) {
        return maybeFile(args, sourceTool, responsePayload, summary, rowCount, responsePayload);
    }

    Object maybeFile(ObjectNode args, String sourceTool, Object artifactPayload, Object summary, Integer rowCount, Object responsePayload) {
        String outputTarget = normalizeOutputTarget(optionalString(args, "output_target", "response"));
        if ("response".equals(outputTarget)) {
            return responsePayload;
        }
        McpArtifactService.ArtifactRecord artifact = writeJsonArtifact(args, sourceTool, artifactPayload, rowCount);
        return new ArtifactOutputResult(summary, artifact);
    }

    McpArtifactService.ArtifactRecord writeJsonArtifact(ObjectNode args, String sourceTool, Object payload, Integer rowCount) {
        String format = optionalString(args, "format", "json").trim().toLowerCase();
        if (!"json".equals(format)) {
            throw new ChemOperationException("unsupported_artifact_format", "Only json artifact output is supported.");
        }
        return artifacts.writeJson(
                sourceTool,
                optionalString(args, "output_name", null),
                optionalBoolean(args, "overwrite", false),
                payload,
                rowCount
        );
    }

    private static String normalizeOutputTarget(String outputTarget) {
        String normalized = outputTarget == null || outputTarget.isBlank() ? "response" : outputTarget.trim().toLowerCase();
        if (!"response".equals(normalized) && !"file".equals(normalized)) {
            throw new ChemOperationException("invalid_output_target", "output_target must be response or file.");
        }
        return normalized;
    }

    private static String optionalString(ObjectNode args, String name, String defaultValue) {
        JsonNode node = args.get(name);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (!node.isTextual()) {
            throw new ChemOperationException("invalid_arguments", "Argument " + name + " must be a string.");
        }
        return node.asText();
    }

    private static boolean optionalBoolean(ObjectNode args, String name, boolean defaultValue) {
        JsonNode node = args.get(name);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (!node.isBoolean()) {
            throw new ChemOperationException("invalid_arguments", "Argument " + name + " must be a boolean.");
        }
        return node.asBoolean();
    }

    private record ArtifactOutputResult(Object summary, McpArtifactService.ArtifactRecord artifact) {}
}
