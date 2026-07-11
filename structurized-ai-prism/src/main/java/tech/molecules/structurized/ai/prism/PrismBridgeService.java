package tech.molecules.structurized.ai.prism;

import java.util.List;

public interface PrismBridgeService {
    PrismDatasetSummary openDataset(OpenPrismDatasetRequest request);

    List<PrismDatasetSummary> listDatasets();

    PrismDatasetInfo getDatasetInfo(String datasetId);

    List<PrismSubjectSetSummary> listSubjectSets(String datasetId);

    List<PrismSubjectSummary> listSubjects(String datasetId, String subjectSetId, int offset, int limit, boolean includeMetadata);

    PrismSubjectSummary getSubject(String datasetId, String subjectId);

    List<PrismEndpointSummary> listEndpoints(String datasetId);

    List<PrismEndpointValue> getEndpointValues(String datasetId, List<String> subjectIds, List<String> endpointIds);

    MaterializePrismSubjectSetResult materializeSubjectSet(MaterializePrismSubjectSetRequest request);
}
