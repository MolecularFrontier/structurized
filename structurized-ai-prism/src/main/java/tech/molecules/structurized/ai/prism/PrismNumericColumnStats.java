package tech.molecules.structurized.ai.prism;

public record PrismNumericColumnStats(
        Double mean,
        Double median,
        Double q1,
        Double q3,
        Double min,
        Double max,
        Double threshold,
        String thresholdDirection,
        Integer thresholdHitCount,
        Double thresholdHitRate
) {
}
