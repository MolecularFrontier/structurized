package tech.molecules.structurized.analytics.mmp;

import com.actelion.research.chem.StereoMolecule;
import tech.molecules.structurized.mmp.MmpMiningConfig;
import tech.molecules.structurized.prism.model.EndpointDataType;
import tech.molecules.structurized.prism.model.EndpointDefinition;
import tech.molecules.structurized.prism.provider.EndpointProvider;
import tech.molecules.structurized.prism.provider.SubjectSet;
import tech.molecules.structurized.prism.provider.SubjectSetProvider;
import tech.molecules.structurized.prism.query.EndpointFetchRequest;
import tech.molecules.structurized.prism.query.EndpointValueRecord;
import tech.molecules.structurized.prism.result.NumericResult;
import tech.molecules.structurized.prism.result.NumericState;
import tech.molecules.structurized.prism.result.OptionalNumericResult;
import tech.molecules.structurized.prism.result.OptionalNumericState;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Compatibility facade that materializes PRISM providers into one immutable snapshot, computes
 * the complete result in memory, and only then persists it.
 */
public final class MmpEndpointStatsComputationService {
    private final EndpointProvider endpointProvider;
    private final SubjectSetProvider subjectSetProvider;
    private final StructureProvider structureProvider;
    private final MmpEndpointStatsCalculator calculator;
    private final MmpAnalyticsPersistenceService persistenceService;

    public MmpEndpointStatsComputationService(
            EndpointProvider endpointProvider,
            SubjectSetProvider subjectSetProvider,
            StructureProvider structureProvider,
            MmpUniverseRepository universeRepository,
            MmpPairRepository pairRepository,
            MmpEndpointStatsRepository statsRepository
    ) {
        this(endpointProvider, subjectSetProvider, structureProvider,
                universeRepository, pairRepository, statsRepository, Clock.systemUTC());
    }

    public MmpEndpointStatsComputationService(
            EndpointProvider endpointProvider,
            SubjectSetProvider subjectSetProvider,
            StructureProvider structureProvider,
            MmpUniverseRepository universeRepository,
            MmpPairRepository pairRepository,
            MmpEndpointStatsRepository statsRepository,
            Clock clock
    ) {
        this.endpointProvider = Objects.requireNonNull(endpointProvider, "endpointProvider");
        this.subjectSetProvider = Objects.requireNonNull(subjectSetProvider, "subjectSetProvider");
        this.structureProvider = Objects.requireNonNull(structureProvider, "structureProvider");
        this.calculator = new MmpEndpointStatsCalculator(clock);
        this.persistenceService = new MmpAnalyticsPersistenceService(
                universeRepository, pairRepository, statsRepository);
    }

    public MmpEndpointStatsComputationResult computeAndPersist(
            MmpEndpointStatsConfig statsConfig,
            MmpMiningConfig mmpConfig
    ) {
        MmpAnalyticsSnapshot snapshot = loadSnapshot(statsConfig);
        MmpAnalyticsComputation computation = calculator.compute(snapshot, statsConfig, mmpConfig);
        return persistenceService.persist(computation);
    }

    /**
     * Loads all structures and numeric endpoint values needed by the configured computation.
     * The returned snapshot can be reused for multiple pure calculations.
     */
    public MmpAnalyticsSnapshot loadSnapshot(MmpEndpointStatsConfig statsConfig) {
        Objects.requireNonNull(statsConfig, "statsConfig");
        List<EndpointDefinition> endpoints = selectNumericEndpoints(statsConfig);
        Map<String, EndpointSubjectSet> endpointSets = resolveEndpointSubjectSets(endpoints, statsConfig);

        LinkedHashSet<String> allSubjects = new LinkedHashSet<>();
        for (EndpointDefinition endpoint : endpoints) {
            allSubjects.addAll(endpointSets.get(endpoint.getId()).subjectIds());
        }
        Map<String, StereoMolecule> structures = structureProvider.fetchStructures(allSubjects);

        ArrayList<MmpEndpointSnapshot> endpointSnapshots = new ArrayList<>();
        for (EndpointDefinition endpoint : endpoints) {
            EndpointSubjectSet endpointSet = endpointSets.get(endpoint.getId());
            Map<String, Double> values = fetchEndpointValues(
                    endpoint.getId(), endpointSet.subjectIds(), statsConfig.batchSize());
            endpointSnapshots.add(new MmpEndpointSnapshot(
                    endpoint.getId(),
                    endpoint.getName(),
                    endpointSet.subjectSetId(),
                    endpointSet.subjectIds(),
                    values
            ));
        }
        return new MmpAnalyticsSnapshot("prism-endpoint-providers", structures, endpointSnapshots);
    }

    private List<EndpointDefinition> selectNumericEndpoints(MmpEndpointStatsConfig config) {
        LinkedHashMap<String, EndpointDefinition> byId = new LinkedHashMap<>();
        for (EndpointDefinition endpoint : endpointProvider.listEndpointDefinitions()) {
            if (isNumericEndpoint(endpoint)) {
                byId.put(endpoint.getId(), endpoint);
            }
        }
        if (config.endpointIds().isEmpty()) {
            return byId.values().stream()
                    .sorted(Comparator.comparing(EndpointDefinition::getId))
                    .toList();
        }

        LinkedHashSet<String> selectedIds = new LinkedHashSet<>();
        ArrayList<EndpointDefinition> selected = new ArrayList<>();
        for (String endpointId : config.endpointIds()) {
            if (!selectedIds.add(endpointId)) {
                throw new IllegalArgumentException("duplicate requested endpoint ID '" + endpointId + "'");
            }
            EndpointDefinition endpoint = byId.get(endpointId);
            if (endpoint == null) {
                throw new IllegalArgumentException("unknown or non-numeric endpoint '" + endpointId + "'");
            }
            selected.add(endpoint);
        }
        return List.copyOf(selected);
    }

    private static boolean isNumericEndpoint(EndpointDefinition endpoint) {
        return endpoint.getDatatype() == EndpointDataType.NUMERIC
                || endpoint.getDatatype() == EndpointDataType.OPTIONAL_NUMERIC;
    }

    private Map<String, EndpointSubjectSet> resolveEndpointSubjectSets(
            List<EndpointDefinition> endpoints,
            MmpEndpointStatsConfig config
    ) {
        List<SubjectSet> allSets = subjectSetProvider.listSubjectSets();
        LinkedHashMap<String, EndpointSubjectSet> result = new LinkedHashMap<>();
        for (EndpointDefinition endpoint : endpoints) {
            String subjectSetId = config.endpointSubjectSetIds().get(endpoint.getId());
            if (subjectSetId == null) {
                subjectSetId = inferSubjectSetId(endpoint, allSets, config)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "no measured subject set configured or inferred for endpoint '"
                                        + endpoint.getId() + "'"));
            }
            result.put(endpoint.getId(), new EndpointSubjectSet(
                    subjectSetId, fetchAllSubjects(subjectSetId, config.batchSize())));
        }
        return Map.copyOf(result);
    }

    private Optional<String> inferSubjectSetId(
            EndpointDefinition endpoint,
            List<SubjectSet> allSets,
            MmpEndpointStatsConfig config
    ) {
        List<String> candidates = List.of(
                endpoint.getId(),
                "assay:" + endpoint.getId() + ":measured",
                "assay-measured:" + endpoint.getId(),
                "endpoint:" + endpoint.getId() + ":measured",
                endpoint.getPath(),
                "assay:" + endpoint.getPath() + ":measured"
        );
        for (String candidate : candidates) {
            for (SubjectSet subjectSet : allSets) {
                if (candidate.equals(subjectSet.getId())) {
                    return Optional.of(candidate);
                }
            }
        }

        return allSets.stream()
                .filter(set -> config.measuredSubjectSetType() == null
                        || config.measuredSubjectSetType().equals(set.getSetType()))
                .filter(set -> config.measuredSubjectSetScope() == null
                        || config.measuredSubjectSetScope().equals(set.getSubjectSetScope()))
                .filter(set -> set.getId().contains(endpoint.getId())
                        || set.getName().contains(endpoint.getName()))
                .map(SubjectSet::getId)
                .findFirst();
    }

    private List<String> fetchAllSubjects(String subjectSetId, int batchSize) {
        long count = subjectSetProvider.countSubjects(subjectSetId);
        ArrayList<String> subjects = new ArrayList<>();
        for (int offset = 0; offset < count; offset += batchSize) {
            subjects.addAll(subjectSetProvider.listSubjects(subjectSetId, offset, batchSize));
        }
        return subjects.stream().distinct().toList();
    }

    private Map<String, Double> fetchEndpointValues(
            String endpointId,
            List<String> subjectIds,
            int batchSize
    ) {
        Set<String> allowedSubjects = Set.copyOf(subjectIds);
        LinkedHashSet<String> seenRecords = new LinkedHashSet<>();
        LinkedHashMap<String, Double> values = new LinkedHashMap<>();
        for (int offset = 0; offset < subjectIds.size(); offset += batchSize) {
            List<String> batch = subjectIds.subList(offset, Math.min(offset + batchSize, subjectIds.size()));
            EndpointFetchRequest request = EndpointFetchRequest.builder()
                    .subjectIds(batch)
                    .endpointIds(List.of(endpointId))
                    .build();
            for (EndpointValueRecord record : endpointProvider.fetchEndpointValues(request)) {
                if (!endpointId.equals(record.getEndpointId())) {
                    throw new IllegalArgumentException("provider returned endpoint '" + record.getEndpointId()
                            + "' while loading endpoint '" + endpointId + "'");
                }
                if (!allowedSubjects.contains(record.getSubjectId())) {
                    throw new IllegalArgumentException("provider returned subject '" + record.getSubjectId()
                            + "' outside subject set for endpoint '" + endpointId + "'");
                }
                if (!seenRecords.add(record.getSubjectId())) {
                    throw new IllegalArgumentException("provider returned duplicate value record for subject '"
                            + record.getSubjectId() + "' and endpoint '" + endpointId + "'");
                }
                extractNumericValue(record).ifPresent(value -> values.put(record.getSubjectId(), value));
            }
        }
        return Map.copyOf(values);
    }

    private static Optional<Double> extractNumericValue(EndpointValueRecord record) {
        Double value = null;
        if (record.getResult() instanceof NumericResult numeric
                && numeric.getState() == NumericState.VALUE
                && numeric.getMean() != null) {
            value = numeric.getMean();
        } else if (record.getResult() instanceof OptionalNumericResult optional
                && optional.getState() == OptionalNumericState.VALUE
                && optional.getMean() != null) {
            value = optional.getMean();
        }
        return value != null && Double.isFinite(value) ? Optional.of(value) : Optional.empty();
    }

    private record EndpointSubjectSet(String subjectSetId, List<String> subjectIds) {
        private EndpointSubjectSet {
            subjectIds = List.copyOf(subjectIds);
        }
    }
}
