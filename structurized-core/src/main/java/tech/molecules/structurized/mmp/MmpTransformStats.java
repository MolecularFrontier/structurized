package tech.molecules.structurized.mmp;

import java.util.List;
import java.util.Objects;

/**
 * Numeric delta summary for one directed MMP transformation.
 *
 * <p>{@code upCount}, {@code downCount} and {@code tieCount} partition {@code supportCount} by the
 * sign of the observed delta. They are stored explicitly because {@code positiveFraction} alone
 * cannot distinguish a decrease from an exact tie, and ties are common for censored endpoints where
 * both pair members sit at an assay ceiling or floor.</p>
 *
 * <p>{@code supportingPairs} carries every pair behind the transform rather than a capped sample.
 * When read back from an artifact it may be empty or bounded, because the repository loads pairs
 * only where they are requested.</p>
 */
public record MmpTransformStats(
        String transformId,
        String fromValueIdcode,
        String toValueIdcode,
        int cutCount,
        int supportCount,
        double meanDelta,
        double medianDelta,
        double standardDeviation,
        double minDelta,
        double maxDelta,
        double positiveFraction,
        int upCount,
        int downCount,
        int tieCount,
        List<MmpPair> supportingPairs
) {
    /**
     * Smallest number of non-tied pairs for which a two-sided binomial test against p=0.5 can reach
     * p &lt;= 0.05. At n=5 the most extreme outcome still yields p=0.0625.
     */
    public static final int MIN_TESTABLE_SUPPORT = 6;

    public MmpTransformStats {
        Objects.requireNonNull(transformId, "transformId");
        Objects.requireNonNull(fromValueIdcode, "fromValueIdcode");
        Objects.requireNonNull(toValueIdcode, "toValueIdcode");
        if (upCount < 0 || downCount < 0 || tieCount < 0) {
            throw new IllegalArgumentException("sign counts must not be negative");
        }
        if (upCount + downCount + tieCount != supportCount) {
            throw new IllegalArgumentException(
                    "sign counts must partition supportCount: " + upCount + "+" + downCount + "+"
                            + tieCount + " != " + supportCount);
        }
        supportingPairs = List.copyOf(supportingPairs == null ? List.of() : supportingPairs);
    }

    /** Returns a copy carrying the supplied supporting pairs. */
    public MmpTransformStats withSupportingPairs(List<MmpPair> pairs) {
        return new MmpTransformStats(transformId, fromValueIdcode, toValueIdcode, cutCount,
                supportCount, meanDelta, medianDelta, standardDeviation, minDelta, maxDelta,
                positiveFraction, upCount, downCount, tieCount, pairs);
    }

    /** Fraction of supporting pairs whose delta decreased. */
    public double negativeFraction() {
        return supportCount == 0 ? 0.0 : downCount / (double) supportCount;
    }

    /** Fraction of supporting pairs with an exactly zero delta. */
    public double tieFraction() {
        return supportCount == 0 ? 0.0 : tieCount / (double) supportCount;
    }

    /** Number of non-tied pairs, which is the sample size of any direction test. */
    public int decidedCount() {
        return upCount + downCount;
    }

    /** Pairs supporting the majority direction, ties excluded. */
    public int majorityCount() {
        return Math.max(upCount, downCount);
    }

    /** True when enough non-tied support exists for a two-sided binomial test at p &lt;= 0.05. */
    public boolean isDirectionTestable() {
        return decidedCount() >= MIN_TESTABLE_SUPPORT;
    }
}
