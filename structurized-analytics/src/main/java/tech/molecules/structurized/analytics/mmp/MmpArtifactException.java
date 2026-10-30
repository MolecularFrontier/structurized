package tech.molecules.structurized.analytics.mmp;

/** Transport-neutral failures for reading a current MMP artifact. */
public final class MmpArtifactException extends IllegalStateException {
    public enum Reason { UNAVAILABLE, SCOPE_MISMATCH, REPLACED, INVALID_ARTIFACT, QUERY_FAILED }

    private final Reason reason;

    public MmpArtifactException(Reason reason, String message) {
        this(reason, message, null);
    }

    public MmpArtifactException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = java.util.Objects.requireNonNull(reason);
    }

    public Reason reason() { return reason; }
}
