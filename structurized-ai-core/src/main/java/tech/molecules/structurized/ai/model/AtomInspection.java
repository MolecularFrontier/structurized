package tech.molecules.structurized.ai.model;

import java.util.List;

public record AtomInspection(
        String atomId,
        String element,
        int atomicNumber,
        Integer isotope,
        int formalCharge,
        int implicitHydrogens,
        int totalHydrogens,
        int heavyAtomDegree,
        int occupiedValence,
        boolean aromatic,
        boolean ringAtom,
        Integer smallestRingSize,
        String componentId,
        List<String> neighborAtoms,
        List<String> incidentBonds,
        String stereo,
        Coordinates2d coordinates2d
) {}
