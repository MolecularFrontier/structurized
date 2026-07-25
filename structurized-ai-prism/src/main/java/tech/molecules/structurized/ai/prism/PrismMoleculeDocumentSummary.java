package tech.molecules.structurized.ai.prism;

public record PrismMoleculeDocumentSummary(
        String documentId,
        String title,
        String mode,
        String structure,
        long revision
) {
}
