package tech.molecules.structurized.analytics.mmp;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import static tech.molecules.structurized.analytics.mmp.MmpRecommendationLimitException.Limit.*;

/** Per-search, cooperative resource controls. Instances must not be shared between searches. */
public final class MmpRecommendationBudget {
    public record Limits(int maxAtoms,int maxFragmentations,int maxStatistics,int maxTasks,Duration timeout) {
        public Limits {
            Objects.requireNonNull(timeout);
            if(maxAtoms<1 || maxFragmentations<1 || maxStatistics<1 || maxTasks<1 || timeout.isNegative())
                throw new IllegalArgumentException("Invalid recommendation budgets");
            timeout.toNanos();
        }
        public static Limits serverDefaults() { return new Limits(256,10_000,50_000,100_000,Duration.ofSeconds(10)); }
    }
    private final Limits limits;
    private final BooleanSupplier cancelled;
    private final LongSupplier clock;
    private final long started;
    private int statistics;
    private RuntimeException stopped;
    public MmpRecommendationBudget(Limits limits,BooleanSupplier cancelled) { this(limits,cancelled,System::nanoTime); }
    MmpRecommendationBudget(Limits limits,BooleanSupplier cancelled,LongSupplier clock) {
        this.limits=Objects.requireNonNull(limits);this.cancelled=Objects.requireNonNull(cancelled);
        this.clock=Objects.requireNonNull(clock);this.started=clock.getAsLong();
    }
    static MmpRecommendationBudget unlimited() {
        return new MmpRecommendationBudget(new Limits(Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,Duration.ZERO),()->false);
    }
    public void check() {
        if(stopped!=null)throw stopped;
        if(Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
            stopped=new CancellationException("Recommendation search cancelled");throw stopped;
        }
        if(!limits.timeout().isZero() && clock.getAsLong()-started>=limits.timeout().toNanos()) {
            stopped=new MmpRecommendationLimitException(TIME);throw stopped;
        }
    }
    /** SQLite progress callbacks return true to interrupt; the precise reason is retained for the caller. */
    public boolean shouldStop() {
        try {check();return false;}
        catch(CancellationException | MmpRecommendationLimitException e) {return true;}
    }
    public void inputAtoms(int count) {check();if(count>limits.maxAtoms())throw new MmpRecommendationLimitException(INPUT_SIZE);}
    public void inputBonds(int count) {check();if(count>4L*limits.maxAtoms())throw new MmpRecommendationLimitException(INPUT_SIZE);}
    public void fragments(int count) {check();if(count>limits.maxFragmentations())throw new MmpRecommendationLimitException(FRAGMENTATIONS);}
    public int remainingStatistics() {check();return limits.maxStatistics()-statistics;}
    public void consumeStatistics(int count) {
        check();if(count<0 || count>limits.maxStatistics()-statistics)throw new MmpRecommendationLimitException(TRANSFORM_STATISTICS);
        statistics+=count;
    }
    public void tasks(int count) {check();if(count>limits.maxTasks())throw new MmpRecommendationLimitException(TASKS);}
}
