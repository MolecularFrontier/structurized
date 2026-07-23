package tech.molecules.structurized.ai.decomposition;

import com.actelion.research.chem.Canonizer;
import com.actelion.research.chem.StereoMolecule;
import tech.molecules.structurized.ai.model.ChemOperationException;
import tech.molecules.structurized.ai.model.StructureRecord;
import tech.molecules.structurized.ai.model.StructureRef;
import tech.molecules.structurized.ai.ocl.MolecularSnapshot;
import tech.molecules.structurized.ai.repository.StoredStructure;
import tech.molecules.structurized.ai.repository.StructureRepositoryService;
import tech.molecules.structurized.decomposition.DecompositionBoundaryBond;
import tech.molecules.structurized.decomposition.DecompositionConfig;
import tech.molecules.structurized.decomposition.DecompositionConfigValidator;
import tech.molecules.structurized.decomposition.DecompositionCutBond;
import tech.molecules.structurized.decomposition.DecompositionEngine;
import tech.molecules.structurized.decomposition.DecompositionNode;
import tech.molecules.structurized.decomposition.DecompositionResult;
import tech.molecules.structurized.decomposition.RuleApplicationStatus;
import tech.molecules.structurized.decomposition.RuleAttempt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Session-scoped decomposition config and evaluation service for AI tools.
 */
public final class DecompositionAiService {
    private static final int PAGE_LIMIT_DEFAULT = 100;
    private static final int PAGE_LIMIT_MAX = 500;

    private final StructureRepositoryService repositories;
    private final Map<String, StoredConfig> configs = new LinkedHashMap<>();
    private final Map<String, StoredEvaluation> evaluations = new LinkedHashMap<>();
    private int nextConfigIndex = 1;
    private int nextEvaluationIndex = 1;

    public DecompositionAiService(StructureRepositoryService repositories) {
        this.repositories = Objects.requireNonNull(repositories, "repositories");
    }

    public synchronized DecompositionValidationResult validateConfig(DecompositionConfig config) {
        List<String> problems = DecompositionConfigValidator.validate(config);
        return new DecompositionValidationResult(
                problems.isEmpty(),
                problems,
                config == null ? null : config.version(),
                config == null || config.rules() == null ? 0 : config.rules().size()
        );
    }

    public synchronized DecompositionConfigRecord createConfig(String configId, String label, DecompositionConfig config) {
        Objects.requireNonNull(config, "config");
        String id = normalizeId(configId == null || configId.isBlank() ? generatedConfigId() : configId, "config_id");
        if (configs.containsKey(id)) {
            throw new ChemOperationException("duplicate_decomposition_config", "Decomposition config " + id + " already exists.");
        }
        StoredConfig stored = new StoredConfig(id, label == null || label.isBlank() ? id : label.trim(), config);
        configs.put(id, stored);
        return stored.toRecord();
    }

    public synchronized List<DecompositionConfigRecord> listConfigs() {
        return configs.values().stream().map(StoredConfig::toRecord).toList();
    }

    public synchronized DecompositionConfigView getConfig(String configId, boolean includeConfig) {
        StoredConfig stored = config(configId);
        return new DecompositionConfigView(stored.toRecord(), includeConfig ? stored.config() : null);
    }

    public synchronized DecompositionEvaluationRecord evaluate(
            String evaluationId,
            String configId,
            String repositoryId,
            List<String> structureIds
    ) {
        StoredConfig storedConfig = config(configId);
        String evalId = normalizeId(evaluationId == null || evaluationId.isBlank() ? generatedEvaluationId() : evaluationId, "evaluation_id");
        if (evaluations.containsKey(evalId)) {
            throw new ChemOperationException("duplicate_decomposition_evaluation", "Decomposition evaluation " + evalId + " already exists.");
        }
        List<StoredStructure> structures = resolveStructures(repositoryId, structureIds);
        DecompositionEngine engine = new DecompositionEngine(storedConfig.config());
        List<ResultEntry> results = new ArrayList<>();
        for (StoredStructure structure : structures) {
            DecompositionResult result = engine.evaluate(structure.record().structureId(), structure.snapshot().moleculeCopy());
            results.add(new ResultEntry(structure, result));
        }
        StoredEvaluation evaluation = new StoredEvaluation(evalId, storedConfig.configId(), repositoryId, List.copyOf(results));
        evaluations.put(evalId, evaluation);
        return evaluation.toRecord();
    }

    public synchronized DecompositionEvaluationView getEvaluation(String evaluationId, boolean includeResults, int offset, int limit) {
        StoredEvaluation evaluation = evaluation(evaluationId);
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.min(PAGE_LIMIT_MAX, Math.max(1, limit <= 0 ? PAGE_LIMIT_DEFAULT : limit));
        List<MoleculeResultSummary> results = includeResults
                ? page(evaluation.results().stream().map(this::summary).toList(), safeOffset, safeLimit)
                : List.of();
        return new DecompositionEvaluationView(evaluation.toRecord(), results);
    }

    public synchronized MoleculeDecompositionView getResult(String evaluationId, String structureId) {
        StoredEvaluation evaluation = evaluation(evaluationId);
        ResultEntry entry = evaluation.results().stream()
                .filter(candidate -> candidate.structure().record().structureId().equals(structureId))
                .findFirst()
                .orElseThrow(() -> new ChemOperationException("decomposition_result_not_found", "Structure " + structureId + " is not part of evaluation " + evaluationId + "."));
        return new MoleculeDecompositionView(
                entry.structure().record().repositoryId(),
                entry.structure().record().structureId(),
                entry.structure().record().label(),
                statusText(entry.result()),
                entry.result().configurationVersion(),
                entry.result().terminalNodes().stream().filter(node -> node.label() != null).map(DecompositionNode::path).sorted().toList(),
                nodeView(entry.structure().snapshot(), entry.result().root())
        );
    }

    public synchronized DecompositionFailureGroups getFailures(String evaluationId, int offset, int limit) {
        StoredEvaluation evaluation = evaluation(evaluationId);
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.min(PAGE_LIMIT_MAX, Math.max(1, limit <= 0 ? PAGE_LIMIT_DEFAULT : limit));
        Map<String, List<MoleculeResultSummary>> groups = new LinkedHashMap<>();
        for (ResultEntry entry : evaluation.results()) {
            String status = statusText(entry.result());
            if ("SUCCESS".equals(status)) {
                continue;
            }
            groups.computeIfAbsent(status, ignored -> new ArrayList<>()).add(summary(entry));
        }
        Map<String, List<MoleculeResultSummary>> paged = new LinkedHashMap<>();
        groups.forEach((status, summaries) -> paged.put(status, page(summaries, safeOffset, safeLimit)));
        return new DecompositionFailureGroups(evaluationId, paged);
    }

    public synchronized DecompositionFragmentSummaryView getFragmentSummary(String evaluationId, int offset, int limit) {
        StoredEvaluation evaluation = evaluation(evaluationId);
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.min(PAGE_LIMIT_MAX, Math.max(1, limit <= 0 ? PAGE_LIMIT_DEFAULT : limit));
        Map<String, MutableFragmentSummary> byPath = new LinkedHashMap<>();
        for (ResultEntry entry : evaluation.results()) {
            for (DecompositionNode node : entry.result().terminalNodes()) {
                if (node.label() == null) {
                    continue;
                }
                String signature = fragmentSignature(entry.structure().snapshot().moleculeView(), node);
                byPath.computeIfAbsent(node.path(), ignored -> new MutableFragmentSummary(node.path(), node.label()))
                        .add(new FragmentExample(
                                entry.structure().record().structureId(),
                                signature,
                                atomIds(entry.structure().snapshot(), node.atomIndices()),
                                node.atomIndices()
                        ));
            }
        }
        List<FragmentSummaryRow> rows = byPath.values().stream()
                .map(MutableFragmentSummary::toRow)
                .sorted(Comparator.comparing(FragmentSummaryRow::path))
                .toList();
        return new DecompositionFragmentSummaryView(evaluationId, rows.size(), page(rows, safeOffset, safeLimit));
    }

    private List<StoredStructure> resolveStructures(String repositoryId, List<String> structureIds) {
        String repoId = normalizeId(repositoryId, "repository_id");
        if (structureIds != null && !structureIds.isEmpty()) {
            return structureIds.stream()
                    .map(structureId -> repositories.getStructure(new StructureRef(repoId, structureId)))
                    .toList();
        }
        List<StoredStructure> result = new ArrayList<>();
        int offset = 0;
        while (true) {
            List<StructureRecord> page = repositories.listStructures(repoId, offset, PAGE_LIMIT_MAX);
            if (page.isEmpty()) {
                break;
            }
            for (StructureRecord record : page) {
                result.add(repositories.getStructure(record.ref()));
            }
            offset += page.size();
        }
        return List.copyOf(result);
    }

    private MoleculeResultSummary summary(ResultEntry entry) {
        DecompositionResult result = entry.result();
        StoredStructure structure = entry.structure();
        return new MoleculeResultSummary(
                structure.record().repositoryId(),
                structure.record().structureId(),
                structure.record().label(),
                statusText(result),
                result.root() == null ? null : result.root().appliedRuleId(),
                result.terminalNodes().stream().filter(node -> node.label() != null).map(DecompositionNode::path).sorted().toList(),
                problemSummary(result),
                structure.snapshot().atomCount()
        );
    }

    private NodeView nodeView(MolecularSnapshot snapshot, DecompositionNode node) {
        return new NodeView(
                node.path(),
                node.label(),
                node.status().name(),
                atomIds(snapshot, node.atomIndices()),
                node.atomIndices(),
                node.appliedRuleId(),
                node.ruleHistory(),
                node.ruleAttempts(),
                node.cutBondsProduced().stream().map(cut -> cutBondView(snapshot, cut)).toList(),
                node.boundaryBonds().stream().map(boundary -> boundaryBondView(snapshot, boundary)).toList(),
                node.children().stream().map(child -> nodeView(snapshot, child)).toList()
        );
    }

    private static CutBondView cutBondView(MolecularSnapshot snapshot, DecompositionCutBond cut) {
        return new CutBondView(
                snapshot.bondId(cut.bondIndex()),
                cut.bondIndex(),
                snapshot.atomId(cut.atom1()),
                snapshot.atomId(cut.atom2()),
                cut.atom1(),
                cut.atom2(),
                cut.bondType(),
                cut.label1(),
                cut.label2()
        );
    }

    private static BoundaryBondView boundaryBondView(MolecularSnapshot snapshot, DecompositionBoundaryBond boundary) {
        return new BoundaryBondView(
                snapshot.bondId(boundary.bondIndex()),
                boundary.bondIndex(),
                snapshot.atomId(boundary.atomInFragment()),
                snapshot.atomId(boundary.atomOutsideFragment()),
                boundary.atomInFragment(),
                boundary.atomOutsideFragment(),
                boundary.bondType(),
                boundary.neighborLabel()
        );
    }

    private static String statusText(DecompositionResult result) {
        if (result == null || result.root() == null) {
            return "NOT_EVALUATED";
        }
        if (result.successful()) {
            return "SUCCESS";
        }
        if (containsStatus(result.root(), RuleApplicationStatus.INVALID_RULE_OR_ASSIGNMENT)) {
            return "INVALID";
        }
        if (containsStatus(result.root(), RuleApplicationStatus.MATCHED_NON_UNIQUE)) {
            return "NON_UNIQUE";
        }
        if (isNoMatchRoot(result.root())) {
            return "NO_MATCH";
        }
        return result.root().status().name();
    }

    private static String problemSummary(DecompositionResult result) {
        if (result == null || result.root() == null) {
            return "";
        }
        List<String> problems = new ArrayList<>();
        collectProblems(result.root(), problems);
        if (problems.isEmpty() && isNoMatchRoot(result.root())) {
            return "root rule did not match";
        }
        return String.join("; ", problems);
    }

    private static void collectProblems(DecompositionNode node, List<String> problems) {
        if (node.status() == RuleApplicationStatus.INVALID_RULE_OR_ASSIGNMENT
                || node.status() == RuleApplicationStatus.MATCHED_NON_UNIQUE) {
            String message = node.ruleAttempts().isEmpty() ? node.status().name() : node.ruleAttempts().getLast().message();
            problems.add(node.path() + ": " + message);
        }
        for (DecompositionNode child : node.children()) {
            collectProblems(child, problems);
        }
    }

    private static boolean containsStatus(DecompositionNode node, RuleApplicationStatus status) {
        if (node.status() == status) {
            return true;
        }
        return node.children().stream().anyMatch(child -> containsStatus(child, status));
    }

    private static boolean isNoMatchRoot(DecompositionNode root) {
        return root.status() == RuleApplicationStatus.SKIPPED
                && !root.ruleAttempts().isEmpty()
                && root.ruleAttempts().stream().allMatch(attempt -> attempt.status() == RuleApplicationStatus.NO_MATCH);
    }

    private static List<String> atomIds(MolecularSnapshot snapshot, List<Integer> atomIndices) {
        return atomIndices.stream().map(snapshot::atomId).toList();
    }

    private static String fragmentSignature(StereoMolecule molecule, DecompositionNode node) {
        boolean[] include = new boolean[molecule.getAtoms()];
        for (int atom : node.atomIndices()) {
            include[atom] = true;
        }
        StereoMolecule fragment = new StereoMolecule();
        molecule.copyMoleculeByAtoms(fragment, include, true, null);
        return new Canonizer(fragment, Canonizer.ENCODE_ATOM_CUSTOM_LABELS).getIDCode();
    }

    private StoredConfig config(String configId) {
        StoredConfig config = configs.get(normalizeId(configId, "config_id"));
        if (config == null) {
            throw new ChemOperationException("decomposition_config_not_found", "Decomposition config " + configId + " does not exist.");
        }
        return config;
    }

    private StoredEvaluation evaluation(String evaluationId) {
        StoredEvaluation evaluation = evaluations.get(normalizeId(evaluationId, "evaluation_id"));
        if (evaluation == null) {
            throw new ChemOperationException("decomposition_evaluation_not_found", "Decomposition evaluation " + evaluationId + " does not exist.");
        }
        return evaluation;
    }

    private String generatedConfigId() {
        String id;
        do {
            id = "decomp_config_" + nextConfigIndex++;
        } while (configs.containsKey(id));
        return id;
    }

    private String generatedEvaluationId() {
        String id;
        do {
            id = "decomp_eval_" + nextEvaluationIndex++;
        } while (evaluations.containsKey(id));
        return id;
    }

    private static String normalizeId(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ChemOperationException("invalid_arguments", "Missing required argument: " + name);
        }
        return value.trim();
    }

    private static <T> List<T> page(List<T> values, int offset, int limit) {
        int from = Math.min(Math.max(0, offset), values.size());
        int to = Math.min(from + Math.max(1, limit), values.size());
        return List.copyOf(values.subList(from, to));
    }

    private record StoredConfig(String configId, String label, DecompositionConfig config) {
        private DecompositionConfigRecord toRecord() {
            List<String> problems = DecompositionConfigValidator.validate(config);
            return new DecompositionConfigRecord(configId, label, config.version(), config.rules().size(), problems);
        }
    }

    private record StoredEvaluation(String evaluationId, String configId, String repositoryId, List<ResultEntry> results) {
        private DecompositionEvaluationRecord toRecord() {
            int success = 0;
            int noMatch = 0;
            int nonUnique = 0;
            int invalid = 0;
            for (ResultEntry entry : results) {
                String status = statusText(entry.result());
                switch (status) {
                    case "SUCCESS" -> success++;
                    case "NO_MATCH" -> noMatch++;
                    case "NON_UNIQUE" -> nonUnique++;
                    case "INVALID" -> invalid++;
                    default -> {}
                }
            }
            return new DecompositionEvaluationRecord(
                    evaluationId,
                    configId,
                    repositoryId,
                    results.size(),
                    success,
                    results.isEmpty() ? 0.0 : (double) success / results.size(),
                    noMatch,
                    nonUnique,
                    invalid
            );
        }
    }

    private record ResultEntry(StoredStructure structure, DecompositionResult result) {}

    private static final class MutableFragmentSummary {
        private final String path;
        private final String label;
        private final List<FragmentExample> examples = new ArrayList<>();

        private MutableFragmentSummary(String path, String label) {
            this.path = path;
            this.label = label;
        }

        private void add(FragmentExample example) {
            examples.add(example);
        }

        private FragmentSummaryRow toRow() {
            Map<String, Long> bySignature = examples.stream()
                    .collect(Collectors.groupingBy(FragmentExample::signature, LinkedHashMap::new, Collectors.counting()));
            int singletons = (int) bySignature.values().stream().filter(count -> count == 1).count();
            return new FragmentSummaryRow(path, label, examples.size(), bySignature.size(), singletons, List.copyOf(examples));
        }
    }

    public record DecompositionValidationResult(boolean valid, List<String> problems, String version, int ruleCount) {}

    public record DecompositionConfigRecord(String configId, String label, String version, int ruleCount, List<String> validationProblems) {}

    public record DecompositionConfigView(DecompositionConfigRecord record, DecompositionConfig config) {}

    public record DecompositionEvaluationRecord(
            String evaluationId,
            String configId,
            String repositoryId,
            int moleculeCount,
            int successfulCount,
            double coverage,
            int rootNoMatchCount,
            int nonUniqueCount,
            int invalidCount
    ) {}

    public record DecompositionEvaluationView(DecompositionEvaluationRecord summary, List<MoleculeResultSummary> results) {}

    public record MoleculeResultSummary(
            String repositoryId,
            String structureId,
            String label,
            String status,
            String rootRule,
            List<String> terminalPaths,
            String problemSummary,
            int atomCount
    ) {}

    public record MoleculeDecompositionView(
            String repositoryId,
            String structureId,
            String label,
            String status,
            String configurationVersion,
            List<String> terminalPaths,
            NodeView root
    ) {}

    public record NodeView(
            String path,
            String label,
            String status,
            List<String> atomIds,
            List<Integer> atomIndices,
            String appliedRuleId,
            List<String> ruleHistory,
            List<RuleAttempt> ruleAttempts,
            List<CutBondView> cutBonds,
            List<BoundaryBondView> boundaryBonds,
            List<NodeView> children
    ) {}

    public record CutBondView(
            String bondId,
            int bondIndex,
            String atom1Id,
            String atom2Id,
            int atom1Index,
            int atom2Index,
            int bondType,
            String label1,
            String label2
    ) {}

    public record BoundaryBondView(
            String bondId,
            int bondIndex,
            String atomInFragmentId,
            String atomOutsideFragmentId,
            int atomInFragmentIndex,
            int atomOutsideFragmentIndex,
            int bondType,
            String neighborLabel
    ) {}

    public record DecompositionFailureGroups(String evaluationId, Map<String, List<MoleculeResultSummary>> groups) {}

    public record DecompositionFragmentSummaryView(String evaluationId, int totalRows, List<FragmentSummaryRow> rows) {}

    public record FragmentSummaryRow(
            String path,
            String label,
            int totalSupport,
            int distinctFragmentCount,
            int singletonCount,
            List<FragmentExample> examples
    ) {}

    public record FragmentExample(String structureId, String signature, List<String> atomIds, List<Integer> atomIndices) {}
}
