package tech.molecules.structurized.ai.prism;

import java.nio.file.Path;

public record OpenPrismPackRequest(
        Path path,
        String sessionId,
        String label
) {}
