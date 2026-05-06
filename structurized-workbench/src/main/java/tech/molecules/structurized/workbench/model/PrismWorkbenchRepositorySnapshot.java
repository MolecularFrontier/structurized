package tech.molecules.structurized.workbench.model;

import tech.molecules.structurized.analytics.mmp.StructureProvider;
import tech.molecules.structurized.prism.provider.inmemory.InMemoryPrismDataset;

import java.util.Objects;

/**
 * Already-materialized PRISM repository data for the Swing workbench.
 */
public record PrismWorkbenchRepositorySnapshot(
        String displayName,
        InMemoryPrismDataset dataset,
        StructureProvider structureProvider
) {
    public PrismWorkbenchRepositorySnapshot {
        displayName = normalize(displayName);
        dataset = Objects.requireNonNull(dataset, "dataset");
        structureProvider = Objects.requireNonNull(structureProvider, "structureProvider");
    }

    private static String normalize(String value) {
        if (value == null) {
            return "PRISM repository";
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? "PRISM repository" : trimmed;
    }
}
