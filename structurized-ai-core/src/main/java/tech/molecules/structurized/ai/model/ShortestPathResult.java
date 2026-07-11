package tech.molecules.structurized.ai.model;

import java.util.List;

public record ShortestPathResult(
        String startAtom,
        String endAtom,
        int topologicalDistance,
        List<String> atomPath,
        List<String> bondPath,
        int ringSystemTransitions,
        List<String> rotatableCandidateBonds,
        boolean alternativeShortestPathsExist
) {}
