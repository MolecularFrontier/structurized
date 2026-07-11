package tech.molecules.structurized.ai.prism;

import java.nio.file.Path;

public record OpenPrismDatasetRequest(
        Path path,
        String datasetId,
        String label
) {}
