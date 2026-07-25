package tech.molecules.structurized.ai.prism;

import java.util.List;

public record PrismClusterMembersView(
        String sessionId,
        String analysisId,
        String clusterId,
        int totalMembers,
        int offset,
        int limit,
        List<PrismClusterMember> members
) {
    public PrismClusterMembersView {
        members = members == null ? List.of() : List.copyOf(members);
    }
}
