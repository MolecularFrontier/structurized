package tech.molecules.structurized.ai.mcp;

import com.actelion.research.chem.Canonizer;
import com.actelion.research.chem.IDCodeParser;
import com.actelion.research.chem.IsomericSmilesCreator;
import com.actelion.research.chem.SmilesParser;
import com.actelion.research.chem.StereoMolecule;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tech.molecules.structurized.ai.model.ChemOperationException;
import tech.molecules.structurized.analytics.mmp.MmpAnalyticsHashes;
import tech.molecules.structurized.analytics.mmp.MmpEndpointPreference;
import tech.molecules.structurized.analytics.mmp.MmpEndpointStatsRun;
import tech.molecules.structurized.analytics.mmp.MmpMiningConfigSnapshot;
import tech.molecules.structurized.analytics.mmp.MmpOptimizationDirection;
import tech.molecules.structurized.analytics.mmp.MmpRecommendationCandidate;
import tech.molecules.structurized.analytics.mmp.MmpRecommendationRequest;
import tech.molecules.structurized.analytics.mmp.MmpRecommendationResult;
import tech.molecules.structurized.analytics.mmp.MmpRecommendationService;
import tech.molecules.structurized.analytics.mmp.SqliteMmpAnalyticsRepository;
import tech.molecules.structurized.mmp.MmpMiningConfig;
import tech.molecules.structurized.mmp.MmpSelectionMode;
import tech.molecules.structurized.mmp.MmpTransformStats;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Read-only MMP artifact handles and endpoint-evidence recommendation operations. */
final class MmpArtifactMcpTools {
    private static final int DEFAULT_MAX_RESULTS = 50;
    private static final int HARD_MAX_RESULTS = 200;

    private final McpToolOutputSupport output;
    private final Map<String, ArtifactHandle> handles = new LinkedHashMap<>();

    MmpArtifactMcpTools(McpToolOutputSupport output) {
        this.output = output;
    }

    synchronized Map<String, Object> open(ObjectNode args) {
        String rawPath = requiredText(args, "path");
        String label = optionalText(args, "label", null);
        try {
            Path path = Path.of(rawPath).toRealPath();
            if (!Files.isRegularFile(path)) {
                throw invalid("path must identify an existing regular file");
            }
            ArtifactIdentity identity = identity(path);
            List<MmpEndpointStatsRun> runs;
            int universeCount;
            int schemaVersion;
            boolean hasPersistedConfig;
            try (SqliteMmpAnalyticsRepository repository = SqliteMmpAnalyticsRepository.openReadOnly(path)) {
                runs = repository.listStatsRuns();
                universeCount = repository.listUniverses().size();
                schemaVersion = repository.artifactSchemaVersion();
                hasPersistedConfig = runs.stream().anyMatch(run ->
                        repository.findMiningConfig(run.mmpConfigHash()).isPresent());
            }
            String id = "mmp-" + UUID.randomUUID();
            ArtifactHandle handle = new ArtifactHandle(id, label, path, identity);
            handles.put(id, handle);
            return Map.of("artifact_id", id, "label", label == null ? path.getFileName().toString() : label,
                    "path", path.toString(), "byte_size", identity.size(), "universe_count", universeCount,
                    "run_count", runs.size(), "schema_version", schemaVersion, "persisted_mining_config", hasPersistedConfig,
                    "read_only", true);
        } catch (ChemOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new ChemOperationException("mmp_artifact_open_error",
                    "Could not open MMP artifact read-only: " + e.getMessage(), e);
        }
    }

    synchronized Map<String, Object> list() {
        List<Map<String, Object>> artifacts = handles.values().stream().map(handle -> {
            boolean unchanged;
            try {
                unchanged = handle.identity().equals(identity(handle.path()));
            } catch (Exception e) {
                unchanged = false;
            }
            return Map.<String, Object>of(
                    "artifact_id", handle.id(),
                    "label", handle.label() == null ? handle.path().getFileName().toString() : handle.label(),
                    "path", handle.path().toString(),
                    "byte_size", handle.identity().size(),
                    "unchanged", unchanged);
        }).toList();
        return Map.of("artifacts", artifacts, "count", artifacts.size());
    }

    Map<String, Object> describe(ObjectNode args) {
        ArtifactHandle handle = checked(requiredText(args, "artifact_id"));
        try (SqliteMmpAnalyticsRepository repository = SqliteMmpAnalyticsRepository.openReadOnly(handle.path())) {
            List<MmpEndpointStatsRun> runs = repository.listStatsRuns();
            List<Map<String, Object>> runRows = runs.stream().map(run -> {
                LinkedHashMap<String, Object> row = new LinkedHashMap<>();
                row.put("run_id", run.runId());
                row.put("endpoint_id", run.endpointId());
                row.put("universe_id", run.universeId());
                row.put("created_at", run.createdAt().toString());
                row.put("mmp_config_hash", run.mmpConfigHash());
                row.put("subject_count", run.subjectCount());
                row.put("value_count", run.valueCount());
                row.put("pair_count", run.pairCount());
                row.put("transform_count", run.statsCount());
                repository.findMiningConfig(run.mmpConfigHash()).ifPresent(config ->
                        row.put("mining_config", config));
                return Map.copyOf(row);
            }).toList();
            return Map.of("artifact_id", handle.id(), "path", handle.path().toString(),
                    "schema_version", repository.artifactSchemaVersion(),
                    "universes", repository.listUniverses().stream().map(universe -> Map.of(
                            "universe_id", universe.universeId(),
                            "name", universe.name(),
                            "subject_set_ids", universe.subjectSetIds(),
                            "mmp_config_hash", universe.mmpConfigHash(),
                            "created_at", universe.createdAt().toString(),
                            "metadata", universe.metadata() == null ? "" : universe.metadata())).toList(),
                    "runs", runRows,
                    "legacy_artifact", runs.stream().noneMatch(run ->
                            repository.findMiningConfig(run.mmpConfigHash()).isPresent()));
        }
    }

    Object recommend(ObjectNode args) {
        ArtifactHandle handle = checked(requiredText(args, "artifact_id"));
        int maxResults = integer(args, "max_results", DEFAULT_MAX_RESULTS);
        if (maxResults < 1 || maxResults > HARD_MAX_RESULTS) {
            throw invalid("max_results must be between 1 and " + HARD_MAX_RESULTS);
        }
        int maxAttempts = integer(args, "max_application_attempts",
                MmpRecommendationRequest.DEFAULT_MAX_APPLICATION_ATTEMPTS);
        String primaryRunId = requiredText(args, "primary_run_id");
        String detail = optionalText(args, "detail", "compact").toLowerCase(Locale.ROOT);
        if (!Set.of("compact", "full").contains(detail)) {
            throw invalid("detail must be compact or full");
        }

        try (SqliteMmpAnalyticsRepository repository = SqliteMmpAnalyticsRepository.openReadOnly(handle.path())) {
            MmpEndpointStatsRun primary = repository.findStatsRun(primaryRunId)
                    .orElseThrow(() -> invalid("unknown primary_run_id " + primaryRunId));
            MmpMiningConfig config = resolveConfig(args, repository, primary);
            ParsedInput input = parseInput(requiredText(args, "input_smiles"),
                    selectionMode(optionalText(args, "selection_mode", "all_sites")),
                    integerSet(args, "selected_atom_maps"));
            List<MmpEndpointPreference> preferences = endpointPreferences(args);
            MmpRecommendationRequest request = new MmpRecommendationRequest(
                    input.idcode(), input.canonicalSelectedAtoms(), input.selectionMode(), preferences,
                    primaryRunId, config, maxResults, maxAttempts);
            MmpRecommendationResult result = new MmpRecommendationService(repository).recommend(request);
            List<Map<String, Object>> candidates = new ArrayList<>();
            for (int index = 0; index < result.candidates().size(); index++) {
                candidates.add(candidate(index + 1, result.candidates().get(index), preferences, detail));
            }
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("artifact_id", handle.id());
            payload.put("input_smiles", requiredText(args, "input_smiles"));
            payload.put("primary_run_id", primaryRunId);
            payload.put("ranking", "primary endpoint desired mean delta, then support and transform identity");
            payload.put("evidence_type", "observed_mmp_statistics");
            payload.put("prediction_type", "none");
            payload.put("candidates", candidates);
            payload.put("diagnostics", diagnostics(result));
            Map<String, Object> summary = Map.of("artifact_id", handle.id(),
                    "candidate_count", candidates.size(), "primary_run_id", primaryRunId,
                    "truncated", result.diagnostics().truncated());
            return output.maybeFile(args, "recommend_mmp_transformations", payload, summary,
                    candidates.size());
        }
    }

    private static Map<String, Object> diagnostics(MmpRecommendationResult result) {
        var value = result.diagnostics();
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("fragmentation_count", value.fragmentationCount());
        map.put("selected_fragmentation_count", value.selectedFragmentationCount());
        map.put("primary_transform_count", value.primaryTransformCount());
        map.put("application_attempt_count", value.applicationAttemptCount());
        map.put("applied_count", value.appliedCount());
        map.put("invalid_count", value.invalidCount());
        map.put("unchanged_count", value.unchangedCount());
        map.put("duplicate_count", value.duplicateCount());
        map.put("result_count", value.resultCount());
        map.put("truncated", value.truncated());
        map.put("duration_millis", value.duration().toMillis());
        return map;
    }

    private static Map<String, Object> candidate(int rank, MmpRecommendationCandidate candidate,
                                                  List<MmpEndpointPreference> preferences, String detail) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("rank", rank);
        row.put("product_smiles", smiles(candidate.productIdcode()));
        row.put("product_idcode", candidate.productIdcode());
        row.put("transform_id", candidate.transform().transformId());
        row.put("cut_count", candidate.transform().cutCount());
        row.put("source_value_atom_indices", candidate.sourceValueAtomIndices());
        row.put("attachments", candidate.attachments());
        LinkedHashMap<String, Object> evidence = new LinkedHashMap<>();
        for (MmpEndpointPreference preference : preferences) {
            MmpTransformStats stats = candidate.statsFor(preference.runId());
            if (stats == null) continue;
            LinkedHashMap<String, Object> stat = new LinkedHashMap<>();
            stat.put("direction", preference.direction().name().toLowerCase(Locale.ROOT));
            stat.put("support_count", stats.supportCount());
            stat.put("mean_delta", stats.meanDelta());
            stat.put("desired_mean_delta", preference.direction().desiredDelta(stats.meanDelta()));
            stat.put("median_delta", stats.medianDelta());
            stat.put("standard_deviation", stats.standardDeviation());
            stat.put("min_delta", stats.minDelta());
            stat.put("max_delta", stats.maxDelta());
            stat.put("positive_fraction", stats.positiveFraction());
            if ("full".equals(detail)) stat.put("example_pairs", stats.examplePairs());
            evidence.put(preference.runId(), stat);
        }
        row.put("endpoint_evidence", evidence);
        return row;
    }

    private static MmpMiningConfig resolveConfig(ObjectNode args, SqliteMmpAnalyticsRepository repository,
                                                  MmpEndpointStatsRun primary) {
        var stored = repository.findMiningConfig(primary.mmpConfigHash());
        if (stored.isPresent()) return stored.get().toMiningConfig();

        MmpMiningConfig.Builder builder = MmpMiningConfig.defaults().toBuilder();
        boolean override = false;
        if (args.has("max_cuts")) { builder.maxCuts(integer(args, "max_cuts", 2)); override = true; }
        if (args.has("min_transform_support")) { builder.minTransformSupport(integer(args, "min_transform_support", 2)); override = true; }
        if (args.has("max_variable_heavy_atoms")) { builder.maxVariableHeavyAtoms(integer(args, "max_variable_heavy_atoms", 15)); override = true; }
        if (args.has("max_variable_to_mol_heavy_atom_fraction")) {
            builder.maxVariableToMolHeavyAtomFraction(decimal(args, "max_variable_to_mol_heavy_atom_fraction", 0.5)); override = true;
        }
        if (args.has("max_fragmentation_records_per_compound")) {
            builder.maxFragmentationRecordsPerCompound(integer(args, "max_fragmentation_records_per_compound", 500)); override = true;
        }
        if (args.has("max_pairs_per_key")) { builder.maxPairsPerKey(integer(args, "max_pairs_per_key", 200_000)); override = true; }
        MmpMiningConfig config = builder.build();
        if (!MmpAnalyticsHashes.mmpConfigHash(config).equals(primary.mmpConfigHash())) {
            throw invalid((override ? "supplied" : "default") +
                    " legacy mining configuration does not match run hash " + primary.mmpConfigHash());
        }
        return config;
    }

    private static ParsedInput parseInput(String smiles, MmpSelectionMode mode, Set<Integer> maps) {
        try {
            StereoMolecule molecule = new StereoMolecule();
            new SmilesParser().parse(molecule, smiles);
            LinkedHashMap<Integer, Integer> atomByMap = new LinkedHashMap<>();
            for (int atom = 0; atom < molecule.getAllAtoms(); atom++) {
                int map = Math.abs(molecule.getAtomMapNo(atom));
                if (map != 0 && atomByMap.put(map, atom) != null) {
                    throw invalid("input_smiles contains duplicate atom map " + map);
                }
            }
            if (mode.requiresSelection() && maps.isEmpty()) {
                throw invalid("selected_atom_maps are required for " + mode.name().toLowerCase(Locale.ROOT));
            }
            LinkedHashSet<Integer> selectedOriginal = new LinkedHashSet<>();
            for (Integer map : maps) {
                Integer atom = atomByMap.get(map);
                if (atom == null) throw invalid("selected atom map is absent from input_smiles: " + map);
                selectedOriginal.add(atom);
            }
            for (int atom = 0; atom < molecule.getAllAtoms(); atom++) molecule.setAtomMapNo(atom, 0, false);
            Canonizer canonizer = new Canonizer(molecule);
            int[] canonicalByOriginal = canonizer.getGraphIndexes();
            LinkedHashSet<Integer> selectedCanonical = new LinkedHashSet<>();
            selectedOriginal.forEach(atom -> selectedCanonical.add(canonicalByOriginal[atom]));
            return new ParsedInput(canonizer.getIDCode(), Set.copyOf(selectedCanonical), mode);
        } catch (ChemOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new ChemOperationException("invalid_arguments", "Could not parse input_smiles: " + e.getMessage(), e);
        }
    }

    private static List<MmpEndpointPreference> endpointPreferences(ObjectNode args) {
        JsonNode node = args.get("endpoint_preferences");
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw invalid("endpoint_preferences must be a non-empty array");
        }
        List<MmpEndpointPreference> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isObject()) throw invalid("endpoint_preferences entries must be objects");
            String runId = text(item, "run_id");
            String direction = text(item, "direction");
            if (runId == null || direction == null) throw invalid("endpoint preference requires run_id and direction");
            result.add(new MmpEndpointPreference(runId,
                    MmpOptimizationDirection.valueOf(direction.trim().toUpperCase(Locale.ROOT))));
        }
        return List.copyOf(result);
    }

    private synchronized ArtifactHandle checked(String id) {
        ArtifactHandle handle = handles.get(id);
        if (handle == null) throw invalid("unknown artifact_id " + id);
        try {
            if (!handle.identity().equals(identity(handle.path()))) {
                throw new ChemOperationException("mmp_artifact_changed",
                        "MMP artifact changed since it was opened; open it again before use.");
            }
            return handle;
        } catch (ChemOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new ChemOperationException("mmp_artifact_unavailable",
                    "MMP artifact is no longer available: " + e.getMessage(), e);
        }
    }

    private static ArtifactIdentity identity(Path path) throws Exception {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
        return new ArtifactIdentity(attributes.size(), attributes.lastModifiedTime().toMillis(),
                String.valueOf(attributes.fileKey()));
    }

    private static String smiles(String idcode) {
        StereoMolecule molecule = new StereoMolecule();
        new IDCodeParser().parse(molecule, idcode);
        return IsomericSmilesCreator.createSmiles(molecule);
    }

    private static MmpSelectionMode selectionMode(String value) {
        try {
            return MmpSelectionMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw invalid("selection_mode must be editable_region, exact_fragment, attachment_vicinity, or all_sites");
        }
    }

    private static String requiredText(ObjectNode args, String name) {
        String value = optionalText(args, name, null);
        if (value == null || value.isBlank()) throw invalid(name + " is required");
        return value.trim();
    }

    private static String optionalText(ObjectNode args, String name, String fallback) {
        JsonNode node = args.get(name);
        if (node == null || node.isNull()) return fallback;
        if (!node.isTextual()) throw invalid(name + " must be a string");
        return node.asText();
    }

    private static String text(JsonNode object, String name) {
        JsonNode node = object.get(name);
        return node == null || !node.isTextual() ? null : node.asText();
    }

    private static int integer(ObjectNode args, String name, int fallback) {
        JsonNode node = args.get(name);
        if (node == null || node.isNull()) return fallback;
        if (!node.isIntegralNumber()) throw invalid(name + " must be an integer");
        return node.asInt();
    }

    private static double decimal(ObjectNode args, String name, double fallback) {
        JsonNode node = args.get(name);
        if (node == null || node.isNull()) return fallback;
        if (!node.isNumber()) throw invalid(name + " must be numeric");
        return node.asDouble();
    }

    private static Set<Integer> integerSet(ObjectNode args, String name) {
        JsonNode node = args.get(name);
        if (node == null || node.isNull()) return Set.of();
        if (!node.isArray()) throw invalid(name + " must be an integer array");
        LinkedHashSet<Integer> values = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isIntegralNumber() || item.asInt() < 1) throw invalid(name + " values must be positive integers");
            if (!values.add(item.asInt())) throw invalid(name + " must not contain duplicates");
        }
        return Set.copyOf(values);
    }

    private static ChemOperationException invalid(String message) {
        return new ChemOperationException("invalid_arguments", message);
    }

    private record ArtifactHandle(String id, String label, Path path, ArtifactIdentity identity) {}
    private record ArtifactIdentity(long size, long lastModifiedMillis, String fileKey) {}
    private record ParsedInput(String idcode, Set<Integer> canonicalSelectedAtoms, MmpSelectionMode selectionMode) {}
}
