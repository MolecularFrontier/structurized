package tech.molecules.structurized.ai.prism;

import java.util.List;

public interface PrismBridgeService {
    PrismDatasetSummary openDataset(OpenPrismDatasetRequest request);

    PrismSessionSummary openPack(OpenPrismPackRequest request);

    List<PrismDatasetSummary> listDatasets();

    List<PrismSessionSummary> listSessions();

    PrismSessionInfo getSessionInfo(String sessionId);

    List<PrismColumnSummary> listColumns(String sessionId);

    PrismSessionAgentDescription describeSessionForAgent(String sessionId);

    List<PrismRowSetSummary> listRowSets(String sessionId);

    PrismRowSetMembersView getRowSetMembers(String sessionId, String rowSetId, int offset, int limit);

    PrismRowSetSummary createRowSetFromSubjectSet(CreatePrismRowSetFromSubjectSetRequest request);

    PrismRowSetSummary createEndpointRowSet(CreatePrismEndpointRowSetRequest request);

    PrismRowSetSummary combineRowSets(CombinePrismRowSetsRequest request);

    PrismRowSetStructureCollection rowSetStructures(String sessionId, String rowSetId);

    PrismDatasetInfo getDatasetInfo(String datasetId);

    List<PrismSubjectSetSummary> listSubjectSets(String datasetId);

    List<PrismSubjectSummary> listSubjects(String datasetId, String subjectSetId, int offset, int limit, boolean includeMetadata);

    PrismSubjectSummary getSubject(String datasetId, String subjectId);

    List<PrismEndpointSummary> listEndpoints(String datasetId);

    List<PrismEndpointValue> getEndpointValues(String datasetId, List<String> subjectIds, List<String> endpointIds);

    MaterializePrismSubjectSetResult materializeSubjectSet(MaterializePrismSubjectSetRequest request);
}
