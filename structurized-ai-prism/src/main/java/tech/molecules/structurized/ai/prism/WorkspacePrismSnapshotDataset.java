package tech.molecules.structurized.ai.prism;

import tech.molecules.structurized.prism.engine.PrismRowSet;
import tech.molecules.structurized.prism.engine.PrismSession;
import tech.molecules.structurized.prism.engine.PrismTable;
import tech.molecules.structurized.prism.engine.snapshot.PrismEndpointCell;
import tech.molecules.structurized.prism.engine.snapshot.PrismSnapshotCapabilities;
import tech.molecules.structurized.prism.engine.snapshot.PrismSnapshotDataset;
import tech.molecules.structurized.prism.engine.snapshot.PrismSnapshotEndpoint;
import tech.molecules.structurized.prism.engine.snapshot.PrismSnapshotOrigin;
import tech.molecules.structurized.prism.engine.snapshot.EndpointResultFidelity;
import tech.molecules.structurized.prism.score.EndpointScoreDefinition;

import java.util.List;
import java.util.Optional;

final class WorkspacePrismSnapshotDataset implements PrismSnapshotDataset {
    private final PrismSession session;
    WorkspacePrismSnapshotDataset(PrismSession session) { this.session = session; }
    @Override public PrismTable table() { return session.baseTable(); }
    @Override public List<PrismSnapshotEndpoint> endpoints() { return List.of(); }
    @Override public List<PrismRowSet> rowSets() { return session.rowSets(); }
    @Override public List<EndpointScoreDefinition> scoreDefinitions() { return session.scoreDefinitions(); }
    @Override public Optional<PrismEndpointCell> endpointCell(String rowId, String endpointId) { return Optional.empty(); }
    @Override public PrismSnapshotCapabilities capabilities() { return new PrismSnapshotCapabilities(EndpointResultFidelity.NONE, false, !session.rowSets().isEmpty(), !session.scoreDefinitions().isEmpty(), false, false); }
    @Override public Optional<PrismSnapshotOrigin> origin() { return Optional.empty(); }
}
