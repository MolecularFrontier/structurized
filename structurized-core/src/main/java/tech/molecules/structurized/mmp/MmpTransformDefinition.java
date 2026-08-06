package tech.molecules.structurized.mmp;

import java.util.Objects;

/**
 * The chemistry required to apply one directed MMP transformation.
 */
public record MmpTransformDefinition(
        String transformId,
        int cutCount,
        String fromValueIdcode,
        String toValueIdcode
) {
    public MmpTransformDefinition {
        transformId = requireText(transformId, "transformId");
        if (cutCount < 1 || cutCount > 2) {
            throw new IllegalArgumentException("cutCount must be 1 or 2");
        }
        fromValueIdcode = requireText(fromValueIdcode, "fromValueIdcode");
        toValueIdcode = requireText(toValueIdcode, "toValueIdcode");
    }

    public static MmpTransformDefinition from(MmpPair pair) {
        Objects.requireNonNull(pair, "pair");
        return new MmpTransformDefinition(pair.transformId(), pair.cutCount(),
                pair.fromValueIdcode(), pair.toValueIdcode());
    }

    public static MmpTransformDefinition from(MmpTransformStats stats) {
        Objects.requireNonNull(stats, "stats");
        return new MmpTransformDefinition(stats.transformId(), stats.cutCount(),
                stats.fromValueIdcode(), stats.toValueIdcode());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
