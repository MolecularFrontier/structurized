package tech.molecules.structurized.analytics.mmp;

import java.time.Duration;

/** Counts and timing from one recommendation search. */
public record MmpRecommendationDiagnostics(
        int fragmentationCount,
        int selectedFragmentationCount,
        int primaryTransformCount,
        int applicationAttemptCount,
        int appliedCount,
        int invalidCount,
        int unchangedCount,
        int duplicateCount,
        int resultCount,
        boolean truncated,
        Duration duration
) {
    public MmpRecommendationDiagnostics {
        duration = duration == null || duration.isNegative() ? Duration.ZERO : duration;
    }
}
