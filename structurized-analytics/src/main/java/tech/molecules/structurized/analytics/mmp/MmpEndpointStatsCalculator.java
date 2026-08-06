package tech.molecules.structurized.analytics.mmp;

import com.actelion.research.chem.StereoMolecule;
import tech.molecules.structurized.mmp.MmpInputCompound;
import tech.molecules.structurized.mmp.MmpMiner;
import tech.molecules.structurized.mmp.MmpMiningConfig;
import tech.molecules.structurized.mmp.MmpMiningResult;
import tech.molecules.structurized.mmp.MmpPair;
import tech.molecules.structurized.mmp.MmpStatsAggregator;
import tech.molecules.structurized.mmp.MmpTransformStats;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure, snapshot-driven MMP endpoint statistics calculator.
 *
 * <p>The calculator performs no provider access and no persistence. In union mode it mines the
 * structural universe once and reuses the resulting pairs for every endpoint.</p>
 */
public final class MmpEndpointStatsCalculator {
    private final Clock clock;

    public MmpEndpointStatsCalculator() {
        this(Clock.systemUTC());
    }

    public MmpEndpointStatsCalculator(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public MmpAnalyticsComputation compute(
            MmpAnalyticsSnapshot snapshot,
            MmpEndpointStatsConfig statsConfig,
            MmpMiningConfig mmpConfig
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(statsConfig, "statsConfig");
        Objects.requireNonNull(mmpConfig, "mmpConfig");

        List<MmpEndpointSnapshot> endpoints = selectEndpoints(snapshot, statsConfig);
        validateSubjectSetMappings(endpoints, statsConfig);
        Map<String, StereoMolecule> structures = snapshot.structuresBySubjectId();
        String mmpConfigHash = MmpAnalyticsHashes.mmpConfigHash(mmpConfig);
        String statsConfigHash = MmpAnalyticsHashes.statsConfigHash(statsConfig);
        Instant createdAt = clock.instant();
        ArrayList<String> warnings = new ArrayList<>();

        if (statsConfig.universeMode() == MmpUniverseMode.PER_ENDPOINT) {
            return computePerEndpoint(snapshot, endpoints, structures, statsConfig, mmpConfig,
                    mmpConfigHash, statsConfigHash, createdAt, warnings);
        }
        return computeUnion(snapshot, endpoints, structures, statsConfig, mmpConfig,
                mmpConfigHash, statsConfigHash, createdAt, warnings);
    }

    private MmpAnalyticsComputation computeUnion(
            MmpAnalyticsSnapshot snapshot,
            List<MmpEndpointSnapshot> endpoints,
            Map<String, StereoMolecule> structures,
            MmpEndpointStatsConfig statsConfig,
            MmpMiningConfig mmpConfig,
            String mmpConfigHash,
            String statsConfigHash,
            Instant createdAt,
            List<String> warnings
    ) {
        LinkedHashSet<String> unionSubjects = new LinkedHashSet<>();
        ArrayList<String> subjectSetIds = new ArrayList<>();
        for (MmpEndpointSnapshot endpoint : endpoints) {
            subjectSetIds.add(endpoint.subjectSetId());
            unionSubjects.addAll(endpoint.subjectIds());
        }

        MmpComputedUniverse computedUniverse = computeUniverse(
                statsConfig.universeId() != null
                        ? statsConfig.universeId()
                        : "mmp-union-" + shortHash(mmpConfigHash + "|" + String.join(",", subjectSetIds)),
                statsConfig.universeName() != null ? statsConfig.universeName() : "Union MMP universe",
                subjectSetIds,
                List.copyOf(unionSubjects),
                structures,
                snapshot.sourceId(),
                mmpConfig,
                mmpConfigHash,
                createdAt,
                warnings
        );

        ArrayList<MmpComputedEndpointStats> endpointStats = new ArrayList<>();
        for (MmpEndpointSnapshot endpoint : endpoints) {
            endpointStats.add(computeEndpointStats(endpoint, computedUniverse.universe(),
                    computedUniverse.pairs(), statsConfigHash, mmpConfigHash, mmpConfig, createdAt, warnings));
        }
        return new MmpAnalyticsComputation(
                List.of(computedUniverse), endpointStats, endpoints.size(), warnings);
    }

    private MmpAnalyticsComputation computePerEndpoint(
            MmpAnalyticsSnapshot snapshot,
            List<MmpEndpointSnapshot> endpoints,
            Map<String, StereoMolecule> structures,
            MmpEndpointStatsConfig statsConfig,
            MmpMiningConfig mmpConfig,
            String mmpConfigHash,
            String statsConfigHash,
            Instant createdAt,
            List<String> warnings
    ) {
        ArrayList<MmpComputedUniverse> universes = new ArrayList<>();
        ArrayList<MmpComputedEndpointStats> endpointStats = new ArrayList<>();
        for (MmpEndpointSnapshot endpoint : endpoints) {
            MmpComputedUniverse computedUniverse = computeUniverse(
                    "mmp-" + endpoint.endpointId() + "-"
                            + shortHash(mmpConfigHash + "|" + endpoint.subjectSetId()),
                    "MMP universe for " + endpoint.endpointName(),
                    List.of(endpoint.subjectSetId()),
                    endpoint.subjectIds(),
                    structures,
                    snapshot.sourceId(),
                    mmpConfig,
                    mmpConfigHash,
                    createdAt,
                    warnings
            );
            universes.add(computedUniverse);
            endpointStats.add(computeEndpointStats(endpoint, computedUniverse.universe(),
                    computedUniverse.pairs(), statsConfigHash, mmpConfigHash, mmpConfig, createdAt, warnings));
        }
        return new MmpAnalyticsComputation(universes, endpointStats, endpoints.size(), warnings);
    }

    private static MmpComputedUniverse computeUniverse(
            String universeId,
            String universeName,
            List<String> subjectSetIds,
            List<String> subjectIds,
            Map<String, StereoMolecule> structures,
            String sourceId,
            MmpMiningConfig mmpConfig,
            String mmpConfigHash,
            Instant createdAt,
            List<String> warnings
    ) {
        ArrayList<String> structuralSubjectIds = new ArrayList<>();
        ArrayList<MmpInputCompound> compounds = new ArrayList<>();
        subjectIds.stream().sorted().forEach(subjectId -> {
            StereoMolecule structure = structures.get(subjectId);
            if (structure != null) {
                structuralSubjectIds.add(subjectId);
                compounds.add(new MmpInputCompound(subjectId, structure, null));
            }
        });
        int missing = subjectIds.size() - structuralSubjectIds.size();
        if (missing > 0) {
            warnings.add("Skipped " + missing + " subjects without structures for universe " + universeId);
        }

        MmpMiningResult result = MmpMiner.mine(compounds, mmpConfig);
        MmpUniverse universe = new MmpUniverse(
                universeId,
                universeName,
                subjectSetIds,
                mmpConfigHash,
                createdAt,
                "source=" + sourceId + ";subjects=" + subjectIds.size()
                        + ";structures=" + structuralSubjectIds.size() + ";missingStructures=" + missing
        );
        return new MmpComputedUniverse(universe, structuralSubjectIds,
                result.fragmentationRecords(), result.pairs(), missing);
    }

    private static MmpComputedEndpointStats computeEndpointStats(
            MmpEndpointSnapshot endpoint,
            MmpUniverse universe,
            List<MmpPair> structuralPairs,
            String statsConfigHash,
            String mmpConfigHash,
            MmpMiningConfig mmpConfig,
            Instant createdAt,
            List<String> warnings
    ) {
        Map<String, Double> values = endpoint.valuesBySubjectId();
        int missingValues = endpoint.subjectIds().size() - values.size();
        if (missingValues > 0) {
            warnings.add("Endpoint '" + endpoint.endpointId() + "' is missing usable numeric values for "
                    + missingValues + " of " + endpoint.subjectIds().size() + " subjects");
        }

        ArrayList<MmpPair> valuedPairs = new ArrayList<>();
        for (MmpPair pair : structuralPairs) {
            Double valueA = values.get(pair.compoundIdA());
            Double valueB = values.get(pair.compoundIdB());
            if (valueA == null || valueB == null) {
                continue;
            }
            valuedPairs.add(new MmpPair(
                    pair.compoundIdA(),
                    pair.compoundIdB(),
                    valueA,
                    valueB,
                    null,
                    pair.keyIdcode(),
                    pair.fromValueIdcode(),
                    pair.toValueIdcode(),
                    pair.transformId(),
                    pair.cutCount()
            ));
        }
        List<MmpTransformStats> transformStats = MmpStatsAggregator.aggregate(valuedPairs, mmpConfig);
        MmpEndpointStatsRun run = new MmpEndpointStatsRun(
                "mmp-stats-" + endpoint.endpointId() + "-"
                        + shortHash(universe.universeId() + "|" + statsConfigHash + "|" + createdAt),
                endpoint.endpointId(),
                endpoint.subjectSetId(),
                universe.universeId(),
                mmpConfigHash,
                statsConfigHash,
                createdAt,
                endpoint.subjectIds().size(),
                values.size(),
                valuedPairs.size(),
                transformStats.size(),
                "endpointName=" + endpoint.endpointName()
        );
        return new MmpComputedEndpointStats(run, transformStats);
    }

    private static List<MmpEndpointSnapshot> selectEndpoints(
            MmpAnalyticsSnapshot snapshot,
            MmpEndpointStatsConfig config
    ) {
        if (config.endpointIds().isEmpty()) {
            return snapshot.endpoints();
        }
        LinkedHashMap<String, MmpEndpointSnapshot> byId = new LinkedHashMap<>();
        snapshot.endpoints().forEach(endpoint -> byId.put(endpoint.endpointId(), endpoint));
        LinkedHashSet<String> selectedIds = new LinkedHashSet<>();
        ArrayList<MmpEndpointSnapshot> selected = new ArrayList<>();
        for (String endpointId : config.endpointIds()) {
            if (!selectedIds.add(endpointId)) {
                throw new IllegalArgumentException("duplicate requested endpoint ID '" + endpointId + "'");
            }
            MmpEndpointSnapshot endpoint = byId.get(endpointId);
            if (endpoint == null) {
                throw new IllegalArgumentException("endpoint '" + endpointId + "' is not present in snapshot '"
                        + snapshot.sourceId() + "'");
            }
            selected.add(endpoint);
        }
        return List.copyOf(selected);
    }

    private static void validateSubjectSetMappings(
            List<MmpEndpointSnapshot> endpoints,
            MmpEndpointStatsConfig config
    ) {
        for (MmpEndpointSnapshot endpoint : endpoints) {
            String configuredSubjectSetId = config.endpointSubjectSetIds().get(endpoint.endpointId());
            if (configuredSubjectSetId != null && !configuredSubjectSetId.equals(endpoint.subjectSetId())) {
                throw new IllegalArgumentException("configured subject set '" + configuredSubjectSetId
                        + "' does not match snapshot subject set '" + endpoint.subjectSetId()
                        + "' for endpoint '" + endpoint.endpointId() + "'");
            }
        }
    }

    private static String shortHash(String value) {
        return Integer.toUnsignedString(value.hashCode(), 36);
    }
}
