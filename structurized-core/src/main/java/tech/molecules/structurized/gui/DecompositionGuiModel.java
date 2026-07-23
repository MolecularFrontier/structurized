package tech.molecules.structurized.gui;

import com.actelion.research.chem.Canonizer;
import com.actelion.research.chem.Molecule;
import com.actelion.research.chem.SmilesParser;
import com.actelion.research.chem.StereoMolecule;
import tech.molecules.structurized.decomposition.DecompositionBoundaryBond;
import tech.molecules.structurized.decomposition.DecompositionConfig;
import tech.molecules.structurized.decomposition.DecompositionConfigValidator;
import tech.molecules.structurized.decomposition.DecompositionCutBond;
import tech.molecules.structurized.decomposition.DecompositionDatasetEvaluation;
import tech.molecules.structurized.decomposition.DecompositionDatasetEvaluator;
import tech.molecules.structurized.decomposition.DecompositionInputMolecule;
import tech.molecules.structurized.decomposition.DecompositionNode;
import tech.molecules.structurized.decomposition.DecompositionResult;
import tech.molecules.structurized.decomposition.DecompositionRule;
import tech.molecules.structurized.decomposition.RuleApplicationStatus;
import tech.molecules.structurized.decomposition.RuleAttempt;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Non-visual model helpers for the decomposition Swing viewer.
 */
final class DecompositionGuiModel {
    private DecompositionGuiModel() {}

    record ParsedMoleculeRow(
            String moleculeId,
            String smiles,
            StereoMolecule molecule,
            String parseError
    ) {
        boolean parsed() {
            return molecule != null && parseError == null;
        }
    }

    record MoleculeResultRow(
            String moleculeId,
            String smiles,
            StereoMolecule molecule,
            DecompositionResult result,
            String parseError
    ) {
        String statusText() {
            if (parseError != null) {
                return "PARSE_ERROR";
            }
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

        String rootRule() {
            return result == null || result.root() == null || result.root().appliedRuleId() == null
                    ? ""
                    : result.root().appliedRuleId();
        }

        String terminalPaths() {
            if (result == null) {
                return "";
            }
            return result.terminalNodes().stream()
                    .filter(node -> node.label() != null)
                    .map(DecompositionNode::path)
                    .sorted()
                    .collect(Collectors.joining(", "));
        }

        String problemSummary() {
            if (parseError != null) {
                return parseError;
            }
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

        int atomCount() {
            return molecule == null ? 0 : molecule.getAtoms();
        }

        List<DecompositionNode> terminalNodes() {
            return result == null ? List.of() : result.terminalNodes().stream()
                    .filter(node -> node.label() != null)
                    .sorted(Comparator.comparing(DecompositionNode::path))
                    .toList();
        }
    }

    record FragmentExample(
            String moleculeId,
            String signature,
            List<Integer> atomIndices
    ) {}

    record FragmentSummaryRow(
            String path,
            String label,
            int totalSupport,
            int distinctFragmentCount,
            int singletonCount,
            List<FragmentExample> examples
    ) {}

    record RunModel(
            DecompositionConfig config,
            List<String> validationProblems,
            DecompositionDatasetEvaluation evaluation,
            List<MoleculeResultRow> moleculeRows,
            List<FragmentSummaryRow> fragmentRows,
            int totalInputRows,
            int parseErrorCount
    ) {
        String summaryText() {
            int evaluated = evaluation == null ? 0 : evaluation.moleculeCount();
            int success = evaluation == null ? 0 : evaluation.successfulCount();
            int rootNoMatch = evaluation == null ? 0 : evaluation.rootNoMatchCount();
            int nonUnique = evaluation == null ? 0 : evaluation.nonUniqueCount();
            int invalid = evaluation == null ? 0 : evaluation.invalidCount();
            double coverage = evaluation == null ? 0.0 : evaluation.coverage();
            return """
                    Input rows: %d
                    Parsed molecules: %d
                    Parse errors: %d
                    Successful decompositions: %d
                    Coverage: %.3f
                    Root no-match: %d
                    Non-unique: %d
                    Invalid: %d
                    Config validation problems: %d
                    """.formatted(
                    totalInputRows,
                    evaluated,
                    parseErrorCount,
                    success,
                    coverage,
                    rootNoMatch,
                    nonUnique,
                    invalid,
                    validationProblems.size()
            );
        }
    }

    static RunModel evaluate(List<SmilesInputReader.SmilesRecord> records, DecompositionConfig config) {
        List<String> validationProblems = DecompositionConfigValidator.validate(config);
        List<ParsedMoleculeRow> parsedRows = new ArrayList<>();
        List<DecompositionInputMolecule> inputs = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            ParsedMoleculeRow parsed = parse(records.get(i), i);
            parsedRows.add(parsed);
            if (parsed.parsed()) {
                inputs.add(new DecompositionInputMolecule(parsed.moleculeId(), parsed.molecule()));
            }
        }

        DecompositionDatasetEvaluation evaluation = DecompositionDatasetEvaluator.evaluate(config, inputs);
        Map<String, DecompositionResult> resultById = evaluation.results().stream()
                .collect(Collectors.toMap(DecompositionResult::moleculeId, result -> result, (a, b) -> a, LinkedHashMap::new));

        List<MoleculeResultRow> moleculeRows = new ArrayList<>();
        int parseErrorCount = 0;
        for (ParsedMoleculeRow parsed : parsedRows) {
            if (!parsed.parsed()) {
                parseErrorCount++;
            }
            moleculeRows.add(new MoleculeResultRow(
                    parsed.moleculeId(),
                    parsed.smiles(),
                    parsed.molecule(),
                    resultById.get(parsed.moleculeId()),
                    parsed.parseError()
            ));
        }

        return new RunModel(
                config,
                validationProblems,
                evaluation,
                List.copyOf(moleculeRows),
                buildFragmentRows(moleculeRows),
                records.size(),
                parseErrorCount
        );
    }

    static ParsedMoleculeRow parse(SmilesInputReader.SmilesRecord record, int index) {
        String moleculeId = record.moleculeId() == null || record.moleculeId().isBlank()
                ? "mol_" + (index + 1)
                : record.moleculeId();
        try {
            StereoMolecule molecule = new StereoMolecule();
            new SmilesParser().parse(molecule, record.smiles());
            molecule.ensureHelperArrays(Molecule.cHelperRings);
            return new ParsedMoleculeRow(moleculeId, record.smiles(), molecule, null);
        } catch (Exception ex) {
            return new ParsedMoleculeRow(moleculeId, record.smiles(), null, ex.getMessage());
        }
    }

    static String detailText(MoleculeResultRow row) {
        StringBuilder sb = new StringBuilder();
        sb.append("Molecule ID: ").append(row.moleculeId()).append('\n');
        sb.append("SMILES: ").append(row.smiles()).append('\n');
        sb.append("Status: ").append(row.statusText()).append('\n');
        if (row.parseError() != null) {
            sb.append("Parse error: ").append(row.parseError()).append('\n');
            return sb.toString();
        }
        if (row.result() == null) {
            return sb.toString();
        }
        sb.append("Configuration: ").append(row.result().configurationVersion()).append('\n');
        sb.append("Terminal paths: ").append(row.terminalPaths()).append('\n');
        String problem = row.problemSummary();
        if (!problem.isBlank()) {
            sb.append("Problems: ").append(problem).append('\n');
        }
        sb.append('\n').append("Tree").append('\n');
        appendNode(sb, row.result().root(), 0);
        return sb.toString();
    }

    static String rulePreviewText(DecompositionConfig config, List<String> validationProblems) {
        StringBuilder sb = new StringBuilder();
        sb.append("Version: ").append(config.version()).append('\n');
        sb.append("Rules: ").append(config.rules().size()).append('\n');
        if (!validationProblems.isEmpty()) {
            sb.append('\n').append("Validation problems").append('\n');
            for (String problem : validationProblems) {
                sb.append("- ").append(problem).append('\n');
            }
        }
        return sb.toString();
    }

    static String examplesText(FragmentSummaryRow row) {
        StringBuilder sb = new StringBuilder();
        sb.append("Path: ").append(row.path()).append('\n');
        sb.append("Label: ").append(row.label()).append('\n');
        sb.append("Support: ").append(row.totalSupport()).append('\n');
        sb.append("Distinct fragments: ").append(row.distinctFragmentCount()).append('\n');
        sb.append("Singletons: ").append(row.singletonCount()).append('\n').append('\n');
        for (FragmentExample example : row.examples()) {
            sb.append(example.moleculeId())
                    .append(" atoms=").append(example.atomIndices())
                    .append(" signature=").append(example.signature())
                    .append('\n');
        }
        return sb.toString();
    }

    private static List<FragmentSummaryRow> buildFragmentRows(List<MoleculeResultRow> moleculeRows) {
        Map<String, MutableFragmentSummary> byPath = new LinkedHashMap<>();
        for (MoleculeResultRow row : moleculeRows) {
            if (row.molecule() == null || row.result() == null) {
                continue;
            }
            for (DecompositionNode node : row.terminalNodes()) {
                String signature = fragmentSignature(row.molecule(), node);
                MutableFragmentSummary summary = byPath.computeIfAbsent(
                        node.path(),
                        ignored -> new MutableFragmentSummary(node.path(), node.label())
                );
                summary.add(new FragmentExample(row.moleculeId(), signature, node.atomIndices()));
            }
        }
        return byPath.values().stream()
                .map(MutableFragmentSummary::toRow)
                .sorted(Comparator.comparing(FragmentSummaryRow::path))
                .toList();
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

    private static void appendNode(StringBuilder sb, DecompositionNode node, int depth) {
        String indent = "  ".repeat(depth);
        sb.append(indent)
                .append(node.path())
                .append(" label=").append(node.label())
                .append(" status=").append(node.status())
                .append(" atoms=").append(node.atomIndices());
        if (node.appliedRuleId() != null) {
            sb.append(" rule=").append(node.appliedRuleId());
        }
        sb.append('\n');

        if (!node.ruleAttempts().isEmpty()) {
            for (RuleAttempt attempt : node.ruleAttempts()) {
                sb.append(indent)
                        .append("  attempt ").append(attempt.ruleId())
                        .append(" status=").append(attempt.status())
                        .append(" matches=").append(attempt.matchCount())
                        .append(" distinct=").append(attempt.distinctAssignmentCount())
                        .append(" message=").append(attempt.message())
                        .append('\n');
            }
        }
        for (DecompositionCutBond cut : node.cutBondsProduced()) {
            sb.append(indent)
                    .append("  cut bond ").append(cut.bondIndex())
                    .append(" ").append(cut.atom1() + 1).append('-').append(cut.atom2() + 1)
                    .append(" ").append(cut.label1()).append('/').append(cut.label2())
                    .append('\n');
        }
        for (DecompositionBoundaryBond boundary : node.boundaryBonds()) {
            sb.append(indent)
                    .append("  boundary bond ").append(boundary.bondIndex())
                    .append(" in=").append(boundary.atomInFragment() + 1)
                    .append(" out=").append(boundary.atomOutsideFragment() + 1)
                    .append(" neighborLabel=").append(boundary.neighborLabel())
                    .append('\n');
        }
        for (DecompositionNode child : node.children()) {
            appendNode(sb, child, depth + 1);
        }
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
            return new FragmentSummaryRow(
                    path,
                    label,
                    examples.size(),
                    bySignature.size(),
                    singletons,
                    List.copyOf(examples)
            );
        }
    }
}
