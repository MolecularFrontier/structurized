package tech.molecules.structurized.ai.prism;

import java.util.List;

public interface PrismArtifactRegistry {
    boolean contains(String sessionId, String artifactId);

    void add(String sessionId, PrismAnalysis artifact);

    List<PrismAnalysisSummary> summaries(String sessionId);

    <T extends PrismAnalysis> T require(String sessionId, String artifactId, Class<T> type);
}
