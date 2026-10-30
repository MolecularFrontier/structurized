package tech.molecules.structurized.analytics.mmp;

/** A recommendation was aborted before returning any partial result. */
public final class MmpRecommendationLimitException extends IllegalStateException {
    public enum Limit { INPUT_SIZE, FRAGMENTATIONS, TRANSFORM_STATISTICS, TASKS, TIME }
    private final Limit limit;
    public MmpRecommendationLimitException(Limit limit) {
        super("Recommendation search exceeded the server "+limit.name()+" budget");
        this.limit=limit;
    }
    public Limit limit() { return limit; }
}
