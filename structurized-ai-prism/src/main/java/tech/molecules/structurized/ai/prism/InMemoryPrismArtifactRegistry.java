package tech.molecules.structurized.ai.prism;

import tech.molecules.structurized.ai.model.ChemOperationException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class InMemoryPrismArtifactRegistry implements PrismArtifactRegistry {
    private final Map<String, Map<String, PrismAnalysis>> artifactsBySession = new LinkedHashMap<>();

    @Override
    public synchronized boolean contains(String sessionId, String artifactId) {
        return artifactsBySession.getOrDefault(requireText(sessionId, "sessionId"), Map.of())
                .containsKey(requireText(artifactId, "artifactId"));
    }

    @Override
    public synchronized void add(String sessionId, PrismAnalysis artifact) {
        String normalizedSessionId = requireText(sessionId, "sessionId");
        PrismAnalysis value = Objects.requireNonNull(artifact, "artifact");
        PrismAnalysisSummary summary = value.summary();
        if (!normalizedSessionId.equals(summary.sessionId())) {
            throw new IllegalArgumentException("artifact sessionId does not match registry sessionId");
        }
        Map<String, PrismAnalysis> artifacts = artifactsBySession.computeIfAbsent(
                normalizedSessionId,
                ignored -> new LinkedHashMap<>()
        );
        if (artifacts.putIfAbsent(summary.analysisId(), value) != null) {
            throw new ChemOperationException(
                    "duplicate_prism_analysis",
                    "Prism analysis " + summary.analysisId() + " already exists."
            );
        }
    }

    @Override
    public synchronized List<PrismAnalysisSummary> summaries(String sessionId) {
        return artifactsBySession.getOrDefault(requireText(sessionId, "sessionId"), Map.of())
                .values().stream()
                .map(PrismAnalysis::summary)
                .toList();
    }

    @Override
    public synchronized <T extends PrismAnalysis> T require(String sessionId,
                                                             String artifactId,
                                                             Class<T> type) {
        String normalizedSessionId = requireText(sessionId, "sessionId");
        String normalizedArtifactId = requireText(artifactId, "artifactId");
        PrismAnalysis artifact = artifactsBySession.getOrDefault(normalizedSessionId, Map.of())
                .get(normalizedArtifactId);
        if (artifact == null) {
            throw new ChemOperationException(
                    "prism_analysis_not_found",
                    "Prism analysis " + normalizedArtifactId + " does not exist in session "
                            + normalizedSessionId + "."
            );
        }
        if (!type.isInstance(artifact)) {
            throw new ChemOperationException(
                    "prism_analysis_type_mismatch",
                    "Prism analysis " + normalizedArtifactId + " has type " + artifact.summary().type() + "."
            );
        }
        return type.cast(artifact);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
