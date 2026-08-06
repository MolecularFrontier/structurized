package tech.molecules.structurized.mmp;

import java.util.Objects;

/** Typed, non-throwing outcome for one transform application attempt. */
public record MmpTransformApplicationAttempt(
        MmpTransformApplicationStatus status,
        MmpTransformApplication application,
        String message
) {
    public MmpTransformApplicationAttempt {
        status = Objects.requireNonNull(status, "status");
        message = normalize(message);
        boolean applied = status == MmpTransformApplicationStatus.APPLIED;
        if (applied != (application != null)) {
            throw new IllegalArgumentException("only APPLIED attempts contain an application");
        }
        if (applied) {
            message = null;
        } else if (message == null) {
            throw new IllegalArgumentException("unsuccessful attempts require a message");
        }
    }

    public static MmpTransformApplicationAttempt applied(MmpTransformApplication application) {
        return new MmpTransformApplicationAttempt(MmpTransformApplicationStatus.APPLIED, application, null);
    }

    public static MmpTransformApplicationAttempt notApplicable(String message) {
        return new MmpTransformApplicationAttempt(MmpTransformApplicationStatus.NOT_APPLICABLE, null, message);
    }

    public static MmpTransformApplicationAttempt invalidTransform(String message) {
        return new MmpTransformApplicationAttempt(MmpTransformApplicationStatus.INVALID_TRANSFORM, null, message);
    }

    public static MmpTransformApplicationAttempt invalidProduct(String message) {
        return new MmpTransformApplicationAttempt(MmpTransformApplicationStatus.INVALID_PRODUCT, null, message);
    }

    public boolean isApplied() {
        return status == MmpTransformApplicationStatus.APPLIED;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
