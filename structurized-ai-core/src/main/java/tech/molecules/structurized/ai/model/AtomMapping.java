package tech.molecules.structurized.ai.model;

import java.util.List;
import java.util.Map;

public record AtomMapping(
        Map<String, String> queryToTarget,
        List<String> targetAtomIds
) {}
