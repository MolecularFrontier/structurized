package tech.molecules.structurized.ai.prism;

import tech.molecules.structurized.prism.engine.PrismSession;
import tech.molecules.structurized.prism.provider.inmemory.InMemoryPrismDataset;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class ManagedPrismSession {
    private final String sessionId;
    private final String label;
    private final Path sourcePath;
    private final InMemoryPrismDataset dataContext;
    private final PrismSession workspace;
    private final Instant openedAt;
    private long revision;

    public ManagedPrismSession(String sessionId,
                               String label,
                               Path sourcePath,
                               InMemoryPrismDataset dataContext,
                               PrismSession workspace,
                               Instant openedAt) {
        this.sessionId = requireText(sessionId, "sessionId");
        this.label = label == null || label.isBlank() ? this.sessionId : label.trim();
        this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath");
        this.dataContext = dataContext;
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.openedAt = openedAt == null ? Instant.now() : openedAt;
        this.revision = 1L;
    }

    public String sessionId() {
        return sessionId;
    }

    public String label() {
        return label;
    }

    public Path sourcePath() {
        return sourcePath;
    }

    public Optional<InMemoryPrismDataset> dataContext() {
        return Optional.ofNullable(dataContext);
    }

    public InMemoryPrismDataset requireDataContext() {
        if (dataContext == null) {
            throw new tech.molecules.structurized.ai.model.ChemOperationException(
                    "prism_data_context_unavailable",
                    "This Prism session was opened from a PrismPack and does not have canonical PRISM TSV endpoint records."
            );
        }
        return dataContext;
    }

    public PrismSession workspace() {
        return workspace;
    }

    public Instant openedAt() {
        return openedAt;
    }

    public long revision() {
        return revision;
    }

    public long bumpRevision() {
        return ++revision;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
