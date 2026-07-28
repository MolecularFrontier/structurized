package tech.molecules.structurized.ai.mcp;

import com.actelion.research.chem.Canonizer;
import com.actelion.research.chem.IDCodeParser;
import com.actelion.research.chem.IsomericSmilesCreator;
import com.actelion.research.chem.SmilesParser;
import com.actelion.research.chem.StereoMolecule;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tech.molecules.structurized.ai.model.ChemOperationException;
import tech.molecules.structurized.ai.model.StructureRecord;
import tech.molecules.structurized.ai.model.StructureRef;
import tech.molecules.structurized.ai.prism.PrismBridgeService;
import tech.molecules.structurized.ai.prism.PrismRowSetStructureCollection;
import tech.molecules.structurized.ai.prism.PrismRowStructureEntry;
import tech.molecules.structurized.ai.repository.StructureRepositoryService;
import tech.molecules.structurized.transforms.OclStrictMcsProvider;
import tech.molecules.structurized.transforms.TransformationGroup;
import tech.molecules.structurized.transforms.TransformationSignature;
import tech.molecules.structurized.transforms.TransformationSplitter;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;

final class StructureComparisonMcpTool {
    private final StructureRepositoryService repositories;
    private final PrismBridgeService prism;

    StructureComparisonMcpTool(StructureRepositoryService repositories, PrismBridgeService prism) {
        this.repositories = Objects.requireNonNull(repositories, "repositories");
        this.prism = Objects.requireNonNull(prism, "prism");
    }

    Object compareStructures(ObjectNode args) {
        String outputMode = normalizeCompareOutputMode(optionalString(args, "output_mode", "summary"));
        boolean includeIdcodes = "full".equals(outputMode) || optionalBoolean(args, "include_idcodes", false);
        boolean includeAtomMappings = optionalBoolean(args, "include_atom_mappings", false);
        int contextRadius = Math.max(0, optionalInt(args, "context_radius", 1));
        StructureComparisonInput left = comparisonInput(args, "left");
        StructureComparisonInput right = comparisonInput(args, "right");

        TransformationSplitter.MCSMap mcs;
        try {
            mcs = new OclStrictMcsProvider().computeStrictMCS(left.molecule(), right.molecule());
        } catch (RuntimeException exception) {
            throw new ChemOperationException("structure_comparison_failed", "Could not compute strict MCS: " + exception.getMessage(), exception);
        }
        if (mcs == null) {
            return new StructureComparisonSummary(
                    "NO_MCS",
                    left.id(),
                    right.id(),
                    left.atomCount(),
                    right.atomCount(),
                    0,
                    left.atomCount(),
                    right.atomCount(),
                    0,
                    List.of(),
                    0,
                    "NO_MCS: no strict shared core found");
        }

        List<TransformationGroup> groups = TransformationSplitter.splitIntoTransformations(
                left.molecule(),
                right.molecule(),
                mcs,
                contextRadius,
                TransformationSplitter.FeatureMask.DEFAULT);
        int coreAtomCount = coreAtomCount(mcs);
        List<String> changeTypes = groups.stream().map(group -> group.type.name()).distinct().toList();
        int extensionPointCount = groups.stream()
                .flatMap(group -> group.attachmentsA.stream())
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new))
                .size();
        String status = coreAtomCount == left.atomCount() && coreAtomCount == right.atomCount() && groups.isEmpty()
                ? "NO_CHANGE"
                : "SUCCESS";
        StructureComparisonSummary summary = new StructureComparisonSummary(
                status,
                left.id(),
                right.id(),
                left.atomCount(),
                right.atomCount(),
                coreAtomCount,
                left.atomCount() - coreAtomCount,
                right.atomCount() - coreAtomCount,
                groups.size(),
                changeTypes,
                extensionPointCount,
                comparisonSummaryText(status, coreAtomCount, groups.size(), changeTypes, extensionPointCount));
        if ("summary".equals(outputMode)) {
            return summary;
        }

        String sharedCoreIdcode = coreIdcode(left.molecule(), mcs);
        List<StructureComparisonChangeGroup> groupViews = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            groupViews.add(changeGroupView(i, groups.get(i), mcs, left.molecule(), right.molecule(), includeIdcodes));
        }
        List<StructureComparisonExtensionPoint> extensionPoints = extensionPoints(groups, mcs, left.molecule(), right.molecule());
        List<StructureAtomMapping> mappings = includeAtomMappings ? atomMappings(mcs) : null;
        return new StructureComparisonDetail(
                summary,
                renderIdcode(sharedCoreIdcode),
                extensionPoints,
                groupViews,
                includeIdcodes ? sharedCoreIdcode : null,
                mappings);
    }

    private StructureComparisonChangeGroup changeGroupView(int index,
                                                           TransformationGroup group,
                                                           TransformationSplitter.MCSMap mcs,
                                                           StereoMolecule left,
                                                           StereoMolecule right,
                                                           boolean includeIdcodes) {
        TransformationSignature signature = group.signature;
        String removed = renderIdcode(signature.removedIdcode);
        String added = renderIdcode(signature.addedIdcode);
        List<StructureComparisonGroupExtensionPoint> localPoints = new ArrayList<>();
        for (int i = 0; i < group.attachmentsA.size(); i++) {
            int leftAtom = group.attachmentsA.get(i);
            int rightAtom = mcs.mapAtoB[leftAtom];
            localPoints.add(new StructureComparisonGroupExtensionPoint(
                    i,
                    "*" + i,
                    leftAtom,
                    rightAtom,
                    atomEnvironment(left, leftAtom)));
        }
        return new StructureComparisonChangeGroup(
                index,
                group.type.name(),
                removed,
                added,
                nullToQuestion(removed) + " -> " + nullToQuestion(added),
                localPoints,
                group.attachmentsA,
                signature.attachmentPattern,
                renderIdcode(signature.contextShellIdcode),
                shortSignature(signature.sigId),
                includeIdcodes ? signature.sigId : null,
                includeIdcodes ? signature.removedIdcode : null,
                includeIdcodes ? signature.addedIdcode : null,
                includeIdcodes ? signature.contextShellIdcode : null,
                includeIdcodes ? signature.expandedRawContextIdcode : null,
                includeIdcodes ? signature.rxnClass : null);
    }

    private List<StructureComparisonExtensionPoint> extensionPoints(List<TransformationGroup> groups,
                                                                    TransformationSplitter.MCSMap mcs,
                                                                    StereoMolecule left,
                                                                    StereoMolecule right) {
        java.util.TreeSet<Integer> sorted = new java.util.TreeSet<>();
        for (TransformationGroup group : groups) {
            sorted.addAll(group.attachmentsA);
        }
        ArrayList<StructureComparisonExtensionPoint> result = new ArrayList<>();
        int index = 0;
        for (int leftAtom : sorted) {
            int rightAtom = mcs.mapAtoB[leftAtom];
            result.add(new StructureComparisonExtensionPoint(
                    index,
                    leftAtom,
                    rightAtom,
                    atomEnvironment(left, leftAtom),
                    rightAtom >= 0 ? atomEnvironment(right, rightAtom) : null));
            index++;
        }
        return List.copyOf(result);
    }

    private List<StructureAtomMapping> atomMappings(TransformationSplitter.MCSMap mcs) {
        ArrayList<StructureAtomMapping> result = new ArrayList<>();
        for (int leftAtom = 0; leftAtom < mcs.mapAtoB.length; leftAtom++) {
            int rightAtom = mcs.mapAtoB[leftAtom];
            if (rightAtom >= 0) {
                result.add(new StructureAtomMapping(leftAtom, rightAtom));
            }
        }
        return List.copyOf(result);
    }

    private StructureComparisonInput comparisonInput(ObjectNode args, String side) {
        String directSmiles = optionalString(args, side + "_smiles", null);
        if (directSmiles != null && !directSmiles.isBlank()) {
            return new StructureComparisonInput(side + "_smiles", side, directSmiles, parseComparisonSmiles(directSmiles));
        }
        String structureId = optionalString(args, side + "_structure_id", null);
        if (structureId != null && !structureId.isBlank()) {
            String repositoryId = requiredString(args, side + "_repository_id");
            StructureRecord record = repositories.getStructure(new StructureRef(repositoryId, structureId)).record();
            return new StructureComparisonInput(repositoryId + ":" + structureId, record.label(), record.canonicalSmiles(), parseComparisonSmiles(record.canonicalSmiles()));
        }
        String rowId = optionalString(args, side + "_row_id", null);
        if (rowId != null && !rowId.isBlank()) {
            String sessionId = requiredString(args, "session_id");
            optionalString(args, "structure_column_id", null); // accepted for forward compatibility; primary row structure is used in v1.
            PrismRowSetStructureCollection structures = prism.rowSetStructures(sessionId, "all");
            PrismRowStructureEntry entry = structures.structures().stream()
                    .filter(candidate -> rowId.equals(candidate.rowId()))
                    .findFirst()
                    .orElseThrow(() -> new ChemOperationException("prism_row_not_found", "Prism row " + rowId + " does not exist or has no usable structure."));
            return new StructureComparisonInput(sessionId + ":" + rowId, entry.label(), entry.smiles(), parseComparisonSmiles(entry.smiles()));
        }
        throw new ChemOperationException("invalid_compare_structures_input", "Provide " + side + "_smiles, " + side + "_repository_id/" + side + "_structure_id, or session_id/" + side + "_row_id.");
    }

    private static StereoMolecule parseComparisonSmiles(String smiles) {
        if (smiles == null || smiles.isBlank()) {
            throw new ChemOperationException("invalid_compare_structure", "Structure SMILES must not be blank.");
        }
        try {
            StereoMolecule molecule = new StereoMolecule();
            new SmilesParser().parse(molecule, smiles);
            molecule.ensureHelperArrays(StereoMolecule.cHelperRings);
            return molecule;
        } catch (Exception exception) {
            throw new ChemOperationException("invalid_compare_structure", "Could not parse SMILES: " + smiles, exception);
        }
    }

    private static String normalizeCompareOutputMode(String value) {
        String normalized = value == null || value.isBlank() ? "summary" : value.trim().toLowerCase();
        if (!"summary".equals(normalized) && !"compact".equals(normalized) && !"full".equals(normalized)) {
            throw new ChemOperationException("invalid_compare_output_mode", "output_mode must be summary, compact, or full.");
        }
        return normalized;
    }

    private static int coreAtomCount(TransformationSplitter.MCSMap mcs) {
        int count = 0;
        for (int rightAtom : mcs.mapAtoB) {
            if (rightAtom >= 0) {
                count++;
            }
        }
        return count;
    }

    private static String coreIdcode(StereoMolecule molecule, TransformationSplitter.MCSMap mcs) {
        BitSet coreAtoms = new BitSet(molecule.getAtoms());
        for (int atom = 0; atom < mcs.mapAtoB.length; atom++) {
            if (mcs.mapAtoB[atom] >= 0) {
                coreAtoms.set(atom);
            }
        }
        return fragmentIdcode(molecule, coreAtoms);
    }

    private static String fragmentIdcode(StereoMolecule molecule, BitSet atoms) {
        StereoMolecule fragment = new StereoMolecule();
        boolean[] keep = new boolean[molecule.getAtoms()];
        for (int atom = atoms.nextSetBit(0); atom >= 0; atom = atoms.nextSetBit(atom + 1)) {
            keep[atom] = true;
        }
        int[] map = new int[molecule.getAllAtoms()];
        java.util.Arrays.fill(map, -1);
        molecule.copyMoleculeByAtoms(fragment, keep, true, map);
        fragment.ensureHelperArrays(StereoMolecule.cHelperRings);
        return new Canonizer(fragment, Canonizer.ENCODE_ATOM_CUSTOM_LABELS).getIDCode();
    }

    private static String renderIdcode(String idcode) {
        if (idcode == null || idcode.isBlank()) {
            return null;
        }
        try {
            StereoMolecule molecule = new IDCodeParser(false).getCompactMolecule(idcode);
            if (molecule == null) {
                return null;
            }
            molecule.ensureHelperArrays(StereoMolecule.cHelperRings);
            return IsomericSmilesCreator.createSmiles(molecule);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String atomEnvironment(StereoMolecule molecule, int atom) {
        if (atom < 0 || atom >= molecule.getAtoms()) {
            return "";
        }
        String symbol = molecule.getAtomLabel(atom);
        String aromatic = molecule.isAromaticAtom(atom) ? "aromatic" : "aliphatic";
        String ring = molecule.isRingAtom(atom) ? "ring" : "chain";
        return symbol + " " + aromatic + " " + ring;
    }

    private static String comparisonSummaryText(String status, int coreAtomCount, int groupCount, List<String> changeTypes, int extensionPointCount) {
        if ("NO_CHANGE".equals(status)) {
            return "NO_CHANGE: structures align completely";
        }
        return status + ": " + coreAtomCount + "-atom core, " + groupCount + " edit" + (groupCount == 1 ? "" : "s")
                + (changeTypes.isEmpty() ? "" : " (" + String.join(", ", changeTypes) + ")")
                + ", " + extensionPointCount + " extension point" + (extensionPointCount == 1 ? "" : "s");
    }

    private static String shortSignature(String signatureId) {
        if (signatureId == null) {
            return null;
        }
        return signatureId.length() <= 12 ? signatureId : signatureId.substring(0, 12);
    }

    private static String nullToQuestion(String value) {
        return value == null || value.isBlank() ? "?" : value;
    }

    private static String requiredString(ObjectNode args, String name) {
        String value = optionalString(args, name, null);
        if (value == null || value.isBlank()) {
            throw new ChemOperationException("invalid_arguments", "Missing required argument: " + name);
        }
        return value;
    }

    private static String optionalString(ObjectNode args, String name, String defaultValue) {
        JsonNode node = args.get(name);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (!node.isTextual()) {
            throw new ChemOperationException("invalid_arguments", "Argument " + name + " must be a string.");
        }
        return node.asText();
    }

    private static int optionalInt(ObjectNode args, String name, int defaultValue) {
        JsonNode node = args.get(name);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (!node.canConvertToInt()) {
            throw new ChemOperationException("invalid_arguments", "Argument " + name + " must be an integer.");
        }
        return node.asInt();
    }

    private static boolean optionalBoolean(ObjectNode args, String name, boolean defaultValue) {
        JsonNode node = args.get(name);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (!node.isBoolean()) {
            throw new ChemOperationException("invalid_arguments", "Argument " + name + " must be a boolean.");
        }
        return node.asBoolean();
    }

    private record StructureComparisonInput(String id, String label, String smiles, StereoMolecule molecule) {
        int atomCount() {
            return molecule.getAtoms();
        }
    }

    private record StructureComparisonSummary(
            String status,
            String leftId,
            String rightId,
            int leftAtomCount,
            int rightAtomCount,
            int sharedCoreAtomCount,
            int changedAtomCountLeft,
            int changedAtomCountRight,
            int changeGroupCount,
            List<String> changeTypes,
            int extensionPointCount,
            String summaryText
    ) {
        private StructureComparisonSummary {
            changeTypes = changeTypes == null ? List.of() : List.copyOf(changeTypes);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record StructureComparisonDetail(
            StructureComparisonSummary summary,
            String sharedCoreSmiles,
            List<StructureComparisonExtensionPoint> extensionPoints,
            List<StructureComparisonChangeGroup> changeGroups,
            String sharedCoreIdcode,
            List<StructureAtomMapping> atomMappings
    ) {
        private StructureComparisonDetail {
            extensionPoints = extensionPoints == null ? List.of() : List.copyOf(extensionPoints);
            changeGroups = changeGroups == null ? List.of() : List.copyOf(changeGroups);
        }
    }

    private record StructureComparisonExtensionPoint(
            int index,
            int leftAtomIndex,
            int rightAtomIndex,
            String leftEnvironment,
            String rightEnvironment
    ) {}

    private record StructureComparisonGroupExtensionPoint(
            int index,
            String dummyLabel,
            int leftAtomIndex,
            int rightAtomIndex,
            String leftEnvironment
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record StructureComparisonChangeGroup(
            int index,
            String type,
            String removedFragmentSmiles,
            String addedFragmentSmiles,
            String transformText,
            List<StructureComparisonGroupExtensionPoint> extensionPoints,
            List<Integer> leftAttachmentAtomIndices,
            String attachmentPattern,
            String contextShellSmiles,
            String signatureId,
            String fullSignatureId,
            String removedIdcode,
            String addedIdcode,
            String contextShellIdcode,
            String expandedRawContextIdcode,
            String reactionClass
    ) {
        private StructureComparisonChangeGroup {
            extensionPoints = extensionPoints == null ? List.of() : List.copyOf(extensionPoints);
            leftAttachmentAtomIndices = leftAttachmentAtomIndices == null ? List.of() : List.copyOf(leftAttachmentAtomIndices);
        }
    }

    private record StructureAtomMapping(int leftAtomIndex, int rightAtomIndex) {}
}
