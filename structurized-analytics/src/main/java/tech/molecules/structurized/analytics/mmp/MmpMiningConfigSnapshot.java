package tech.molecules.structurized.analytics.mmp;

import tech.molecules.structurized.mmp.MmpMiningConfig;
import tech.molecules.structurized.mmp.NoCutBondRules;

/** Persistable representation of the resolved MMP mining configuration. */
public record MmpMiningConfigSnapshot(
        int maxCuts, boolean singleBondsOnly, boolean skipSmallRings,
        boolean allowMacrocycleRingCuts, int macrocycleMinRingSize,
        boolean allowMixedRingChainCutSets, int minKeyHeavyAtoms,
        int minVariableHeavyAtoms, int maxVariableHeavyAtoms,
        double maxVariableToMolHeavyAtomFraction,
        int maxFragmentationRecordsPerCompound, int maxPairsPerKey,
        boolean emitReverseTransforms, int minTransformSupport,
        String noCutRuleProfile
) {
    public static final String DEFAULT_NO_CUT_RULE_PROFILE = "structurized-default-v1";

    public MmpMiningConfigSnapshot {
        if (!DEFAULT_NO_CUT_RULE_PROFILE.equals(noCutRuleProfile)) {
            throw new IllegalArgumentException("unsupported no-cut rule profile: " + noCutRuleProfile);
        }
    }

    public static MmpMiningConfigSnapshot from(MmpMiningConfig config) {
        String actualRules = config.noCutBondRules().stream()
                .map(rule -> rule.getClass().getName()).sorted().toList().toString();
        String defaultRules = NoCutBondRules.defaultRules().stream()
                .map(rule -> rule.getClass().getName()).sorted().toList().toString();
        if (!actualRules.equals(defaultRules)) {
            throw new IllegalArgumentException("only the default no-cut rule profile can be persisted");
        }
        return new MmpMiningConfigSnapshot(config.maxCuts(), config.singleBondsOnly(), config.skipSmallRings(),
                config.allowMacrocycleRingCuts(), config.macrocycleMinRingSize(), config.allowMixedRingChainCutSets(),
                config.minKeyHeavyAtoms(), config.minVariableHeavyAtoms(), config.maxVariableHeavyAtoms(),
                config.maxVariableToMolHeavyAtomFraction(), config.maxFragmentationRecordsPerCompound(),
                config.maxPairsPerKey(), config.emitReverseTransforms(), config.minTransformSupport(),
                DEFAULT_NO_CUT_RULE_PROFILE);
    }

    public MmpMiningConfig toMiningConfig() {
        return MmpMiningConfig.builder()
                .maxCuts(maxCuts).singleBondsOnly(singleBondsOnly).skipSmallRings(skipSmallRings)
                .allowMacrocycleRingCuts(allowMacrocycleRingCuts).macrocycleMinRingSize(macrocycleMinRingSize)
                .allowMixedRingChainCutSets(allowMixedRingChainCutSets).minKeyHeavyAtoms(minKeyHeavyAtoms)
                .minVariableHeavyAtoms(minVariableHeavyAtoms).maxVariableHeavyAtoms(maxVariableHeavyAtoms)
                .maxVariableToMolHeavyAtomFraction(maxVariableToMolHeavyAtomFraction)
                .maxFragmentationRecordsPerCompound(maxFragmentationRecordsPerCompound)
                .maxPairsPerKey(maxPairsPerKey).emitReverseTransforms(emitReverseTransforms)
                .minTransformSupport(minTransformSupport).noCutBondRules(NoCutBondRules.defaultRules()).build();
    }
}
