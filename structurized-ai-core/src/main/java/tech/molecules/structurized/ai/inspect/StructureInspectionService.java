package tech.molecules.structurized.ai.inspect;

import tech.molecules.structurized.ai.model.AtomEnvironmentInspection;
import tech.molecules.structurized.ai.model.AtomInspection;
import tech.molecules.structurized.ai.model.AtomRef;
import tech.molecules.structurized.ai.model.BondCutResult;
import tech.molecules.structurized.ai.model.BondInspection;
import tech.molecules.structurized.ai.model.BondRef;
import tech.molecules.structurized.ai.model.CutBondsRequest;
import tech.molecules.structurized.ai.model.RingSystemInspection;
import tech.molecules.structurized.ai.model.ShortestPathResult;
import tech.molecules.structurized.ai.model.StructureInspection;
import tech.molecules.structurized.ai.model.StructureRef;

public interface StructureInspectionService {
    StructureInspection inspectStructure(StructureRef structure);

    AtomInspection inspectAtom(AtomRef atom);

    BondInspection inspectBond(BondRef bond);

    AtomEnvironmentInspection inspectAtomEnvironment(AtomRef atom, int radius);

    RingSystemInspection inspectRingSystem(AtomRef atom);

    ShortestPathResult findShortestPath(AtomRef start, AtomRef end);

    BondCutResult cutBonds(CutBondsRequest request);
}
