package tech.molecules.structurized.workbench.model;

import tech.molecules.structurized.prism.model.EndpointDefinition;
import tech.molecules.structurized.prism.provider.inmemory.InMemoryPrismDataset;
import tech.molecules.structurized.prism.query.EndpointValueRecord;
import tech.molecules.structurized.prism.result.NumericResult;
import tech.molecules.structurized.prism.result.NumericState;
import tech.molecules.structurized.prism.result.OptionalNumericResult;
import tech.molecules.structurized.prism.result.OptionalNumericState;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Extracts basic QC and descriptive statistics for one numeric PRISM endpoint.
 */
public final class NumericEndpointAnalysis {
    private final EndpointDefinition endpoint;
    private final String subjectSetId;
    private final List<String> subjectIds;
    private final List<ValueRow> valueRows;
    private final int notMeasuredCount;
    private final int missingRecordCount;
    private final Summary summary;

    private NumericEndpointAnalysis(
            EndpointDefinition endpoint,
            String subjectSetId,
            List<String> subjectIds,
            List<ValueRow> valueRows,
            int notMeasuredCount,
            int missingRecordCount
    ) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.subjectSetId = subjectSetId;
        this.subjectIds = List.copyOf(subjectIds);
        this.valueRows = List.copyOf(valueRows);
        this.notMeasuredCount = notMeasuredCount;
        this.missingRecordCount = missingRecordCount;
        this.summary = Summary.from(valueRows);
    }

    public static NumericEndpointAnalysis analyze(
            InMemoryPrismDataset dataset,
            EndpointDefinition endpoint,
            String subjectSetId,
            List<String> subjectIds
    ) {
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(endpoint, "endpoint");
        List<String> subjects = List.copyOf(subjectIds == null ? List.of() : subjectIds);
        ArrayList<ValueRow> values = new ArrayList<>();
        int notMeasured = 0;
        int missing = 0;

        for (String subjectId : subjects) {
            Optional<EndpointValueRecord> record = dataset.findEndpointValue(subjectId, endpoint.getId());
            if (record.isEmpty()) {
                missing++;
                continue;
            }
            ExtractedNumeric extracted = extract(record.get());
            if (extracted.mean() == null) {
                notMeasured++;
                continue;
            }
            values.add(new ValueRow(
                    subjectId,
                    extracted.mean(),
                    extracted.lower(),
                    extracted.upper(),
                    extracted.n(),
                    extracted.rawValueCount(),
                    extracted.rawValueIdCount(),
                    extracted.firstMeasurement(),
                    extracted.lastMeasurement(),
                    parseInstant(extracted.firstMeasurement()).orElse(null),
                    parseInstant(extracted.lastMeasurement()).orElse(null)
            ));
        }
        values.sort(Comparator.comparing(ValueRow::subjectId));
        return new NumericEndpointAnalysis(endpoint, subjectSetId, subjects, values, notMeasured, missing);
    }

    public EndpointDefinition endpoint() {
        return endpoint;
    }

    public String subjectSetId() {
        return subjectSetId;
    }

    public List<String> subjectIds() {
        return subjectIds;
    }

    public List<ValueRow> valueRows() {
        return valueRows;
    }

    public int subjectCount() {
        return subjectIds.size();
    }

    public int measuredCount() {
        return valueRows.size();
    }

    public int notMeasuredCount() {
        return notMeasuredCount;
    }

    public int missingRecordCount() {
        return missingRecordCount;
    }

    public Summary summary() {
        return summary;
    }

    public Map<String, Object> summaryMap() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("Subjects", subjectCount());
        map.put("Measured", measuredCount());
        map.put("Not measured", notMeasuredCount);
        map.put("Missing records", missingRecordCount);
        map.put("Mean", summary.mean());
        map.put("Median", summary.median());
        map.put("StdDev", summary.standardDeviation());
        map.put("Min", summary.min());
        map.put("Max", summary.max());
        map.put("Mean n", summary.meanN());
        map.put("Mean raw values", summary.meanRawValueCount());
        return Map.copyOf(map);
    }

    private static ExtractedNumeric extract(EndpointValueRecord record) {
        if (record.getResult() instanceof NumericResult numeric) {
            return new ExtractedNumeric(
                    numeric.getState() == NumericState.VALUE ? numeric.getMean() : null,
                    numeric.getLower(),
                    numeric.getUpper(),
                    numeric.getN(),
                    numeric.getRawValues().size(),
                    numeric.getRawValueIds().size(),
                    numeric.getFirstMeasurement(),
                    numeric.getLastMeasurement()
            );
        }
        if (record.getResult() instanceof OptionalNumericResult numeric) {
            return new ExtractedNumeric(
                    numeric.getState() == OptionalNumericState.VALUE ? numeric.getMean() : null,
                    numeric.getLower(),
                    numeric.getUpper(),
                    numeric.getN(),
                    numeric.getRawValues().size(),
                    numeric.getRawValueIds().size(),
                    numeric.getFirstMeasurement(),
                    numeric.getLastMeasurement()
            );
        }
        return new ExtractedNumeric(null, null, null, null, 0, 0, null, null);
    }

    private static Optional<Instant> parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(value));
        } catch (DateTimeParseException ignored) {
            try {
                return Optional.of(LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC));
            } catch (DateTimeParseException ignoredAgain) {
                return Optional.empty();
            }
        }
    }

    private record ExtractedNumeric(
            Double mean,
            Double lower,
            Double upper,
            Integer n,
            int rawValueCount,
            int rawValueIdCount,
            String firstMeasurement,
            String lastMeasurement
    ) {
    }

    public record ValueRow(
            String subjectId,
            double mean,
            Double lower,
            Double upper,
            Integer n,
            int rawValueCount,
            int rawValueIdCount,
            String firstMeasurement,
            String lastMeasurement,
            Instant firstMeasurementInstant,
            Instant lastMeasurementInstant
    ) {
    }

    public record Summary(
            int count,
            double mean,
            double median,
            double standardDeviation,
            double min,
            double max,
            double meanN,
            double meanRawValueCount
    ) {
        private static Summary from(List<ValueRow> values) {
            if (values.isEmpty()) {
                return new Summary(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
            }
            List<Double> sorted = values.stream().map(ValueRow::mean).sorted().toList();
            double mean = sorted.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
            double variance = sorted.stream()
                    .mapToDouble(value -> {
                        double centered = value - mean;
                        return centered * centered;
                    })
                    .average()
                    .orElse(Double.NaN);
            double meanN = values.stream()
                    .filter(row -> row.n() != null)
                    .mapToInt(ValueRow::n)
                    .average()
                    .orElse(Double.NaN);
            double meanRaw = values.stream().mapToInt(ValueRow::rawValueCount).average().orElse(Double.NaN);
            return new Summary(
                    sorted.size(),
                    mean,
                    median(sorted),
                    Math.sqrt(variance),
                    sorted.getFirst(),
                    sorted.getLast(),
                    meanN,
                    meanRaw
            );
        }

        private static double median(List<Double> sorted) {
            int mid = sorted.size() / 2;
            if (sorted.size() % 2 == 1) {
                return sorted.get(mid);
            }
            return (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
        }
    }
}
