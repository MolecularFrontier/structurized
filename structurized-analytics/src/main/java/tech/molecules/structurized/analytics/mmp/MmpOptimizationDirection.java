package tech.molecules.structurized.analytics.mmp;

/** Desired direction for interpreting one endpoint's directed MMP delta. */
public enum MmpOptimizationDirection {
    HIGHER_IS_BETTER("Higher is better", 1),
    LOWER_IS_BETTER("Lower is better", -1),
    NEUTRAL("Neutral", 0);

    private final String label;
    private final int sign;

    MmpOptimizationDirection(String label, int sign) {
        this.label = label;
        this.sign = sign;
    }

    public double desiredDelta(double meanDelta) {
        return sign == 0 ? 0.0 : sign * meanDelta;
    }

    public boolean isPreferred(double meanDelta) {
        return sign != 0 && desiredDelta(meanDelta) > 0.0;
    }

    public boolean isOpposed(double meanDelta) {
        return sign != 0 && desiredDelta(meanDelta) < 0.0;
    }

    @Override
    public String toString() {
        return label;
    }
}
