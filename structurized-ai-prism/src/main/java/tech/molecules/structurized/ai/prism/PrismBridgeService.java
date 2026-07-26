package tech.molecules.structurized.ai.prism;

import tech.molecules.structurized.prism.prediction.PredictionCapability;

import java.util.List;

public interface PrismBridgeService {
    PrismDatasetSummary openDataset(OpenPrismDatasetRequest request);

    PrismSessionSummary openPack(OpenPrismPackRequest request);

    List<PrismDatasetSummary> listDatasets();

    List<PrismSessionSummary> listSessions();

    PrismSessionInfo getSessionInfo(String sessionId);

    List<PrismColumnSummary> listColumns(String sessionId);

    PrismSessionAgentDescription describeSessionForAgent(String sessionId);

    List<PrismMoleculeListSummary> listMoleculeLists(String sessionId);

    PrismMoleculeListView getMoleculeList(String sessionId, String listId);

    PrismMoleculeListSummary createMoleculeList(CreatePrismMoleculeListRequest request);

    PrismMoleculeListView addMolecules(AddPrismMoleculesRequest request);

    List<PrismRowSetSummary> listRowSets(String sessionId);

    List<PrismGroupingSummary> listGroupings(String sessionId);

    List<PrismGraphSummary> listGraphs(String sessionId);

    PrismGraphSummary summarizeGraph(String sessionId, String graphId);

    PrismGraphNeighborhood inspectGraphNeighborhood(String sessionId, String graphId, String centerRowId, int limit);

    PrismRowSetSummary createGraphNeighborhoodRowSet(CreatePrismGraphNeighborhoodRowSetRequest request);

    PrismMmpGraphSummary mineMmpGraph(MinePrismMmpGraphRequest request);

    PrismGroupingView getGrouping(String sessionId, String groupingId, int offset, int limit);

    PrismRowSetSummary createGroupRowSet(CreatePrismGroupRowSetRequest request);

    PrismRowSetColumnSummary summarizeRowSetByColumns(
            String sessionId,
            String rowSetId,
            List<String> columnIds,
            Double threshold,
            String thresholdDirection,
            int topValuesLimit);

    PrismGroupingColumnSummary summarizeGroupingByColumns(
            String sessionId,
            String groupingId,
            List<String> columnIds,
            boolean includeSingletons,
            int offset,
            int limit,
            Double threshold,
            String thresholdDirection,
            int topValuesLimit);

    PrismRowSetMembersView getRowSetMembers(String sessionId, String rowSetId, int offset, int limit);

    PrismRowSetSummary createRowSetFromSubjectSet(CreatePrismRowSetFromSubjectSetRequest request);

    PrismRowSetSummary createEndpointRowSet(CreatePrismEndpointRowSetRequest request);

    PrismRowSetSummary createColumnRowSet(CreatePrismColumnRowSetRequest request);

    PrismRowSetSummary combineRowSets(CombinePrismRowSetsRequest request);

    PrismRowSetStructureCollection rowSetStructures(String sessionId, String rowSetId);

    PrismClusteringSummary clusterRowSet(ClusterPrismRowSetRequest request);

    List<PrismAnalysisSummary> listAnalyses(String sessionId);

    List<PredictionCapability> listPredictionCapabilities(String sessionId, String endpointId);

    PredictionCapability describePredictionCapability(String sessionId, String capabilityId);

    PredictionRunSummary evaluatePrismPrediction(EvaluatePrismPredictionRequest request);


    PredictionRunView getPredictionRun(String sessionId, String predictionRunId, int offset, int limit);

    PrismClusteringView getClustering(String sessionId, String analysisId, boolean includeSingletons, int offset, int limit);

    PrismClusterMembersView getClusterMembers(String sessionId, String analysisId, String clusterId, int offset, int limit);

    PrismRowSetSummary createClusterRowSet(CreatePrismClusterRowSetRequest request);

    PrismDatasetInfo getDatasetInfo(String datasetId);

    List<PrismSubjectSetSummary> listSubjectSets(String datasetId);

    List<PrismSubjectSummary> listSubjects(String datasetId, String subjectSetId, int offset, int limit, boolean includeMetadata);

    PrismSubjectSummary getSubject(String datasetId, String subjectId);

    List<PrismEndpointSummary> listEndpoints(String datasetId);

    List<PrismEndpointValue> getEndpointValues(String datasetId, List<String> subjectIds, List<String> endpointIds);

    MaterializePrismSubjectSetResult materializeSubjectSet(MaterializePrismSubjectSetRequest request);
}
