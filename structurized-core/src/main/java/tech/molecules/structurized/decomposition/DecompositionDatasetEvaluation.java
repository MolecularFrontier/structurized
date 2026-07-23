package tech.molecules.structurized.decomposition;

import java.util.List;
import java.util.Map;

/**
 * Dataset-wide decomposition summary plus per-molecule witnesses.
 */
public record DecompositionDatasetEvaluation(
        String configurationVersion,
        int moleculeCount,
        int successfulCount,
        int rootNoMatchCount,
        int nonUniqueCount,
        int invalidCount,
        Map<String, Map<String, Integer>> terminalFragmentFrequencies,
        List<DecompositionResult> results
) {
    public double coverage() {
        return moleculeCount == 0 ? 0.0 : (double) successfulCount / moleculeCount;
    }
}
