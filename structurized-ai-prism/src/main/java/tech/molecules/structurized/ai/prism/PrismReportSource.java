package tech.molecules.structurized.ai.prism;

import java.nio.file.Path;

public record PrismReportSource(Path path, String source) {
    public PrismReportSource {
        if ((path == null) == (source == null)) {
            throw new IllegalArgumentException("exactly one of report path or source is required");
        }
        path = path == null ? null : path.toAbsolutePath().normalize();
    }

    public static PrismReportSource fromPath(Path path) {
        return new PrismReportSource(path, null);
    }

    public static PrismReportSource inline(String source) {
        return new PrismReportSource(null, source);
    }
}
