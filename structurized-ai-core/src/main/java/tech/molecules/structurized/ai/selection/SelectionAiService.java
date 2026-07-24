package tech.molecules.structurized.ai.selection;

import tech.molecules.structurized.ai.model.ChemOperationException;
import tech.molecules.structurized.ai.model.StructureRecord;
import tech.molecules.structurized.ai.model.StructureRef;
import tech.molecules.structurized.ai.repository.StructureRepositoryService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Session-scoped structure selections used as compact handles by MCP tools.
 */
public final class SelectionAiService {
    private static final int PAGE_LIMIT_DEFAULT = 50;
    private static final int PAGE_LIMIT_MAX = 500;

    private final StructureRepositoryService repositories;
    private final Map<String, StoredSelection> selections = new LinkedHashMap<>();
    private int nextSelectionIndex = 1;

    public SelectionAiService(StructureRepositoryService repositories) {
        this.repositories = Objects.requireNonNull(repositories, "repositories");
    }

    public synchronized SelectionRecord createSelection(
            String selectionId,
            String repositoryId,
            String sourceType,
            String sourceId,
            List<String> structureIds
    ) {
        String repoId = normalizeId(repositoryId, "repository_id");
        String id = normalizeId(selectionId == null || selectionId.isBlank() ? generatedSelectionId() : selectionId, "selection_id");
        if (selections.containsKey(id)) {
            throw new ChemOperationException("duplicate_selection", "Selection " + id + " already exists.");
        }
        List<SelectionMember> members = new ArrayList<>();
        for (String structureId : structureIds == null ? List.<String>of() : structureIds) {
            StructureRecord record = repositories.getStructure(new StructureRef(repoId, structureId)).record();
            members.add(member(record));
        }
        StoredSelection stored = new StoredSelection(
                id,
                repoId,
                sourceType == null || sourceType.isBlank() ? "manual" : sourceType.trim(),
                sourceId == null || sourceId.isBlank() ? null : sourceId.trim(),
                List.copyOf(members)
        );
        selections.put(id, stored);
        return stored.toRecord();
    }

    public synchronized SelectionRecord createSelectionFromRecords(
            String selectionId,
            String repositoryId,
            String sourceType,
            String sourceId,
            List<StructureRecord> records
    ) {
        String repoId = normalizeId(repositoryId, "repository_id");
        String id = normalizeId(selectionId == null || selectionId.isBlank() ? generatedSelectionId() : selectionId, "selection_id");
        if (selections.containsKey(id)) {
            throw new ChemOperationException("duplicate_selection", "Selection " + id + " already exists.");
        }
        List<SelectionMember> members = (records == null ? List.<StructureRecord>of() : records).stream()
                .filter(record -> repoId.equals(record.repositoryId()))
                .map(SelectionAiService::member)
                .toList();
        StoredSelection stored = new StoredSelection(
                id,
                repoId,
                sourceType == null || sourceType.isBlank() ? "manual" : sourceType.trim(),
                sourceId == null || sourceId.isBlank() ? null : sourceId.trim(),
                members
        );
        selections.put(id, stored);
        return stored.toRecord();
    }

    public synchronized SelectionView getSelection(String selectionId) {
        StoredSelection stored = selection(selectionId);
        return new SelectionView(stored.toRecord(), examples(stored.members(), 3));
    }

    public synchronized SelectionMembersView getMembers(String selectionId, int offset, int limit) {
        StoredSelection stored = selection(selectionId);
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.min(PAGE_LIMIT_MAX, Math.max(1, limit <= 0 ? PAGE_LIMIT_DEFAULT : limit));
        return new SelectionMembersView(stored.toRecord(), page(stored.members(), safeOffset, safeLimit));
    }

    public synchronized List<SelectionMember> allMembers(String selectionId) {
        return selection(selectionId).members();
    }

    public synchronized StoredSelectionData selectionData(String selectionId) {
        StoredSelection stored = selection(selectionId);
        return new StoredSelectionData(stored.toRecord(), stored.members());
    }

    private StoredSelection selection(String selectionId) {
        StoredSelection stored = selections.get(normalizeId(selectionId, "selection_id"));
        if (stored == null) {
            throw new ChemOperationException("selection_not_found", "Selection " + selectionId + " does not exist.");
        }
        return stored;
    }

    private String generatedSelectionId() {
        String id;
        do {
            id = "selection_" + nextSelectionIndex++;
        } while (selections.containsKey(id));
        return id;
    }

    private static SelectionMember member(StructureRecord record) {
        return new SelectionMember(record.structureId(), record.label(), record.canonicalSmiles(), record.fields());
    }

    private static List<SelectionMember> examples(List<SelectionMember> members, int limit) {
        return List.copyOf(members.subList(0, Math.min(limit, members.size())));
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

    private record StoredSelection(String selectionId, String repositoryId, String sourceType, String sourceId, List<SelectionMember> members) {
        private SelectionRecord toRecord() {
            return new SelectionRecord(selectionId, repositoryId, sourceType, sourceId, members.size());
        }
    }

    public record SelectionRecord(String selectionId, String repositoryId, String sourceType, String sourceId, int memberCount) {}

    public record SelectionMember(String structureId, String label, String canonicalSmiles, Map<String, String> fields) {
        public SelectionMember {
            fields = fields == null ? Map.of() : Map.copyOf(fields);
        }
    }

    public record SelectionView(SelectionRecord summary, List<SelectionMember> examples) {}

    public record SelectionMembersView(SelectionRecord summary, List<SelectionMember> members) {}

    public record StoredSelectionData(SelectionRecord summary, List<SelectionMember> members) {}
}
