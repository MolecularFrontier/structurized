package tech.molecules.structurized.ai.prism;

public record MinePrismMmpGraphRequest(
        String sessionId,
        String rowSetId,
        String structureColumnId,
        String valueColumnId,
        String graphId,
        String label,
        Integer maxCuts,
        Integer minTransformSupport,
        Integer maxVariableHeavyAtoms,
        Double maxVariableToMolHeavyAtomFraction,
        Integer maxFragmentationRecordsPerCompound,
        Integer maxPairsPerKey
) {
}
