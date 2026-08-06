package tech.molecules.structurized.analytics.mmp;

import tech.molecules.structurized.mmp.MmpMiningConfig;
import tech.molecules.structurized.mmp.MmpSelectionMode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable input for one endpoint-backed MMP recommendation search. */
public record MmpRecommendationRequest(
        String inputIdcode,
        Set<Integer> selectedAtomIndices,
        MmpSelectionMode selectionMode,
        List<MmpEndpointPreference> endpointPreferences,
        String primaryRunId,
        MmpMiningConfig miningConfig,
        int maxResults,
        int maxApplicationAttempts
) {
    public static final int DEFAULT_MAX_RESULTS = 500;
    public static final int DEFAULT_MAX_APPLICATION_ATTEMPTS = 100_000;

    public MmpRecommendationRequest {
        if (inputIdcode == null || inputIdcode.isBlank()) {
            throw new IllegalArgumentException("inputIdcode must not be blank");
        }
        inputIdcode = inputIdcode.trim();
        LinkedHashSet<Integer> selected = new LinkedHashSet<>();
        for (Integer atom : selectedAtomIndices == null ? Set.<Integer>of() : selectedAtomIndices) {
            if (atom == null || atom < 0) {
                throw new IllegalArgumentException("selectedAtomIndices must contain non-negative values");
            }
            selected.add(atom);
        }
        selectedAtomIndices = Set.copyOf(selected);
        selectionMode = Objects.requireNonNull(selectionMode, "selectionMode");
        if (selectionMode.requiresSelection() && selectedAtomIndices.isEmpty()) {
            throw new IllegalArgumentException(selectionMode + " requires selected atoms");
        }
        endpointPreferences = List.copyOf(
                endpointPreferences == null ? List.of() : endpointPreferences);
        if (endpointPreferences.isEmpty()) {
            throw new IllegalArgumentException("endpointPreferences must not be empty");
        }
        LinkedHashSet<String> runIds = new LinkedHashSet<>();
        for (MmpEndpointPreference preference : endpointPreferences) {
            Objects.requireNonNull(preference, "endpointPreferences must not contain null");
            if (!runIds.add(preference.runId())) {
                throw new IllegalArgumentException("duplicate endpoint run " + preference.runId());
            }
        }
        if (primaryRunId == null || primaryRunId.isBlank()) {
            throw new IllegalArgumentException("primaryRunId must not be blank");
        }
        primaryRunId = primaryRunId.trim();
        if (!runIds.contains(primaryRunId)) {
            throw new IllegalArgumentException("primaryRunId must be one of endpointPreferences");
        }
        miningConfig = Objects.requireNonNull(miningConfig, "miningConfig");
        if (maxResults < 1 || maxApplicationAttempts < 1) {
            throw new IllegalArgumentException("recommendation limits must be positive");
        }
    }

    public static MmpRecommendationRequest defaults(
            String inputIdcode,
            Set<Integer> selectedAtomIndices,
            MmpSelectionMode selectionMode,
            List<MmpEndpointPreference> endpointPreferences,
            String primaryRunId,
            MmpMiningConfig miningConfig
    ) {
        return new MmpRecommendationRequest(
                inputIdcode, selectedAtomIndices, selectionMode, endpointPreferences,
                primaryRunId, miningConfig,
                DEFAULT_MAX_RESULTS, DEFAULT_MAX_APPLICATION_ATTEMPTS);
    }

    public MmpEndpointPreference primaryPreference() {
        return endpointPreferences.stream()
                .filter(preference -> primaryRunId.equals(preference.runId()))
                .findFirst()
                .orElseThrow();
    }
}
