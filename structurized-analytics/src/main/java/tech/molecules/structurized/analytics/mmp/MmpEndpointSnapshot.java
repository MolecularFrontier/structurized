package tech.molecules.structurized.analytics.mmp;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable endpoint data already materialized for an MMP analytics computation. */
public record MmpEndpointSnapshot(
        String endpointId,
        String endpointName,
        String subjectSetId,
        List<String> subjectIds,
        Map<String, Double> valuesBySubjectId
) {
    public MmpEndpointSnapshot {
        endpointId = requireText(endpointId, "endpointId");
        endpointName = requireText(endpointName, "endpointName");
        subjectSetId = requireText(subjectSetId, "subjectSetId");

        List<String> copiedSubjectIds = List.copyOf(Objects.requireNonNull(subjectIds, "subjectIds"));
        LinkedHashSet<String> uniqueSubjectIds = new LinkedHashSet<>();
        for (String subjectId : copiedSubjectIds) {
            String normalized = requireText(subjectId, "subjectId");
            if (!uniqueSubjectIds.add(normalized)) {
                throw new IllegalArgumentException("duplicate subject ID '" + normalized
                        + "' in endpoint '" + endpointId + "'");
            }
        }
        subjectIds = List.copyOf(uniqueSubjectIds);

        Map<String, Double> suppliedValues = Objects.requireNonNull(valuesBySubjectId, "valuesBySubjectId");
        LinkedHashMap<String, Double> normalizedValues = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : suppliedValues.entrySet()) {
            String subjectId = requireText(entry.getKey(), "value subject ID");
            if (!uniqueSubjectIds.contains(subjectId)) {
                throw new IllegalArgumentException("value for subject '" + subjectId
                        + "' is outside endpoint subject set '" + subjectSetId + "'");
            }
            Double value = Objects.requireNonNull(entry.getValue(), "value for subject '" + subjectId + "'");
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("non-finite value for subject '" + subjectId
                        + "' in endpoint '" + endpointId + "'");
            }
            if (normalizedValues.putIfAbsent(subjectId, value) != null) {
                throw new IllegalArgumentException("duplicate value subject ID '" + subjectId
                        + "' in endpoint '" + endpointId + "'");
            }
        }
        LinkedHashMap<String, Double> orderedValues = new LinkedHashMap<>();
        for (String subjectId : subjectIds) {
            if (normalizedValues.containsKey(subjectId)) {
                orderedValues.put(subjectId, normalizedValues.get(subjectId));
            }
        }
        valuesBySubjectId = Collections.unmodifiableMap(orderedValues);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
