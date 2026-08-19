package tech.molecules.structurized.mmp;

import java.util.Objects;

/** Non-throwing result of assembling a constant key and one variable fragment. */
public record MmpFragmentAssemblyAttempt(
        MmpFragmentAssemblyStatus status,
        String productIdcode,
        String message
) {
    public MmpFragmentAssemblyAttempt {
        status = Objects.requireNonNull(status, "status");
        productIdcode = normalize(productIdcode);
        message = normalize(message);
        boolean assembled = status == MmpFragmentAssemblyStatus.ASSEMBLED;
        if (assembled != (productIdcode != null)) {
            throw new IllegalArgumentException("only ASSEMBLED attempts contain a product IDCode");
        }
        if (assembled) {
            message = null;
        } else if (message == null) {
            throw new IllegalArgumentException("failed assembly attempts require a message");
        }
    }

    public static MmpFragmentAssemblyAttempt assembled(String productIdcode) {
        return new MmpFragmentAssemblyAttempt(
                MmpFragmentAssemblyStatus.ASSEMBLED, productIdcode, null);
    }

    public static MmpFragmentAssemblyAttempt failed(
            MmpFragmentAssemblyStatus status,
            String message
    ) {
        if (status == MmpFragmentAssemblyStatus.ASSEMBLED) {
            throw new IllegalArgumentException("ASSEMBLED is not a failure status");
        }
        return new MmpFragmentAssemblyAttempt(status, null, message);
    }

    public boolean isAssembled() {
        return status == MmpFragmentAssemblyStatus.ASSEMBLED;
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
