package tech.molecules.structurized.analytics.mmp;

import tech.molecules.structurized.mmp.MmpAttachment;
import tech.molecules.structurized.mmp.MmpTransformDefinition;
import tech.molecules.structurized.mmp.MmpTransformStats;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** One directed transform applied at one mapped site of the query molecule. */
public record MmpRecommendationCandidate(
        String productIdcode,
        MmpTransformDefinition transform,
        List<MmpAttachment> attachments,
        List<Integer> sourceValueAtomIndices,
        Map<String, MmpTransformStats> statsByRunId
) {
    public MmpRecommendationCandidate {
        if (productIdcode == null || productIdcode.isBlank()) {
            throw new IllegalArgumentException("productIdcode must not be blank");
        }
        productIdcode = productIdcode.trim();
        transform = Objects.requireNonNull(transform, "transform");
        attachments = List.copyOf(attachments == null ? List.of() : attachments);
        sourceValueAtomIndices = List.copyOf(
                sourceValueAtomIndices == null ? List.of() : sourceValueAtomIndices);
        statsByRunId = Map.copyOf(new LinkedHashMap<>(
                statsByRunId == null ? Map.of() : statsByRunId));
    }

    public MmpTransformStats statsFor(String runId) {
        return statsByRunId.get(runId);
    }

    public List<Integer> cutBondIndices() {
        return attachments.stream().map(MmpAttachment::cutBondIndex).sorted().toList();
    }
}
