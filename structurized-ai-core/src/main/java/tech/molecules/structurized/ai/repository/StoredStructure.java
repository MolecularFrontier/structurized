package tech.molecules.structurized.ai.repository;

import tech.molecules.structurized.ai.model.StructureRecord;
import tech.molecules.structurized.ai.ocl.MolecularSnapshot;

public record StoredStructure(StructureRecord record, MolecularSnapshot snapshot) {}
