package tech.molecules.structurized.ai.repository;

import tech.molecules.structurized.ai.model.ChemOperationException;
import tech.molecules.structurized.ai.model.CreateRepositoryRequest;
import tech.molecules.structurized.ai.model.RegisterStructureRequest;
import tech.molecules.structurized.ai.model.RepositoryRecord;
import tech.molecules.structurized.ai.model.StructureRecord;
import tech.molecules.structurized.ai.model.StructureRef;
import tech.molecules.structurized.ai.ocl.MolecularSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class InMemoryStructureRepositoryService implements StructureRepositoryService {
    public static final String DEFAULT_REPOSITORY_ID = "session";

    private final Map<String, MutableRepository> repositories = new LinkedHashMap<>();

    public InMemoryStructureRepositoryService() {
        repositories.put(DEFAULT_REPOSITORY_ID, new MutableRepository(
                new RepositoryRecord(DEFAULT_REPOSITORY_ID, "Session structures", null, true, "session", 0)
        ));
    }

    @Override
    public synchronized RepositoryRecord createRepository(CreateRepositoryRequest request) {
        Objects.requireNonNull(request, "request");
        String repositoryId = normalizeId(
                request.repositoryId() == null || request.repositoryId().isBlank()
                        ? generatedRepositoryId()
                        : request.repositoryId(),
                "repositoryId"
        );
        if (repositories.containsKey(repositoryId)) {
            throw new ChemOperationException("duplicate_repository_id", "Repository " + repositoryId + " already exists.");
        }
        RepositoryRecord record = new RepositoryRecord(
                repositoryId,
                request.label() == null || request.label().isBlank() ? repositoryId : request.label().trim(),
                request.description(),
                request.mutable(),
                "programmatic",
                0
        );
        repositories.put(repositoryId, new MutableRepository(record));
        return record;
    }

    @Override
    public synchronized List<RepositoryRecord> listRepositories() {
        return repositories.values().stream()
                .map(MutableRepository::toRecord)
                .toList();
    }

    @Override
    public synchronized StructureRecord registerStructure(RegisterStructureRequest request) {
        Objects.requireNonNull(request, "request");
        String repositoryId = request.repositoryId() == null || request.repositoryId().isBlank()
                ? DEFAULT_REPOSITORY_ID
                : normalizeId(request.repositoryId(), "repositoryId");
        MutableRepository repository = repository(repositoryId);
        if (!repository.record.mutable()) {
            throw new ChemOperationException("repository_read_only", "Repository " + repositoryId + " is read-only.");
        }
        String structureId = request.structureId() == null || request.structureId().isBlank()
                ? generatedStructureId(repository)
                : normalizeId(request.structureId(), "structureId");
        if (repository.structures.containsKey(structureId)) {
            throw new ChemOperationException("duplicate_structure_id", "Structure " + repositoryId + ":" + structureId + " already exists.");
        }

        MolecularSnapshot snapshot = MolecularSnapshot.fromSmiles(request.smiles());
        StructureRecord record = new StructureRecord(
                repositoryId,
                structureId,
                request.label() == null || request.label().isBlank() ? structureId : request.label().trim(),
                request.smiles(),
                snapshot.canonicalSmiles(),
                snapshot.canonicalIdCode(),
                request.fields() == null ? Map.of() : Map.copyOf(request.fields()),
                snapshot.components().size(),
                snapshot.atomCount(),
                snapshot.bondCount()
        );
        repository.structures.put(structureId, new StoredStructure(record, snapshot));
        return record;
    }

    @Override
    public synchronized List<StructureRecord> listStructures(String repositoryId, int offset, int limit) {
        if (offset < 0 || limit < 1) {
            throw new IllegalArgumentException("offset must be >= 0 and limit must be >= 1");
        }
        MutableRepository repository = repository(repositoryId);
        List<StructureRecord> records = repository.structures.values().stream()
                .map(StoredStructure::record)
                .toList();
        int from = Math.min(offset, records.size());
        int to = Math.min(from + limit, records.size());
        return List.copyOf(records.subList(from, to));
    }

    @Override
    public synchronized StoredStructure getStructure(StructureRef reference) {
        Objects.requireNonNull(reference, "reference");
        MutableRepository repository = repository(reference.repositoryId());
        StoredStructure structure = repository.structures.get(reference.structureId());
        if (structure == null) {
            throw new ChemOperationException(
                    "structure_not_found",
                    "Structure " + reference.qualifiedId() + " does not exist."
            );
        }
        return structure;
    }

    private MutableRepository repository(String repositoryId) {
        MutableRepository repository = repositories.get(normalizeId(repositoryId, "repositoryId"));
        if (repository == null) {
            throw new ChemOperationException("repository_not_found", "Repository " + repositoryId + " does not exist.");
        }
        return repository;
    }

    private String generatedRepositoryId() {
        int index = repositories.size() + 1;
        while (repositories.containsKey("repo" + index)) {
            index++;
        }
        return "repo" + index;
    }

    private String generatedStructureId(MutableRepository repository) {
        int index = repository.nextStructureIndex++;
        while (repository.structures.containsKey("s" + index)) {
            index = repository.nextStructureIndex++;
        }
        return "s" + index;
    }

    private static String normalizeId(String value, String name) {
        Objects.requireNonNull(value, name);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return trimmed;
    }

    private static final class MutableRepository {
        private final RepositoryRecord record;
        private final Map<String, StoredStructure> structures = new LinkedHashMap<>();
        private int nextStructureIndex = 1;

        private MutableRepository(RepositoryRecord record) {
            this.record = record;
        }

        private RepositoryRecord toRecord() {
            return new RepositoryRecord(
                    record.repositoryId(),
                    record.label(),
                    record.description(),
                    record.mutable(),
                    record.sourceType(),
                    structures.size()
            );
        }
    }
}
