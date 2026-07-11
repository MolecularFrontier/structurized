package tech.molecules.structurized.ai.model;

public final class ChemOperationException extends RuntimeException {
    private final String code;

    public ChemOperationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public ChemOperationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
