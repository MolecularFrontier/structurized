package tech.molecules.structurized.ai.trace;

import java.util.Objects;

public record AgentElementReference(
        AgentElementKind kind,
        String contextId,
        String elementId,
        AgentAttentionRole role,
        AgentReferenceSource source
) {
    public AgentElementReference {
        Objects.requireNonNull(kind, "kind");
        contextId = requireText(contextId, "contextId");
        elementId = requireText(elementId, "elementId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(source, "source");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
