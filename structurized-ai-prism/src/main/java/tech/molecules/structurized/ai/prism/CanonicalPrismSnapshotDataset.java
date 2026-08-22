package tech.molecules.structurized.ai.prism;

import tech.molecules.structurized.prism.engine.PrismRowSet;
import tech.molecules.structurized.prism.engine.PrismTable;
import tech.molecules.structurized.prism.engine.snapshot.PrismEndpointCell;
import tech.molecules.structurized.prism.engine.snapshot.PrismSnapshotCapabilities;
import tech.molecules.structurized.prism.engine.snapshot.PrismSnapshotDataset;
import tech.molecules.structurized.prism.engine.snapshot.PrismSnapshotEndpoint;
import tech.molecules.structurized.prism.engine.snapshot.PrismSnapshotOrigin;
import tech.molecules.structurized.prism.provider.inmemory.InMemoryPrismDataset;
import tech.molecules.structurized.prism.score.EndpointScoreDefinition;

import java.util.List;
import java.util.Optional;

/** Snapshot adapter retaining source records only for deprecated non-snapshot Java APIs. */
final class CanonicalPrismSnapshotDataset implements PrismSnapshotDataset {
    private final PrismSnapshotDataset delegate;
    private final InMemoryPrismDataset sourceDataset;

    CanonicalPrismSnapshotDataset(PrismSnapshotDataset delegate, InMemoryPrismDataset sourceDataset) {
        this.delegate = delegate;
        this.sourceDataset = sourceDataset;
    }

    InMemoryPrismDataset sourceDataset() { return sourceDataset; }
    @Override public PrismTable table() { return delegate.table(); }
    @Override public List<PrismSnapshotEndpoint> endpoints() { return delegate.endpoints(); }
    @Override public List<PrismRowSet> rowSets() { return delegate.rowSets(); }
    @Override public List<EndpointScoreDefinition> scoreDefinitions() { return delegate.scoreDefinitions(); }
    @Override public Optional<PrismEndpointCell> endpointCell(String rowId, String endpointId) { return delegate.endpointCell(rowId, endpointId); }
    @Override public PrismSnapshotCapabilities capabilities() { return delegate.capabilities(); }
    @Override public Optional<PrismSnapshotOrigin> origin() { return delegate.origin(); }
}
