package tech.molecules.structurized.ai.prism;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record PrismSarDimensionAssignment(
        String label,
        int scaffoldAtom,
        Integer scaffoldAtomMap,
        Map<String, String> valuesByRowId
) {
    public PrismSarDimensionAssignment {
        label = label == null ? "" : label.trim();
        valuesByRowId = valuesByRowId == null || valuesByRowId.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(valuesByRowId));
    }
}
