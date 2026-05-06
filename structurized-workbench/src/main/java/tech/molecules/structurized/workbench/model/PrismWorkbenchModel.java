package tech.molecules.structurized.workbench.model;

import tech.molecules.structurized.prism.model.EndpointDefinition;
import tech.molecules.structurized.prism.provider.SubjectSet;
import tech.molecules.structurized.prism.provider.inmemory.InMemoryPrismDataset;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable snapshot of the currently loaded PRISM repository in the workbench.
 */
public record PrismWorkbenchModel(
        String displayName,
        Path sourceDirectory,
        InMemoryPrismDataset dataset,
        String selectedEndpointId,
        String selectedSubjectSetId
) {
    public PrismWorkbenchModel {
        displayName = displayName == null || displayName.trim().isEmpty() ? "PRISM repository" : displayName.trim();
        dataset = Objects.requireNonNull(dataset, "dataset");
    }

    public static PrismWorkbenchModel of(Path sourceDirectory, InMemoryPrismDataset dataset) {
        String displayName = sourceDirectory == null ? "PRISM repository" : sourceDirectory.toString();
        return new PrismWorkbenchModel(displayName, sourceDirectory, dataset, null, null);
    }

    public static PrismWorkbenchModel of(PrismWorkbenchRepositorySnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return new PrismWorkbenchModel(snapshot.displayName(), null, snapshot.dataset(), null, null);
    }

    public PrismWorkbenchModel withSelectedEndpoint(String endpointId) {
        return new PrismWorkbenchModel(displayName, sourceDirectory, dataset, endpointId, selectedSubjectSetId);
    }

    public PrismWorkbenchModel withSelectedSubjectSet(String subjectSetId) {
        return new PrismWorkbenchModel(displayName, sourceDirectory, dataset, selectedEndpointId, subjectSetId);
    }

    public Optional<EndpointDefinition> selectedEndpoint() {
        return selectedEndpointId == null ? Optional.empty() : dataset.findEndpointDefinition(selectedEndpointId);
    }

    public Optional<SubjectSet> selectedSubjectSet() {
        return selectedSubjectSetId == null ? Optional.empty() : dataset.findSubjectSet(selectedSubjectSetId);
    }

    public List<String> selectedSubjectIds() {
        if (selectedSubjectSetId != null) {
            return dataset.getSubjectsForSet(selectedSubjectSetId);
        }
        return dataset.getSubjectRecords().stream().map(subject -> subject.getSubjectId()).toList();
    }
}
