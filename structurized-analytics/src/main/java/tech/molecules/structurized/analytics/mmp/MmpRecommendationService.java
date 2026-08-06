package tech.molecules.structurized.analytics.mmp;

import com.actelion.research.chem.Canonizer;
import com.actelion.research.chem.IDCodeParser;
import com.actelion.research.chem.Molecule;
import com.actelion.research.chem.StereoMolecule;
import tech.molecules.structurized.mmp.MmpFragmentationMatch;
import tech.molecules.structurized.mmp.MmpFragmenter;
import tech.molecules.structurized.mmp.MmpInputCompound;
import tech.molecules.structurized.mmp.MmpTransformApplicationAttempt;
import tech.molecules.structurized.mmp.MmpTransformApplicator;
import tech.molecules.structurized.mmp.MmpTransformDefinition;
import tech.molecules.structurized.mmp.MmpTransformStats;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Matches endpoint-backed transformations to mapped query fragments and generates products. */
public final class MmpRecommendationService {
    private final MmpEndpointStatsRepository repository;

    public MmpRecommendationService(MmpEndpointStatsRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public MmpRecommendationResult recommend(MmpRecommendationRequest request) {
        Objects.requireNonNull(request, "request");
        long started = System.nanoTime();
        validateRuns(request);
        StereoMolecule input = parse(request.inputIdcode());
        validateSelectedAtoms(input, request.selectedAtomIndices());
        String canonicalInput = new Canonizer(input).getIDCode();

        List<MmpFragmentationMatch> allMatches = MmpFragmenter.fragmentWithMapping(
                new MmpInputCompound("query", input, null), request.miningConfig());
        List<MmpFragmentationMatch> selectedMatches = allMatches.stream()
                .filter(match -> request.selectionMode().accepts(
                        match, request.selectedAtomIndices()))
                .toList();

        ArrayList<ApplicationTask> tasks = primaryTasks(request, selectedMatches);
        tasks.sort(taskComparator(request.primaryPreference().direction()));
        int primaryTransformCount = (int) tasks.stream()
                .map(task -> task.stats().transformId())
                .distinct()
                .count();

        LinkedHashMap<ApplicationKey, PreliminaryCandidate> candidates = new LinkedHashMap<>();
        int attempts = 0;
        int applied = 0;
        int invalid = 0;
        int unchanged = 0;
        int duplicates = 0;
        int taskIndex = 0;
        while (taskIndex < tasks.size()
                && attempts < request.maxApplicationAttempts()
                && candidates.size() < request.maxResults()) {
            ApplicationTask task = tasks.get(taskIndex++);
            attempts++;
            MmpTransformDefinition transform = MmpTransformDefinition.from(task.stats());
            MmpTransformApplicationAttempt attempt =
                    MmpTransformApplicator.apply(task.match(), transform);
            if (!attempt.isApplied()) {
                invalid++;
                continue;
            }
            applied++;
            if (canonicalInput.equals(attempt.application().productIdcode())) {
                unchanged++;
                continue;
            }
            ApplicationKey key = new ApplicationKey(
                    attempt.application().productIdcode(),
                    transform.transformId(),
                    attempt.application().attachments().stream()
                            .map(attachment -> attachment.cutBondIndex())
                            .sorted()
                            .toList());
            PreliminaryCandidate previous = candidates.putIfAbsent(key, new PreliminaryCandidate(
                    attempt.application().productIdcode(),
                    transform,
                    attempt.application().attachments(),
                    task.match().valueAtomIndices(),
                    task.stats()));
            if (previous != null) duplicates++;
        }
        boolean truncated = taskIndex < tasks.size();

        Set<String> candidateTransformIds = candidates.values().stream()
                .map(candidate -> candidate.transform().transformId())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<String, Map<String, MmpTransformStats>> secondaryStats =
                loadSecondaryStats(request, candidateTransformIds);
        ArrayList<MmpRecommendationCandidate> results = new ArrayList<>();
        for (PreliminaryCandidate candidate : candidates.values()) {
            LinkedHashMap<String, MmpTransformStats> statsByRun = new LinkedHashMap<>();
            for (MmpEndpointPreference preference : request.endpointPreferences()) {
                MmpTransformStats stats = preference.runId().equals(request.primaryRunId())
                        ? candidate.primaryStats()
                        : secondaryStats.getOrDefault(preference.runId(), Map.of())
                                .get(candidate.transform().transformId());
                if (stats != null) statsByRun.put(preference.runId(), stats);
            }
            results.add(new MmpRecommendationCandidate(
                    candidate.productIdcode(), candidate.transform(), candidate.attachments(),
                    candidate.sourceValueAtomIndices(), statsByRun));
        }

        Duration duration = Duration.ofNanos(Math.max(0L, System.nanoTime() - started));
        return new MmpRecommendationResult(results, new MmpRecommendationDiagnostics(
                allMatches.size(), selectedMatches.size(), primaryTransformCount,
                attempts, applied, invalid, unchanged, duplicates, results.size(),
                truncated, duration));
    }

    private Map<String, MmpEndpointStatsRun> validateRuns(MmpRecommendationRequest request) {
        LinkedHashMap<String, MmpEndpointStatsRun> runs = new LinkedHashMap<>();
        for (MmpEndpointPreference preference : request.endpointPreferences()) {
            MmpEndpointStatsRun run = repository.findStatsRun(preference.runId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "unknown endpoint stats run " + preference.runId()));
            runs.put(run.runId(), run);
        }
        MmpEndpointStatsRun primary = runs.get(request.primaryRunId());
        for (MmpEndpointStatsRun run : runs.values()) {
            if (!primary.universeId().equals(run.universeId())) {
                throw new IllegalArgumentException(
                        "selected endpoint runs must use the same MMP universe");
            }
            if (!primary.mmpConfigHash().equals(run.mmpConfigHash())) {
                throw new IllegalArgumentException(
                        "selected endpoint runs must use the same MMP mining configuration");
            }
        }
        return Map.copyOf(runs);
    }

    private ArrayList<ApplicationTask> primaryTasks(
            MmpRecommendationRequest request,
            List<MmpFragmentationMatch> matches
    ) {
        ArrayList<ApplicationTask> tasks = new ArrayList<>();
        for (int cuts = 1; cuts <= request.miningConfig().maxCuts(); cuts++) {
            int cutCount = cuts;
            List<MmpFragmentationMatch> cutMatches = matches.stream()
                    .filter(match -> match.record().cutCount() == cutCount)
                    .toList();
            Set<String> sources = cutMatches.stream()
                    .map(match -> match.record().valueIdcode())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (sources.isEmpty()) continue;
            List<MmpTransformStats> stats = repository.findTransformStatsBySourceFragments(
                    request.primaryRunId(), cutCount, sources);
            Map<String, List<MmpTransformStats>> bySource = stats.stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            MmpTransformStats::fromValueIdcode,
                            LinkedHashMap::new,
                            java.util.stream.Collectors.toList()));
            for (MmpFragmentationMatch match : cutMatches) {
                for (MmpTransformStats stat :
                        bySource.getOrDefault(match.record().valueIdcode(), List.of())) {
                    tasks.add(new ApplicationTask(match, stat));
                }
            }
        }
        return tasks;
    }

    private Map<String, Map<String, MmpTransformStats>> loadSecondaryStats(
            MmpRecommendationRequest request,
            Set<String> transformIds
    ) {
        LinkedHashMap<String, Map<String, MmpTransformStats>> byRun = new LinkedHashMap<>();
        for (MmpEndpointPreference preference : request.endpointPreferences()) {
            if (preference.runId().equals(request.primaryRunId())) continue;
            Map<String, MmpTransformStats> stats = repository
                    .findTransformStatsByIds(preference.runId(), transformIds)
                    .stream()
                    .collect(java.util.stream.Collectors.toMap(
                            MmpTransformStats::transformId,
                            stat -> stat,
                            (first, ignored) -> first,
                            LinkedHashMap::new));
            byRun.put(preference.runId(), Map.copyOf(stats));
        }
        return Map.copyOf(byRun);
    }

    private static Comparator<ApplicationTask> taskComparator(
            MmpOptimizationDirection direction
    ) {
        Comparator<ApplicationTask> evidence;
        if (direction == MmpOptimizationDirection.NEUTRAL) {
            evidence = Comparator
                    .comparingInt((ApplicationTask task) -> task.stats().supportCount()).reversed()
                    .thenComparing(
                            Comparator.comparingDouble(
                                    (ApplicationTask task) -> Math.abs(task.stats().meanDelta()))
                                    .reversed());
        } else {
            evidence = Comparator
                    .comparingDouble((ApplicationTask task) ->
                            direction.desiredDelta(task.stats().meanDelta())).reversed()
                    .thenComparing(
                            Comparator.comparingInt(
                                    (ApplicationTask task) -> task.stats().supportCount()).reversed());
        }
        return evidence
                .thenComparing(task -> task.stats().transformId())
                .thenComparing(task -> task.match().attachments().stream()
                        .map(attachment -> Integer.toString(attachment.cutBondIndex()))
                        .collect(java.util.stream.Collectors.joining(",")));
    }

    private static StereoMolecule parse(String idcode) {
        try {
            StereoMolecule molecule = new StereoMolecule();
            new IDCodeParser().parse(molecule, idcode);
            molecule.ensureHelperArrays(Molecule.cHelperRings);
            if (molecule.getAllAtoms() == 0) {
                throw new IllegalArgumentException("input structure is empty");
            }
            return molecule;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("invalid input IDCode", exception);
        }
    }

    private static void validateSelectedAtoms(
            StereoMolecule molecule,
            Set<Integer> selectedAtoms
    ) {
        for (Integer atom : selectedAtoms) {
            if (atom >= molecule.getAllAtoms()) {
                throw new IllegalArgumentException(
                        "selected atom index " + atom + " is outside the input structure");
            }
        }
    }

    private record ApplicationTask(
            MmpFragmentationMatch match,
            MmpTransformStats stats
    ) {
    }

    private record ApplicationKey(
            String productIdcode,
            String transformId,
            List<Integer> cutBondIndices
    ) {
    }

    private record PreliminaryCandidate(
            String productIdcode,
            MmpTransformDefinition transform,
            List<tech.molecules.structurized.mmp.MmpAttachment> attachments,
            List<Integer> sourceValueAtomIndices,
            MmpTransformStats primaryStats
    ) {
    }
}
