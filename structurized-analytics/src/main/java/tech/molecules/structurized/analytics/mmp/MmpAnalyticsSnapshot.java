package tech.molecules.structurized.analytics.mmp;

import com.actelion.research.chem.StereoMolecule;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Complete immutable input for one provider-independent MMP analytics computation. */
public record MmpAnalyticsSnapshot(
        String sourceId,
        Map<String, StereoMolecule> structuresBySubjectId,
        List<MmpEndpointSnapshot> endpoints
) {
    public MmpAnalyticsSnapshot {
        sourceId = requireText(sourceId, "sourceId");
        Objects.requireNonNull(structuresBySubjectId, "structuresBySubjectId");
        LinkedHashMap<String, StereoMolecule> normalizedStructures = new LinkedHashMap<>();
        for (Map.Entry<String, StereoMolecule> entry : structuresBySubjectId.entrySet()) {
            String subjectId = requireText(entry.getKey(), "structure subject ID");
            StereoMolecule molecule = new StereoMolecule(Objects.requireNonNull(entry.getValue(),
                    "structure for subject '" + subjectId + "'"));
            if (normalizedStructures.putIfAbsent(subjectId, molecule) != null) {
                throw new IllegalArgumentException("duplicate structure subject ID '" + subjectId + "'");
            }
        }
        LinkedHashMap<String, StereoMolecule> copiedStructures = new LinkedHashMap<>();
        normalizedStructures.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> copiedStructures.put(entry.getKey(), entry.getValue()));
        structuresBySubjectId = Collections.unmodifiableMap(copiedStructures);

        endpoints = List.copyOf(Objects.requireNonNull(endpoints, "endpoints"));
        LinkedHashSet<String> endpointIds = new LinkedHashSet<>();
        for (MmpEndpointSnapshot endpoint : endpoints) {
            Objects.requireNonNull(endpoint, "endpoint");
            if (!endpointIds.add(endpoint.endpointId())) {
                throw new IllegalArgumentException("duplicate endpoint ID '" + endpoint.endpointId() + "'");
            }
        }
    }

    @Override
    public Map<String, StereoMolecule> structuresBySubjectId() {
        LinkedHashMap<String, StereoMolecule> copy = new LinkedHashMap<>();
        structuresBySubjectId.forEach((subjectId, molecule) -> copy.put(subjectId, new StereoMolecule(molecule)));
        return Collections.unmodifiableMap(copy);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
