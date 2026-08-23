package tech.molecules.structurized.ai.prism;

import tech.molecules.structurized.prism.score.EndpointScoreDefinition;

public record PrismEndpointScoreSummary(
        EndpointScoreDefinition definition,
        String fingerprint,
        String sourceColumnId,
        String outputColumnId
) {
}
