package tech.molecules.structurized.decomposition;

import java.util.List;

/**
 * Versioned, ordered rule configuration for recursive molecular decomposition.
 */
public record DecompositionConfig(
        String version,
        List<DecompositionRule> rules
) {
    public static final String DEFAULT_VERSION = "series-decomposition-v1";

    public DecompositionConfig {
        version = version == null || version.isBlank() ? DEFAULT_VERSION : version;
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    public static DecompositionConfig of(List<DecompositionRule> rules) {
        return new DecompositionConfig(DEFAULT_VERSION, rules);
    }
}
