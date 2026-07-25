package tech.molecules.structurized.ai.prism;

import java.util.List;

public record PrismRowSetMembersView(
        PrismRowSetSummary summary,
        int offset,
        int limit,
        List<PrismRowMember> members
) {
    public PrismRowSetMembersView {
        members = members == null ? List.of() : List.copyOf(members);
    }
}
