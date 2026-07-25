package tech.molecules.structurized.ai.prism;

import java.util.List;

public record PrismCategoricalColumnStats(
        int distinctCount,
        List<PrismCategoryFrequency> topValues
) {
    public PrismCategoricalColumnStats {
        topValues = topValues == null ? List.of() : List.copyOf(topValues);
    }
}
