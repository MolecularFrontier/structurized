package tech.molecules.structurized.analytics.mmp;

import java.util.Objects;

/** Persists a fully completed MMP analytics computation. */
public final class MmpAnalyticsPersistenceService {
    private final MmpUniverseRepository universeRepository;
    private final MmpPairRepository pairRepository;
    private final MmpEndpointStatsRepository statsRepository;

    public MmpAnalyticsPersistenceService(
            MmpUniverseRepository universeRepository,
            MmpPairRepository pairRepository,
            MmpEndpointStatsRepository statsRepository
    ) {
        this.universeRepository = Objects.requireNonNull(universeRepository, "universeRepository");
        this.pairRepository = Objects.requireNonNull(pairRepository, "pairRepository");
        this.statsRepository = Objects.requireNonNull(statsRepository, "statsRepository");
    }

    public MmpEndpointStatsComputationResult persist(MmpAnalyticsComputation computation) {
        Objects.requireNonNull(computation, "computation");
        if (computation.miningConfig() != null && statsRepository instanceof MmpMiningConfigRepository configs) {
            configs.saveMiningConfig(MmpAnalyticsHashes.mmpConfigHash(computation.miningConfig().toMiningConfig()),
                    computation.miningConfig());
        }
        for (MmpComputedUniverse computed : computation.universes()) {
            universeRepository.saveUniverse(computed.universe(), computed.structuralSubjectIds());
            pairRepository.replaceFragmentationRecords(
                    computed.universe().universeId(), computed.fragmentationRecords());
            pairRepository.replacePairs(computed.universe().universeId(), computed.pairs());
        }
        for (MmpComputedEndpointStats computed : computation.endpointStats()) {
            statsRepository.saveStatsRun(computed.run(), computed.transformStats());
        }
        return computation.summary();
    }
}
