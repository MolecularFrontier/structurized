package tech.molecules.structurized.ai.prism;

import tech.molecules.structurized.ai.model.ChemOperationException;
import tech.molecules.structurized.prism.engine.PrismColumn;
import tech.molecules.structurized.prism.engine.PrismColumnSchema;
import tech.molecules.structurized.prism.engine.PrismColumnType;
import tech.molecules.structurized.prism.engine.PrismOperationResult;
import tech.molecules.structurized.prism.engine.PrismRowSet;
import tech.molecules.structurized.prism.engine.RowIdMaterializedColumnData;
import tech.molecules.structurized.prism.prediction.PredictionCapability;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class PrismPredictionService {
    private static final int PAGE_LIMIT_DEFAULT = 100;
    private static final int PAGE_LIMIT_MAX = 500;

    private final PrismArtifactRegistry artifacts;
    private final PredictionRegistry registry;

    PrismPredictionService(PrismArtifactRegistry artifacts, PredictionRegistry registry) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    List<PredictionCapability> listCapabilities(ManagedPrismSession session,
                                                List<PrismEndpointSummary> endpoints,
                                                String endpointId) {
        List<PredictionCapability> capabilities = mergedCapabilities(session, endpoints);
        if (endpointId == null || endpointId.isBlank()) {
            return capabilities;
        }
        String normalized = endpointId.trim();
        return capabilities.stream()
                .filter(capability -> normalized.equals(capability.endpointId())
                        || normalized.equals(capability.predictedEndpointId()))
                .toList();
    }

    PredictionCapability describeCapability(ManagedPrismSession session,
                                            List<PrismEndpointSummary> endpoints,
                                            String capabilityId) {
        String normalized = requireText(capabilityId, "capabilityId");
        return mergedCapabilities(session, endpoints).stream()
                .filter(capability -> capability.capabilityId().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new ChemOperationException(
                        "prediction_capability_not_found",
                        "Prediction capability " + normalized + " does not exist."
                ));
    }


    PredictionRunSummary evaluate(ManagedPrismSession session,
                                  List<PrismEndpointSummary> endpoints,
                                  PrismRowSet sourceRowSet,
                                  PrismRowSetStructureCollection collection,
                                  EvaluatePrismPredictionRequest request) {
        String endpointId = requireText(request.endpointId(), "endpointId");
        String mode = normalizeMode(request.mode());
        PredictionContext context = context(session, endpoints);
        PredictionCapability capability = resolveCapability(session, endpoints, endpointId, request.capabilityId());
        String outputEndpointId = capability.predictedEndpointId();
        String analysisId = resolveAnalysisId(session, request.predictionRunId());
        String label = request.label() == null || request.label().isBlank()
                ? analysisId
                : request.label().trim();
        long sourceRevision = session.revision();

        ArrayList<PredictionInput> inputs = new ArrayList<>();
        for (PrismRowStructureEntry entry : collection.structures()) {
            if ("MISSING_ONLY".equals(mode) && !rowNeedsPrediction(session, entry.rowId(), endpointId, outputEndpointId)) {
                continue;
            }
            inputs.add(new PredictionInput(
                    entry.rowId(),
                    entry.smiles(),
                    Map.of(
                            "subjectId", valueOrEmpty(entry.subjectId()),
                            "structureId", valueOrEmpty(entry.structureId()),
                            "fields", entry.fields()
                    )
            ));
        }
        if (inputs.isEmpty()) {
            throw new ChemOperationException(
                    "no_predictable_prism_rows",
                    "Prism row set " + sourceRowSet.id() + " contains no rows requiring prediction."
            );
        }

        PredictionRequest providerRequest = new PredictionRequest(
                context,
                capability,
                endpointId,
                inputs,
                Map.of(
                        "mode", mode,
                        "sourceRowSetId", sourceRowSet.id(),
                        "analysisId", analysisId
                )
        );
        List<PredictionValue> values = registry.evaluate(providerRequest);
        List<String> publishedColumnIds = publishedColumnIds(analysisId, outputEndpointId, values, request);
        PrismAnalysisSummary analysis = new PrismAnalysisSummary(
                session.sessionId(),
                analysisId,
                "prediction_run",
                label,
                sourceRowSet.id(),
                sourceRevision,
                sourceRevision + 1,
                Instant.now().toString(),
                publishedColumnIds,
                Map.of(
                        "capabilityId", capability.capabilityId(),
                        "providerId", capability.providerId(),
                        "workflowId", capability.workflowId(),
                        "workflowVersion", capability.workflowVersion(),
                        "endpointId", endpointId,
                        "predictedEndpointId", outputEndpointId,
                        "mode", mode,
                        "inputCount", inputs.size(),
                        "valueCount", values.size()
                )
        );
        PredictionRun run = new PredictionRun(analysis, capability, inputs, values, providerRequest.options());
        PrismOperationResult publication = publication(session, sourceRowSet, analysisId, label, capability, outputEndpointId, values, request);
        session.runAs(ManagedPrismSessionChangeOrigin.MCP, () -> {
            session.workspace().applyOperationResult(publication);
            artifacts.add(session.sessionId(), run);
        });
        return summary(run);
    }


    PredictionRunView getRun(ManagedPrismSession session, String predictionRunId, int offset, int limit) {
        PredictionRun run = artifacts.require(
                session.sessionId(),
                requireText(predictionRunId, "predictionRunId"),
                PredictionRun.class
        );
        int safeOffset = Math.min(Math.max(0, offset), run.values().size());
        int safeLimit = safeLimit(limit);
        int to = Math.min(safeOffset + safeLimit, run.values().size());
        return new PredictionRunView(
                summary(run),
                run.capability(),
                run.values().size(),
                safeOffset,
                safeLimit,
                run.values().subList(safeOffset, to)
        );
    }

    private PredictionCapability resolveCapability(ManagedPrismSession session,
                                                   List<PrismEndpointSummary> endpoints,
                                                   String endpointId,
                                                   String requestedCapabilityId) {
        List<PredictionCapability> capabilities = listCapabilities(session, endpoints, endpointId);
        if (requestedCapabilityId != null && !requestedCapabilityId.isBlank()) {
            String normalized = requestedCapabilityId.trim();
            return mergedCapabilities(session, endpoints).stream()
                    .filter(capability -> capability.capabilityId().equals(normalized)
                            || capability.workflowId().equals(normalized))
                    .findFirst()
                    .orElseThrow(() -> new ChemOperationException(
                            "prediction_capability_not_found",
                            "Prediction capability " + normalized + " does not exist."
                    ));
        }
        return capabilities.stream()
                .findFirst()
                .orElseThrow(() -> new ChemOperationException(
                        "prediction_capability_not_found",
                        "No prediction capability is available for endpoint " + endpointId + "."
                ));
    }

    private List<PredictionCapability> mergedCapabilities(ManagedPrismSession session, List<PrismEndpointSummary> endpoints) {
        PredictionContext context = context(session, endpoints);
        LinkedHashMap<String, PredictionCapability> merged = new LinkedHashMap<>();
        for (PredictionCapability capability : session.workspace().predictionCapabilities()) {
            merged.put(capability.capabilityId(), capability);
        }
        for (PredictionCapability capability : registry.capabilities(context)) {
            merged.putIfAbsent(capability.capabilityId(), capability);
        }
        return merged.values().stream()
                .sorted(Comparator.comparingInt(PredictionCapability::priority).reversed()
                        .thenComparing(PredictionCapability::capabilityId))
                .toList();
    }

    private PrismOperationResult publication(ManagedPrismSession session,
                                             PrismRowSet sourceRowSet,
                                             String analysisId,
                                             String label,
                                             PredictionCapability capability,
                                             String outputEndpointId,
                                             List<PredictionValue> values,
                                             EvaluatePrismPredictionRequest request) {
        PrismOperationResult.Builder builder = PrismOperationResult.builder()
                .provenance("analysisId", analysisId)
                .provenance("analysisType", "prediction_run")
                .provenance("capabilityId", capability.capabilityId())
                .provenance("providerId", capability.providerId())
                .provenance("workflowId", capability.workflowId())
                .provenance("workflowVersion", capability.workflowVersion());
        Map<String, Object> provenance = Map.of(
                "source", "structurized_prediction",
                "sessionId", session.sessionId(),
                "analysisId", analysisId,
                "sourceRowSetId", sourceRowSet.id(),
                "capabilityId", capability.capabilityId(),
                "providerId", capability.providerId(),
                "workflowId", capability.workflowId(),
                "workflowVersion", capability.workflowVersion(),
                "endpointId", request.endpointId(),
                "predictedEndpointId", outputEndpointId
        );
        String base = analysisId + "." + sanitizeId(outputEndpointId);
        List<PredictionValue> endpointValues = values.stream()
                .filter(value -> value.endpointId().equals(outputEndpointId))
                .toList();
        if (publishValue(request)) {
            builder.addColumnByRowId(new RowIdMaterializedColumnData(
                    new PrismColumnSchema(
                            base + ".prediction",
                            valueColumnType(endpointValues),
                            label + " " + outputEndpointId + " prediction",
                            "prediction_value",
                            "analysis_result",
                            unit(capability),
                            outputEndpointId,
                            direction(capability, outputEndpointId),
                            null,
                            provenance
                    ),
                    valueMap(endpointValues),
                    provenance
            ));
        }
        if (publishStatus(request)) {
            builder.addColumnByRowId(new RowIdMaterializedColumnData(
                    new PrismColumnSchema(
                            base + ".status",
                            PrismColumnType.CATEGORICAL,
                            label + " " + outputEndpointId + " status",
                            "prediction_status",
                            "analysis_result",
                            null,
                            outputEndpointId,
                            null,
                            null,
                            provenance
                    ),
                    statusMap(endpointValues),
                    provenance
            ));
        }
        if (publishUncertainty(request)) {
            builder.addColumnByRowId(new RowIdMaterializedColumnData(
                    new PrismColumnSchema(
                            base + ".uncertainty",
                            PrismColumnType.NUMERIC,
                            label + " " + outputEndpointId + " uncertainty",
                            "prediction_uncertainty",
                            "analysis_result",
                            unit(capability),
                            outputEndpointId,
                            "lower_is_better",
                            null,
                            provenance
                    ),
                    uncertaintyMap(endpointValues),
                    provenance
            ));
        }
        if (publishApplicability(request)) {
            builder.addColumnByRowId(new RowIdMaterializedColumnData(
                    new PrismColumnSchema(
                            base + ".applicability",
                            PrismColumnType.NUMERIC,
                            label + " " + outputEndpointId + " applicability",
                            "prediction_applicability",
                            "analysis_result",
                            null,
                            outputEndpointId,
                            "higher_is_better",
                            null,
                            provenance
                    ),
                    applicabilityMap(endpointValues),
                    provenance
            ));
        }
        Set<String> failures = rowIds(values, Set.of(
                PredictionStatus.INVALID_INPUT,
                PredictionStatus.MISSING_FEATURES,
                PredictionStatus.MODEL_ERROR
        ));
        if (!failures.isEmpty()) {
            builder.addRowSet(new PrismRowSet(
                    analysisId + ".failures",
                    label + " failures",
                    "Rows for which prediction failed.",
                    failures,
                    provenance
            ));
        }
        Set<String> outOfDomain = rowIds(values, Set.of(PredictionStatus.OUT_OF_DOMAIN));
        if (!outOfDomain.isEmpty()) {
            builder.addRowSet(new PrismRowSet(
                    analysisId + ".out_of_domain",
                    label + " outside applicability domain",
                    "Rows predicted outside the model applicability domain.",
                    outOfDomain,
                    provenance
            ));
        }
        return builder.build();
    }

    private boolean rowNeedsPrediction(ManagedPrismSession session, String rowId, String endpointId, String outputEndpointId) {
        int physicalRow = session.workspace().physicalRowForRowId(rowId)
                .orElseThrow(() -> new ChemOperationException("prism_row_not_found", "Prism row " + rowId + " does not exist."));
        return !hasEndpointValue(session, physicalRow, endpointId)
                && !hasEndpointValue(session, physicalRow, outputEndpointId);
    }

    private boolean hasEndpointValue(ManagedPrismSession session, int physicalRow, String endpointId) {
        if (endpointId == null || endpointId.isBlank()) {
            return false;
        }
        for (PrismColumn column : session.workspace().table().columns()) {
            PrismColumnSchema schema = column.schema();
            boolean matches = endpointId.equals(schema.endpointId()) || endpointId.equals(schema.id());
            if (matches && !column.isMissing(physicalRow)) {
                Object value = column.valueAt(physicalRow);
                if (value != null && !value.toString().isBlank()) {
                    return true;
                }
            }
        }
        return false;
    }


    private static PredictionContext context(ManagedPrismSession session, List<PrismEndpointSummary> endpoints) {
        return new PredictionContext(
                session.sessionId(),
                null,
                endpoints,
                Map.of(
                        "sourcePath", session.sourcePath().toString(),
                        "revision", session.revision()
                )
        );
    }

    private String resolveAnalysisId(ManagedPrismSession session, String requestedId) {
        if (requestedId != null && !requestedId.isBlank()) {
            String id = requestedId.trim();
            if (artifacts.contains(session.sessionId(), id) || session.workspace().table().findColumn(id + ".prediction").isPresent()) {
                throw new ChemOperationException(
                        "duplicate_prediction_run",
                        "Prediction run " + id + " already exists."
                );
            }
            return id;
        }
        int index = 1;
        String candidate;
        do {
            candidate = "prediction_" + index++;
        } while (artifacts.contains(session.sessionId(), candidate));
        return candidate;
    }

    private PredictionRunSummary summary(PredictionRun run) {
        int success = 0;
        int outOfDomain = 0;
        int failures = 0;
        for (PredictionValue value : run.values()) {
            if (value.status() == PredictionStatus.SUCCESS) {
                success++;
            } else if (value.status() == PredictionStatus.OUT_OF_DOMAIN) {
                outOfDomain++;
            } else {
                failures++;
            }
        }
        return new PredictionRunSummary(
                run.summary(),
                run.capability().capabilityId(),
                run.capability().providerId(),
                run.capability().workflowId(),
                run.capability().workflowVersion(),
                run.inputs().size(),
                run.values().size(),
                success,
                outOfDomain,
                failures,
                run.summary().publishedColumnIds()
        );
    }

    private static List<String> publishedColumnIds(String analysisId,
                                                   String outputEndpointId,
                                                   List<PredictionValue> values,
                                                   EvaluatePrismPredictionRequest request) {
        ArrayList<String> ids = new ArrayList<>();
        String base = analysisId + "." + sanitizeId(outputEndpointId);
        if (publishValue(request)) {
            ids.add(base + ".prediction");
        }
        if (publishStatus(request)) {
            ids.add(base + ".status");
        }
        if (publishUncertainty(request) && values.stream().anyMatch(value -> value.uncertainty() != null)) {
            ids.add(base + ".uncertainty");
        } else if (publishUncertainty(request)) {
            ids.add(base + ".uncertainty");
        }
        if (publishApplicability(request) && values.stream().anyMatch(value -> value.applicability() != null)) {
            ids.add(base + ".applicability");
        } else if (publishApplicability(request)) {
            ids.add(base + ".applicability");
        }
        return List.copyOf(ids);
    }

    private LinkedHashMap<String, Object> valueMap(List<PredictionValue> values) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (PredictionValue value : values) {
            if (value.value() != null) {
                map.put(value.inputId(), value.value());
            }
        }
        return map;
    }

    private LinkedHashMap<String, String> statusMap(List<PredictionValue> values) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        for (PredictionValue value : values) {
            map.put(value.inputId(), value.status().name());
        }
        return map;
    }

    private LinkedHashMap<String, Double> uncertaintyMap(List<PredictionValue> values) {
        LinkedHashMap<String, Double> map = new LinkedHashMap<>();
        for (PredictionValue value : values) {
            if (value.uncertainty() != null) {
                map.put(value.inputId(), value.uncertainty());
            }
        }
        return map;
    }

    private LinkedHashMap<String, Double> applicabilityMap(List<PredictionValue> values) {
        LinkedHashMap<String, Double> map = new LinkedHashMap<>();
        for (PredictionValue value : values) {
            if (value.applicability() != null) {
                map.put(value.inputId(), value.applicability());
            }
        }
        return map;
    }

    private static Set<String> rowIds(List<PredictionValue> values, Set<PredictionStatus> statuses) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (PredictionValue value : values) {
            if (statuses.contains(value.status())) {
                ids.add(value.inputId());
            }
        }
        return ids;
    }

    private static PrismColumnType valueColumnType(List<PredictionValue> values) {
        for (PredictionValue value : values) {
            if (value.value() instanceof Number) {
                return PrismColumnType.NUMERIC;
            }
        }
        return PrismColumnType.TEXT;
    }


    private static String unit(PredictionCapability capability) {
        return stringMetadata(capability, "unit");
    }

    private static String direction(PredictionCapability capability, String endpointId) {
        String endpointSpecific = stringMetadata(capability, "direction." + endpointId);
        return endpointSpecific == null ? stringMetadata(capability, "direction") : endpointSpecific;
    }


    private static String stringMetadata(PredictionCapability capability, String key) {
        Object value = capability.metadata().get(key);
        return value == null || value.toString().isBlank() ? null : value.toString();
    }


    private static boolean publishValue(EvaluatePrismPredictionRequest request) {
        return request.publishValue() == null || request.publishValue();
    }

    private static boolean publishStatus(EvaluatePrismPredictionRequest request) {
        return request.publishStatus() == null || request.publishStatus();
    }

    private static boolean publishUncertainty(EvaluatePrismPredictionRequest request) {
        return request.publishUncertainty() == null || request.publishUncertainty();
    }

    private static boolean publishApplicability(EvaluatePrismPredictionRequest request) {
        return request.publishApplicability() == null || request.publishApplicability();
    }

    private static String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return "MISSING_ONLY";
        }
        String normalized = mode.trim().toUpperCase(Locale.ROOT);
        if (!List.of("MISSING_ONLY", "ALL").contains(normalized)) {
            throw new ChemOperationException("invalid_prediction_mode", "Prediction mode must be MISSING_ONLY or ALL.");
        }
        return normalized;
    }

    private static String sanitizeId(String value) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '-') {
                sb.append(ch);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    private static int safeLimit(int limit) {
        if (limit < 1) {
            return PAGE_LIMIT_DEFAULT;
        }
        return Math.min(limit, PAGE_LIMIT_MAX);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ChemOperationException("invalid_arguments", name + " must not be blank.");
        }
        return value.trim();
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
