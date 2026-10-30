package tech.molecules.structurized.mmp;

import java.util.List;

/**
 * Configuration for in-memory MMP fragmentation, pairing, and statistics.
 */
public final class MmpMiningConfig {
    private final int maxCuts;
    private final boolean singleBondsOnly;
    private final boolean skipSmallRings;
    private final boolean allowMacrocycleRingCuts;
    private final int macrocycleMinRingSize;
    private final boolean allowMixedRingChainCutSets;
    private final boolean allowHydrogenCuts;
    private final int minKeyHeavyAtoms;
    private final int minVariableHeavyAtoms;
    private final int maxVariableHeavyAtoms;
    private final double maxVariableToMolHeavyAtomFraction;
    private final int maxFragmentationRecordsPerCompound;
    private final int maxPairsPerKey;
    private final boolean emitReverseTransforms;
    private final int minTransformSupport;
    private final List<NoCutBondRule> noCutBondRules;

    private MmpMiningConfig(Builder builder) {
        this.maxCuts = builder.maxCuts;
        this.singleBondsOnly = builder.singleBondsOnly;
        this.skipSmallRings = builder.skipSmallRings;
        this.allowMacrocycleRingCuts = builder.allowMacrocycleRingCuts;
        this.macrocycleMinRingSize = builder.macrocycleMinRingSize;
        this.allowMixedRingChainCutSets = builder.allowMixedRingChainCutSets;
        this.allowHydrogenCuts = builder.allowHydrogenCuts;
        this.minKeyHeavyAtoms = builder.minKeyHeavyAtoms;
        this.minVariableHeavyAtoms = builder.minVariableHeavyAtoms;
        this.maxVariableHeavyAtoms = builder.maxVariableHeavyAtoms;
        this.maxVariableToMolHeavyAtomFraction = builder.maxVariableToMolHeavyAtomFraction;
        this.maxFragmentationRecordsPerCompound = builder.maxFragmentationRecordsPerCompound;
        this.maxPairsPerKey = builder.maxPairsPerKey;
        this.emitReverseTransforms = builder.emitReverseTransforms;
        this.minTransformSupport = builder.minTransformSupport;
        this.noCutBondRules = List.copyOf(builder.noCutBondRules);
        validate();
    }

    public static MmpMiningConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .maxCuts(maxCuts)
                .singleBondsOnly(singleBondsOnly)
                .skipSmallRings(skipSmallRings)
                .allowMacrocycleRingCuts(allowMacrocycleRingCuts)
                .macrocycleMinRingSize(macrocycleMinRingSize)
                .allowMixedRingChainCutSets(allowMixedRingChainCutSets)
                .allowHydrogenCuts(allowHydrogenCuts)
                .minKeyHeavyAtoms(minKeyHeavyAtoms)
                .minVariableHeavyAtoms(minVariableHeavyAtoms)
                .maxVariableHeavyAtoms(maxVariableHeavyAtoms)
                .maxVariableToMolHeavyAtomFraction(maxVariableToMolHeavyAtomFraction)
                .maxFragmentationRecordsPerCompound(maxFragmentationRecordsPerCompound)
                .maxPairsPerKey(maxPairsPerKey)
                .emitReverseTransforms(emitReverseTransforms)
                .minTransformSupport(minTransformSupport)
                .noCutBondRules(noCutBondRules);
    }

    private void validate() {
        if (maxCuts < 1 || maxCuts > 2) {
            throw new IllegalArgumentException("maxCuts must be 1 or 2 in V1");
        }
        if (macrocycleMinRingSize < 3) {
            throw new IllegalArgumentException("macrocycleMinRingSize must be at least 3");
        }
        if (minKeyHeavyAtoms < 0 || minVariableHeavyAtoms < 0 || maxVariableHeavyAtoms < minVariableHeavyAtoms) {
            throw new IllegalArgumentException("invalid heavy atom size limits");
        }
        if (maxVariableToMolHeavyAtomFraction <= 0.0 || maxVariableToMolHeavyAtomFraction > 1.0) {
            throw new IllegalArgumentException("maxVariableToMolHeavyAtomFraction must be in (0, 1]");
        }
        if (maxFragmentationRecordsPerCompound < 0 || maxPairsPerKey < 0 || minTransformSupport < 1) {
            throw new IllegalArgumentException("safety limits must be non-negative (0 means unlimited); support must be positive");
        }
    }

    public int maxCuts() { return maxCuts; }
    public boolean singleBondsOnly() { return singleBondsOnly; }
    public boolean skipSmallRings() { return skipSmallRings; }
    public boolean allowMacrocycleRingCuts() { return allowMacrocycleRingCuts; }
    public int macrocycleMinRingSize() { return macrocycleMinRingSize; }
    public boolean allowMixedRingChainCutSets() { return allowMixedRingChainCutSets; }
    /** Whether one-cut hydrogen replacement fragmentations (-H &lt;-&gt; -R) are enumerated. */
    public boolean allowHydrogenCuts() { return allowHydrogenCuts; }
    public int minKeyHeavyAtoms() { return minKeyHeavyAtoms; }
    public int minVariableHeavyAtoms() { return minVariableHeavyAtoms; }
    public int maxVariableHeavyAtoms() { return maxVariableHeavyAtoms; }
    public double maxVariableToMolHeavyAtomFraction() { return maxVariableToMolHeavyAtomFraction; }
    /** Zero is unlimited; exceeding a positive safety budget fails instead of truncating. */
    public int maxFragmentationRecordsPerCompound() { return maxFragmentationRecordsPerCompound; }
    /** Directed emissions per constant-key/cut-count group. Zero is unlimited; positive budgets fail closed. */
    public int maxPairsPerKey() { return maxPairsPerKey; }
    public boolean emitReverseTransforms() { return emitReverseTransforms; }
    public int minTransformSupport() { return minTransformSupport; }
    public List<NoCutBondRule> noCutBondRules() { return noCutBondRules; }

    public static final class Builder {
        private int maxCuts = 2;
        private boolean singleBondsOnly = true;
        private boolean skipSmallRings = true;
        private boolean allowMacrocycleRingCuts = true;
        private int macrocycleMinRingSize = 10;
        private boolean allowMixedRingChainCutSets = false;
        private boolean allowHydrogenCuts = false;
        private int minKeyHeavyAtoms = 6;
        private int minVariableHeavyAtoms = 1;
        private int maxVariableHeavyAtoms = 15;
        private double maxVariableToMolHeavyAtomFraction = 0.5;
        private int maxFragmentationRecordsPerCompound = 0;
        private int maxPairsPerKey = 0;
        private boolean emitReverseTransforms = true;
        private int minTransformSupport = 2;
        private List<NoCutBondRule> noCutBondRules = NoCutBondRules.defaultRules();

        public Builder maxCuts(int maxCuts) { this.maxCuts = maxCuts; return this; }
        public Builder singleBondsOnly(boolean singleBondsOnly) { this.singleBondsOnly = singleBondsOnly; return this; }
        public Builder skipSmallRings(boolean skipSmallRings) { this.skipSmallRings = skipSmallRings; return this; }
        public Builder allowMacrocycleRingCuts(boolean allowMacrocycleRingCuts) { this.allowMacrocycleRingCuts = allowMacrocycleRingCuts; return this; }
        public Builder macrocycleMinRingSize(int macrocycleMinRingSize) { this.macrocycleMinRingSize = macrocycleMinRingSize; return this; }
        public Builder allowMixedRingChainCutSets(boolean allowMixedRingChainCutSets) { this.allowMixedRingChainCutSets = allowMixedRingChainCutSets; return this; }
        public Builder allowHydrogenCuts(boolean allowHydrogenCuts) { this.allowHydrogenCuts = allowHydrogenCuts; return this; }
        public Builder minKeyHeavyAtoms(int minKeyHeavyAtoms) { this.minKeyHeavyAtoms = minKeyHeavyAtoms; return this; }
        public Builder minVariableHeavyAtoms(int minVariableHeavyAtoms) { this.minVariableHeavyAtoms = minVariableHeavyAtoms; return this; }
        public Builder maxVariableHeavyAtoms(int maxVariableHeavyAtoms) { this.maxVariableHeavyAtoms = maxVariableHeavyAtoms; return this; }
        public Builder maxVariableToMolHeavyAtomFraction(double maxVariableToMolHeavyAtomFraction) { this.maxVariableToMolHeavyAtomFraction = maxVariableToMolHeavyAtomFraction; return this; }
        public Builder maxFragmentationRecordsPerCompound(int maxFragmentationRecordsPerCompound) { this.maxFragmentationRecordsPerCompound = maxFragmentationRecordsPerCompound; return this; }
        public Builder maxPairsPerKey(int maxPairsPerKey) { this.maxPairsPerKey = maxPairsPerKey; return this; }
        public Builder emitReverseTransforms(boolean emitReverseTransforms) { this.emitReverseTransforms = emitReverseTransforms; return this; }
        public Builder minTransformSupport(int minTransformSupport) { this.minTransformSupport = minTransformSupport; return this; }
        public Builder noCutBondRules(List<NoCutBondRule> noCutBondRules) {
            this.noCutBondRules = List.copyOf(noCutBondRules == null ? List.of() : noCutBondRules);
            return this;
        }

        public MmpMiningConfig build() {
            return new MmpMiningConfig(this);
        }
    }
}
