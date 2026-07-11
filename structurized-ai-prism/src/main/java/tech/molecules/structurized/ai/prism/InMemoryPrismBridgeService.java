package tech.molecules.structurized.ai.prism;

import tech.molecules.structurized.ai.model.ChemOperationException;
import tech.molecules.structurized.ai.model.CreateRepositoryRequest;
import tech.molecules.structurized.ai.model.RegisterStructureRequest;
import tech.molecules.structurized.ai.repository.StructureRepositoryService;
import tech.molecules.structurized.prism.io.PrismTsvDatasetLoader;
import tech.molecules.structurized.prism.model.CategoryDefinition;
import tech.molecules.structurized.prism.model.EndpointDefinition;
import tech.molecules.structurized.prism.model.NumericEndpointMeta;
import tech.molecules.structurized.prism.provider.SubjectRecord;
import tech.molecules.structurized.prism.provider.SubjectSet;
import tech.molecules.structurized.prism.provider.inmemory.InMemoryPrismDataset;
import tech.molecules.structurized.prism.query.EndpointValueRecord;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class InMemoryPrismBridgeService implements PrismBridgeService {
    private final StructureRepositoryService repositories;
    private final Map<String, LoadedPrismDataset> datasets = new LinkedHashMap<>();
    private final Map<String, MaterializationMapping> materializationsByRepositoryId = new LinkedHashMap<>();

    public InMemoryPrismBridgeService(StructureRepositoryService repositories) {
        this.repositories = Objects.requireNonNull(repositories, "repositories");
    }

    @Override
    public synchronized PrismDatasetSummary openDataset(OpenPrismDatasetRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.path() == null) {
            throw new ChemOperationException("invalid_prism_path", "Prism dataset path must not be null.");
        }
        Path sourcePath = request.path().toAbsolutePath().normalize();
        String datasetId = normalizeId(request.datasetId() == null || request.datasetId().isBlank()
                ? generatedDatasetId()
                : request.datasetId(), "datasetId");
        if (datasets.containsKey(datasetId)) {
            throw new ChemOperationException("duplicate_prism_dataset_id", "Prism dataset " + datasetId + " already exists.");
        }
        try {
            InMemoryPrismDataset dataset = PrismTsvDatasetLoader.load(sourcePath);
            String label = request.label() == null || request.label().isBlank() ? datasetId : request.label().trim();
            LoadedPrismDataset loaded = new LoadedPrismDataset(datasetId, label, sourcePath, dataset, Instant.now());
            datasets.put(datasetId, loaded);
            return summary(loaded);
        } catch (IOException | RuntimeException e) {
            throw new ChemOperationException("invalid_prism_dataset", "Could not load Prism dataset from " + sourcePath + ".", e);
        }
    }

    @Override
    public synchronized List<PrismDatasetSummary> listDatasets() {
        return datasets.values().stream().map(this::summary).toList();
    }

    @Override
    public synchronized PrismDatasetInfo getDatasetInfo(String datasetId) {
        LoadedPrismDataset loaded = dataset(datasetId);
        return new PrismDatasetInfo(summary(loaded), subjectSets(loaded), endpoints(loaded));
    }

    @Override
    public synchronized List<PrismSubjectSetSummary> listSubjectSets(String datasetId) {
        return subjectSets(dataset(datasetId));
    }

    @Override
    public synchronized List<PrismSubjectSummary> listSubjects(String datasetId, String subjectSetId, int offset, int limit, boolean includeMetadata) {
        if (offset < 0 || limit < 1) {
            throw new ChemOperationException("invalid_arguments", "offset must be >= 0 and limit must be >= 1.");
        }
        LoadedPrismDataset loaded = dataset(datasetId);
        List<SubjectRecord> subjects = subjects(loaded, subjectSetId);
        int from = Math.min(offset, subjects.size());
        int to = Math.min(from + limit, subjects.size());
        return subjects.subList(from, to).stream()
                .map(subject -> subjectSummary(subject, includeMetadata))
                .toList();
    }

    @Override
    public synchronized PrismSubjectSummary getSubject(String datasetId, String subjectId) {
        LoadedPrismDataset loaded = dataset(datasetId);
        SubjectRecord subject = loaded.dataset().findSubjectRecord(normalizeId(subjectId, "subjectId"))
                .orElseThrow(() -> new ChemOperationException("prism_subject_not_found", "Prism subject " + subjectId + " does not exist."));
        return subjectSummary(subject, true);
    }

    @Override
    public synchronized List<PrismEndpointSummary> listEndpoints(String datasetId) {
        return endpoints(dataset(datasetId));
    }

    @Override
    public synchronized List<PrismEndpointValue> getEndpointValues(String datasetId, List<String> subjectIds, List<String> endpointIds) {
        LoadedPrismDataset loaded = dataset(datasetId);
        if (subjectIds == null || subjectIds.isEmpty()) {
            throw new ChemOperationException("invalid_arguments", "subjectIds must not be empty.");
        }
        if (endpointIds == null || endpointIds.isEmpty()) {
            throw new ChemOperationException("invalid_arguments", "endpointIds must not be empty.");
        }
        List<PrismEndpointValue> result = new ArrayList<>();
        for (String subjectId : subjectIds) {
            String normalizedSubjectId = normalizeId(subjectId, "subjectId");
            if (loaded.dataset().findSubjectRecord(normalizedSubjectId).isEmpty()) {
                throw new ChemOperationException("prism_subject_not_found", "Prism subject " + normalizedSubjectId + " does not exist.");
            }
            for (String endpointId : endpointIds) {
                String normalizedEndpointId = normalizeId(endpointId, "endpointId");
                if (loaded.dataset().findEndpointDefinition(normalizedEndpointId).isEmpty()) {
                    throw new ChemOperationException("prism_endpoint_not_found", "Prism endpoint " + normalizedEndpointId + " does not exist.");
                }
                loaded.dataset().findEndpointValue(normalizedSubjectId, normalizedEndpointId)
                        .map(value -> new PrismEndpointValue(value.getSubjectId(), value.getEndpointId(), value.getResult()))
                        .ifPresent(result::add);
            }
        }
        return List.copyOf(result);
    }

    @Override
    public synchronized MaterializePrismSubjectSetResult materializeSubjectSet(MaterializePrismSubjectSetRequest request) {
        Objects.requireNonNull(request, "request");
        LoadedPrismDataset loaded = dataset(request.datasetId());
        String subjectSetId = request.subjectSetId() == null || request.subjectSetId().isBlank() ? null : request.subjectSetId().trim();
        if (subjectSetId != null && loaded.dataset().findSubjectSet(subjectSetId).isEmpty()) {
            throw new ChemOperationException("prism_subject_set_not_found", "Prism subject set " + subjectSetId + " does not exist.");
        }
        List<SubjectRecord> subjects = subjects(loaded, subjectSetId);
        String repositoryId = request.repositoryId() == null || request.repositoryId().isBlank()
                ? defaultRepositoryId(loaded.datasetId(), subjectSetId)
                : request.repositoryId().trim();
        String label = request.label() == null || request.label().isBlank()
                ? defaultRepositoryLabel(loaded, subjectSetId)
                : request.label().trim();

        repositories.createRepository(new CreateRepositoryRequest(repositoryId, label, prismRepositoryDescription(loaded, subjectSetId), true));

        int missingSmiles = 0;
        int invalidSmiles = 0;
        int structuresImported = 0;
        List<PrismSkippedSubject> skipped = new ArrayList<>();
        for (SubjectRecord subject : subjects) {
            if (subject.getSmiles() == null || subject.getSmiles().isBlank()) {
                missingSmiles++;
                skipped.add(new PrismSkippedSubject(subject.getSubjectId(), "missing_smiles", "Subject has no SMILES."));
                continue;
            }
            try {
                repositories.registerStructure(new RegisterStructureRequest(
                        subject.getSmiles(),
                        repositoryId,
                        subject.getSubjectId(),
                        subject.getSubjectId(),
                        subjectFields(loaded, subjectSetId, subject)
                ));
                structuresImported++;
            } catch (RuntimeException e) {
                invalidSmiles++;
                skipped.add(new PrismSkippedSubject(subject.getSubjectId(), "invalid_smiles", e.getMessage()));
            }
        }
        materializationsByRepositoryId.put(repositoryId, new MaterializationMapping(loaded.datasetId(), subjectSetId));
        return new MaterializePrismSubjectSetResult(
                loaded.datasetId(),
                subjectSetId,
                repositoryId,
                subjects.size(),
                structuresImported,
                missingSmiles,
                invalidSmiles,
                List.copyOf(skipped)
        );
    }

    private PrismDatasetSummary summary(LoadedPrismDataset loaded) {
        return new PrismDatasetSummary(
                loaded.datasetId(),
                loaded.label(),
                loaded.sourcePath().toString(),
                loaded.dataset().getSubjectRecords().size(),
                loaded.dataset().getSubjectSets().size(),
                loaded.dataset().getEndpointDefinitions().size(),
                loaded.dataset().getEndpointValues().size(),
                structureSubjectCount(loaded.dataset())
        );
    }

    private List<PrismSubjectSetSummary> subjectSets(LoadedPrismDataset loaded) {
        return loaded.dataset().getSubjectSets().stream()
                .map(set -> new PrismSubjectSetSummary(
                        set.getId(),
                        set.getName(),
                        set.getSetType(),
                        set.getSubjectSetScope(),
                        set.getParentSetId(),
                        set.getDescription(),
                        loaded.dataset().getSubjectsForSet(set.getId()).size()
                ))
                .toList();
    }

    private List<PrismEndpointSummary> endpoints(LoadedPrismDataset loaded) {
        return loaded.dataset().getEndpointDefinitions().stream()
                .map(this::endpointSummary)
                .toList();
    }

    private PrismEndpointSummary endpointSummary(EndpointDefinition endpoint) {
        NumericEndpointMeta numeric = endpoint.getNumericMeta();
        return new PrismEndpointSummary(
                endpoint.getId(),
                endpoint.getName(),
                endpoint.getPath(),
                endpoint.getDatatype().name(),
                endpoint.getEndpointType().name(),
                endpoint.getUnit(),
                endpoint.getEvaluationMode().name(),
                endpoint.getDescription(),
                numeric == null || numeric.getScale() == null ? null : numeric.getScale().name(),
                numeric == null ? null : numeric.getDomainLowerBound(),
                numeric == null ? null : numeric.getDomainUpperBound(),
                endpoint.getCategories().stream().map(CategoryDefinition::getId).toList()
        );
    }

    private List<SubjectRecord> subjects(LoadedPrismDataset loaded, String subjectSetId) {
        if (subjectSetId == null || subjectSetId.isBlank()) {
            return loaded.dataset().getSubjectRecords();
        }
        String normalizedSubjectSetId = subjectSetId.trim();
        if (loaded.dataset().findSubjectSet(normalizedSubjectSetId).isEmpty()) {
            throw new ChemOperationException("prism_subject_set_not_found", "Prism subject set " + normalizedSubjectSetId + " does not exist.");
        }
        return loaded.dataset().getSubjectsForSet(normalizedSubjectSetId).stream()
                .map(subjectId -> loaded.dataset().findSubjectRecord(subjectId).orElseThrow())
                .toList();
    }

    private static PrismSubjectSummary subjectSummary(SubjectRecord subject, boolean includeMetadata) {
        return new PrismSubjectSummary(
                subject.getSubjectId(),
                subject.getStructureId(),
                subject.getBatchId(),
                subject.getProject(),
                subject.getSeries(),
                subject.getSmiles() != null && !subject.getSmiles().isBlank(),
                includeMetadata ? subject.getMetadata() : Map.of()
        );
    }

    private static Map<String, String> subjectFields(LoadedPrismDataset loaded, String subjectSetId, SubjectRecord subject) {
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        put(fields, "prism.dataset_id", loaded.datasetId());
        put(fields, "prism.source_path", loaded.sourcePath().toString());
        put(fields, "prism.subject_id", subject.getSubjectId());
        put(fields, "prism.subject_set_id", subjectSetId);
        put(fields, "prism.structure_id", subject.getStructureId());
        put(fields, "prism.batch_id", subject.getBatchId());
        put(fields, "prism.project", subject.getProject());
        put(fields, "prism.series", subject.getSeries());
        for (Map.Entry<String, String> entry : subject.getMetadata().entrySet()) {
            put(fields, "prism.metadata." + entry.getKey(), entry.getValue());
        }
        return Map.copyOf(fields);
    }

    private static void put(Map<String, String> fields, String key, String value) {
        if (value != null && !value.isBlank()) {
            fields.put(key, value);
        }
    }

    private LoadedPrismDataset dataset(String datasetId) {
        LoadedPrismDataset loaded = datasets.get(normalizeId(datasetId, "datasetId"));
        if (loaded == null) {
            throw new ChemOperationException("prism_dataset_not_found", "Prism dataset " + datasetId + " does not exist.");
        }
        return loaded;
    }

    private String generatedDatasetId() {
        int index = datasets.size() + 1;
        while (datasets.containsKey("prism" + index)) {
            index++;
        }
        return "prism" + index;
    }

    private static String defaultRepositoryId(String datasetId, String subjectSetId) {
        return sanitizeRepositoryId("prism:" + datasetId + ":" + (subjectSetId == null ? "all" : subjectSetId));
    }

    private static String defaultRepositoryLabel(LoadedPrismDataset loaded, String subjectSetId) {
        if (subjectSetId == null) {
            return loaded.label() + " all structures";
        }
        return loaded.dataset().findSubjectSet(subjectSetId)
                .map(set -> loaded.label() + " / " + set.getName())
                .orElse(loaded.label() + " / " + subjectSetId);
    }

    private static String prismRepositoryDescription(LoadedPrismDataset loaded, String subjectSetId) {
        return "Materialized chemistry structures from Prism dataset " + loaded.datasetId()
                + (subjectSetId == null ? " (all subjects)." : " subject set " + subjectSetId + ".");
    }

    private static String sanitizeRepositoryId(String value) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '-' || ch == ':') {
                sb.append(ch);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    private static String normalizeId(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ChemOperationException("invalid_arguments", name + " must not be blank.");
        }
        return value.trim();
    }

    private static int structureSubjectCount(InMemoryPrismDataset dataset) {
        int count = 0;
        for (SubjectRecord subject : dataset.getSubjectRecords()) {
            if (subject.getSmiles() != null && !subject.getSmiles().isBlank()) {
                count++;
            }
        }
        return count;
    }

    private record LoadedPrismDataset(
            String datasetId,
            String label,
            Path sourcePath,
            InMemoryPrismDataset dataset,
            Instant openedAt
    ) {}

    private record MaterializationMapping(String datasetId, String subjectSetId) {}
}
