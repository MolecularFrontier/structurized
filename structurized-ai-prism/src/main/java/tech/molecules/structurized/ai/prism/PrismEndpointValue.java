package tech.molecules.structurized.ai.prism;

import tech.molecules.structurized.prism.result.EndpointResult;

public record PrismEndpointValue(
        String subjectId,
        String endpointId,
        EndpointResult result
) {}
