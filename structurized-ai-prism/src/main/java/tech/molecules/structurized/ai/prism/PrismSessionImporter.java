package tech.molecules.structurized.ai.prism;

import tech.molecules.structurized.prism.engine.PrismRowSet;
import tech.molecules.structurized.prism.engine.PrismSession;
import tech.molecules.structurized.prism.model.EndpointDataType;
import tech.molecules.structurized.prism.model.EndpointDefinition;
import tech.molecules.structurized.prism.pack.PrismPack;
import tech.molecules.structurized.prism.provider.SubjectRecord;
import tech.molecules.structurized.prism.provider.SubjectSet;
import tech.molecules.structurized.prism.provider.inmemory.InMemoryPrismDataset;
import tech.molecules.structurized.prism.query.EndpointValueRecord;
import tech.molecules.structurized.prism.result.BooleanResult;
import tech.molecules.structurized.prism.result.CategoricalResult;
import tech.molecules.structurized.prism.result.EndpointResult;
import tech.molecules.structurized.prism.result.NumericResult;
import tech.molecules.structurized.prism.result.NumericState;
import tech.molecules.structurized.prism.result.OptionalNumericResult;
import tech.molecules.structurized.prism.result.OptionalNumericState;
import tech.molecules.structurized.prism.result.TextResult;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class PrismSessionImporter {
    private PrismSessionImporter() {}

    static PrismSession toSession(InMemoryPrismDataset dataset, Path sourcePath) {
        PrismSession session = PrismSession.from(toPack(dataset, sourcePath));
        for (SubjectSet subjectSet : dataset.getSubjectSets()) {
            LinkedHashSet<String> rowIds = new LinkedHashSet<>(dataset.getSubjectsForSet(subjectSet.getId()));
            if (!rowIds.isEmpty()) {
                session.addRowSet(new PrismRowSet(
                        subjectSet.getId(),
                        subjectSet.getName(),
                        subjectSet.getDescription(),
                        rowIds,
                        Map.of("source", "prism-tsv-subject-set")
                ));
            }
        }
        return session;
    }

    private static PrismPack toPack(InMemoryPrismDataset dataset, Path sourcePath) {
        List<EndpointDefinition> endpoints = dataset.getEndpointDefinitions();
        List<String> headers = headers(endpoints);
        ArrayList<List<String>> rows = new ArrayList<>();
        for (SubjectRecord subject : dataset.getSubjectRecords()) {
            rows.add(row(dataset, subject, endpoints));
        }
        PrismPack.DataFrame dataFrame = new PrismPack.DataFrame(headers, rows);
        PrismPack.DataFrameSchema schema = new PrismPack.DataFrameSchema(columns(endpoints), Map.of());
        PrismPack.MoleculeMetadata molecules = new PrismPack.MoleculeMetadata("smiles", "smiles", "subject_id", Map.of());
        PrismPack.EndpointMetadata endpointMetadata = new PrismPack.EndpointMetadata(endpoints.stream()
                .map(endpoint -> new PrismPack.Endpoint(
                        endpoint.getId(),
                        endpoint.getId(),
                        endpoint.getName(),
                        endpoint.getUnit(),
                        null,
                        endpoint.getPath(),
                        null,
                        Map.of()
                ))
                .toList(), Map.of());
        PrismPack.TableView tableView = new PrismPack.TableView(
                "default",
                "Imported dataset",
                headers,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of()
        );
        String sourceName = sourcePath == null ? "Imported PRISM TSV dataset" : sourcePath.getFileName().toString();
        return new PrismPack(
                new PrismPack.Manifest(
                        "0.1",
                        "structurized-prism-session",
                        sourceName,
                        "Imported from canonical PRISM TSV dataset",
                        Instant.now().toString(),
                        "Structurized MCP",
                        new PrismPack.DataframeRef("data", "dataframe.tsv", "schema.json", "subject", Map.of()),
                        "semantics/molecules.json",
                        "semantics/endpoints.json",
                        "views/table.json",
                        "views/visualizations.json",
                        "attachments/attachments.json",
                        "provenance.json",
                        Map.of()
                ),
                dataFrame,
                schema,
                molecules,
                endpointMetadata,
                tableView,
                new PrismPack.VisualizationSet(List.of(), Map.of()),
                new PrismPack.AttachmentSet(List.of(), Map.of()),
                Map.of("sourcePath", sourcePath == null ? "" : sourcePath.toString()),
                List.of()
        );
    }

    private static List<String> headers(List<EndpointDefinition> endpoints) {
        ArrayList<String> headers = new ArrayList<>(List.of("subject_id", "structure_id", "batch_id", "project", "series", "smiles"));
        endpoints.stream().map(EndpointDefinition::getId).forEach(headers::add);
        return List.copyOf(headers);
    }

    private static List<PrismPack.Column> columns(List<EndpointDefinition> endpoints) {
        ArrayList<PrismPack.Column> columns = new ArrayList<>();
        columns.add(new PrismPack.Column("subject_id", "text", "compound_id", "Subject ID", "identifier", null, null, null, null, Map.of()));
        columns.add(new PrismPack.Column("structure_id", "text", null, "Structure ID", null, null, null, null, null, Map.of()));
        columns.add(new PrismPack.Column("batch_id", "text", null, "Batch ID", null, null, null, null, null, Map.of()));
        columns.add(new PrismPack.Column("project", "text", "category", "Project", null, null, null, null, null, Map.of()));
        columns.add(new PrismPack.Column("series", "text", "category", "Series", null, null, null, null, null, Map.of()));
        columns.add(new PrismPack.Column("smiles", "text", "chemical_structure", "Structure", null, null, null, null, "smiles", Map.of()));
        for (EndpointDefinition endpoint : endpoints) {
            columns.add(new PrismPack.Column(
                    endpoint.getId(),
                    type(endpoint.getDatatype()),
                    semanticType(endpoint.getDatatype()),
                    endpoint.getName(),
                    null,
                    endpoint.getUnit(),
                    endpoint.getId(),
                    null,
                    null,
                    Map.of("endpointPath", endpoint.getPath())
            ));
        }
        return List.copyOf(columns);
    }

    private static String type(EndpointDataType datatype) {
        return switch (datatype) {
            case NUMERIC, OPTIONAL_NUMERIC -> "number";
            case BOOLEAN -> "boolean";
            case CATEGORICAL, TEXT -> "text";
        };
    }

    private static String semanticType(EndpointDataType datatype) {
        return datatype == EndpointDataType.CATEGORICAL ? "category" : null;
    }

    private static List<String> row(InMemoryPrismDataset dataset, SubjectRecord subject, List<EndpointDefinition> endpoints) {
        ArrayList<String> row = new ArrayList<>();
        row.add(value(subject.getSubjectId()));
        row.add(value(subject.getStructureId()));
        row.add(value(subject.getBatchId()));
        row.add(value(subject.getProject()));
        row.add(value(subject.getSeries()));
        row.add(value(subject.getSmiles()));
        for (EndpointDefinition endpoint : endpoints) {
            row.add(value(dataset.findEndpointValue(subject.getSubjectId(), endpoint.getId())
                    .map(EndpointValueRecord::getResult)
                    .map(PrismSessionImporter::displayValue)
                    .orElse(null)));
        }
        return List.copyOf(row);
    }

    private static String displayValue(EndpointResult result) {
        if (result instanceof NumericResult numeric) {
            return numeric.getState() == NumericState.VALUE ? string(numeric.getMean()) : null;
        }
        if (result instanceof OptionalNumericResult numeric) {
            return numeric.getState() == OptionalNumericState.VALUE ? string(numeric.getMean()) : null;
        }
        if (result instanceof BooleanResult bool) {
            return Boolean.toString(bool.getValue());
        }
        if (result instanceof CategoricalResult categorical) {
            return categorical.getValue();
        }
        if (result instanceof TextResult text) {
            return text.getText();
        }
        return result == null ? null : result.toString();
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static String string(Double value) {
        return value == null ? null : Double.toString(value);
    }
}
