package tech.molecules.structurized.decomposition;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One ordered decomposition rule.
 *
 * <p>The SMARTS defines the query graph. {@code atomLabels} maps query atom indices to output
 * labels. A {@code null} {@code labelToSplit} targets the root molecule.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DecompositionRule(
        String id,
        String title,
        String description,
        String labelToSplit,
        String smarts,
        Map<Integer, String> atomLabels,
        Boolean enabled
) {
    public DecompositionRule {
        atomLabels = atomLabels == null ? Map.of() : Map.copyOf(atomLabels);
    }

    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    public static DecompositionRule of(String id, String labelToSplit, String smarts, Map<Integer, String> atomLabels) {
        return new DecompositionRule(id, null, null, labelToSplit, smarts, new LinkedHashMap<>(atomLabels), true);
    }
}
