package tech.molecules.structurized.ai.prism;

import java.util.List;

public record PrismMoleculeListView(
        PrismMoleculeListSummary summary,
        long workspaceRevision,
        List<PrismMoleculeDocumentSummary> documents
) {
    public PrismMoleculeListView {
        documents = documents == null ? List.of() : List.copyOf(documents);
    }
}
