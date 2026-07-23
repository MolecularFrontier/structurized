package tech.molecules.structurized.clustering;

public record SimilarityClusteringConfig(String descriptor, double threshold, int maxCrossNeighbors) {
    public static final String DESCRIPTOR_SKELSPHERES = "skelspheres";
    public static final String STRATEGY_GREEDY_LEADERS = "greedy_leaders";
    public static final double DEFAULT_THRESHOLD = 0.80;
    public static final int DEFAULT_MAX_CROSS_NEIGHBORS = 5;

    public SimilarityClusteringConfig() {
        this(DESCRIPTOR_SKELSPHERES, DEFAULT_THRESHOLD, DEFAULT_MAX_CROSS_NEIGHBORS);
    }

    public SimilarityClusteringConfig {
        descriptor = descriptor == null || descriptor.isBlank() ? DESCRIPTOR_SKELSPHERES : descriptor.trim().toLowerCase();
        if (!DESCRIPTOR_SKELSPHERES.equals(descriptor)) {
            throw new IllegalArgumentException("Unsupported clustering descriptor: " + descriptor);
        }
        if (Double.isNaN(threshold) || threshold < 0.0 || threshold > 1.0) {
            throw new IllegalArgumentException("threshold must be between 0.0 and 1.0");
        }
        if (maxCrossNeighbors < 0) {
            throw new IllegalArgumentException("maxCrossNeighbors must be >= 0");
        }
    }
}
