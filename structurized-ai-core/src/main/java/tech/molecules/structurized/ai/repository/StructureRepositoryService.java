package tech.molecules.structurized.ai.repository;

import tech.molecules.structurized.ai.model.CreateRepositoryRequest;
import tech.molecules.structurized.ai.model.RegisterStructureRequest;
import tech.molecules.structurized.ai.model.RepositoryRecord;
import tech.molecules.structurized.ai.model.StructureRecord;
import tech.molecules.structurized.ai.model.StructureRef;

import java.util.List;

public interface StructureRepositoryService {
    RepositoryRecord createRepository(CreateRepositoryRequest request);

    List<RepositoryRecord> listRepositories();

    StructureRecord registerStructure(RegisterStructureRequest request);

    List<StructureRecord> listStructures(String repositoryId, int offset, int limit);

    StoredStructure getStructure(StructureRef reference);
}
