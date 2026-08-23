package tech.molecules.structurized.ai.prism;

import tech.molecules.structurized.prism.score.ScorePoint;

import java.util.List;

public record DefinePrismEndpointScoreRequest(
        String sessionId,
        String scoreId,
        String endpointId,
        String displayName,
        String description,
        String xScale,
        Boolean clampOutsideRange,
        List<ScorePoint> points,
        String outputColumnId
) {
    public DefinePrismEndpointScoreRequest {
        points = points == null ? List.of() : List.copyOf(points);
    }
}
