package tech.molecules.structurized.ai.prism;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

final class NullSafeMaps {
    private NullSafeMaps() {}

    static <K, V> Map<K, V> immutableCopy(Map<K, V> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
