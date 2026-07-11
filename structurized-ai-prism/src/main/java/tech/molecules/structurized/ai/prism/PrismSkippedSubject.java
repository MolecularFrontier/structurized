package tech.molecules.structurized.ai.prism;

public record PrismSkippedSubject(
        String subjectId,
        String reason,
        String message
) {}
