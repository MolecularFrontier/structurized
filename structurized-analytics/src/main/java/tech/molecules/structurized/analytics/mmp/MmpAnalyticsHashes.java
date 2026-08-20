package tech.molecules.structurized.analytics.mmp;

import tech.molecules.structurized.mmp.MmpMiningConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.stream.Collectors;

public final class MmpAnalyticsHashes {
    private MmpAnalyticsHashes() {}

    public static String mmpConfigHash(MmpMiningConfig config) {
        String noCutRules = config.noCutBondRules().stream()
                .map(rule -> rule.getClass().getName())
                .sorted()
                .collect(Collectors.joining(","));
        return sha256(String.join("|",
                "maxCuts=" + config.maxCuts(),
                "singleBondsOnly=" + config.singleBondsOnly(),
                "skipSmallRings=" + config.skipSmallRings(),
                "allowMacrocycleRingCuts=" + config.allowMacrocycleRingCuts(),
                "macrocycleMinRingSize=" + config.macrocycleMinRingSize(),
                "allowMixedRingChainCutSets=" + config.allowMixedRingChainCutSets(),
                "minKeyHeavyAtoms=" + config.minKeyHeavyAtoms(),
                "minVariableHeavyAtoms=" + config.minVariableHeavyAtoms(),
                "maxVariableHeavyAtoms=" + config.maxVariableHeavyAtoms(),
                "maxVariableToMolHeavyAtomFraction=" + config.maxVariableToMolHeavyAtomFraction(),
                "maxFragmentationRecordsPerCompound=" + config.maxFragmentationRecordsPerCompound(),
                "maxPairsPerKey=" + config.maxPairsPerKey(),
                "emitReverseTransforms=" + config.emitReverseTransforms(),
                "minTransformSupport=" + config.minTransformSupport(),
                "noCutRules=" + noCutRules
        ));
    }

    static String statsConfigHash(MmpEndpointStatsConfig config) {
        String endpoints = config.endpointIds().stream().sorted().collect(Collectors.joining(","));
        String setMap = config.endpointSubjectSetIds().entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey() + "=" + e.getValue()))
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(","));
        return sha256(String.join("|",
                "endpoints=" + endpoints,
                "setMap=" + setMap,
                "universeMode=" + config.universeMode(),
                "measuredSubjectSetType=" + config.measuredSubjectSetType(),
                "measuredSubjectSetScope=" + config.measuredSubjectSetScope(),
                "batchSize=" + config.batchSize()
        ));
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
