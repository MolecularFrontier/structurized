package tech.molecules.structurized.prismlite.app;

import tech.molecules.structurized.prism.engine.PrismViewSpec;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

record ChemFlowProjectRiverViewSpec(
        String viewId,
        String title,
        String graphId,
        String rowSetId,
        String structureColumnId,
        String dateColumnId,
        List<String> labelColumnIds,
        double minParentScore,
        double xSpacing,
        double laneSpacing,
        int timeBatchSize,
        double nodeScale,
        ProjectRiverNodeColorMode nodeColorMode,
        String colorColumnId
) implements PrismViewSpec {
    static final String VIEW_TYPE = "chemflow.project_river";

    ChemFlowProjectRiverViewSpec(
            String viewId,
            String title,
            String graphId,
            String rowSetId,
            String structureColumnId,
            String dateColumnId,
            List<String> labelColumnIds
    ) {
        this(viewId, title, graphId, rowSetId, structureColumnId, dateColumnId, labelColumnIds, 0.0, 150.0, 48.0, 25, 1.0);
    }

    ChemFlowProjectRiverViewSpec(
            String viewId,
            String title,
            String graphId,
            String rowSetId,
            String structureColumnId,
            String dateColumnId,
            List<String> labelColumnIds,
            double minParentScore,
            double xSpacing,
            double laneSpacing
    ) {
        this(viewId, title, graphId, rowSetId, structureColumnId, dateColumnId, labelColumnIds, minParentScore, xSpacing, laneSpacing, 25, 1.0);
    }

    ChemFlowProjectRiverViewSpec(
            String viewId,
            String title,
            String graphId,
            String rowSetId,
            String structureColumnId,
            String dateColumnId,
            List<String> labelColumnIds,
            double minParentScore,
            double xSpacing,
            double laneSpacing,
            int timeBatchSize
    ) {
        this(viewId, title, graphId, rowSetId, structureColumnId, dateColumnId, labelColumnIds,
                minParentScore, xSpacing, laneSpacing, timeBatchSize, 1.0);
    }

    ChemFlowProjectRiverViewSpec(
            String viewId,
            String title,
            String graphId,
            String rowSetId,
            String structureColumnId,
            String dateColumnId,
            List<String> labelColumnIds,
            double minParentScore,
            double xSpacing,
            double laneSpacing,
            int timeBatchSize,
            double nodeScale
    ) {
        this(viewId, title, graphId, rowSetId, structureColumnId, dateColumnId, labelColumnIds,
                minParentScore, xSpacing, laneSpacing, timeBatchSize, nodeScale,
                ProjectRiverNodeColorMode.ROOT_LINEAGE, null);
    }

    ChemFlowProjectRiverViewSpec {
        if (viewId == null || viewId.isBlank()) throw new IllegalArgumentException("view id must not be blank");
        if (graphId == null || graphId.isBlank()) throw new IllegalArgumentException("graph id must not be blank");
        if (rowSetId == null || rowSetId.isBlank()) throw new IllegalArgumentException("row set id must not be blank");
        if (structureColumnId == null || structureColumnId.isBlank()) throw new IllegalArgumentException("structure column id must not be blank");
        viewId = viewId.trim();
        title = title == null || title.isBlank() ? "Project River" : title.trim();
        graphId = graphId.trim();
        rowSetId = rowSetId.trim();
        structureColumnId = structureColumnId.trim();
        dateColumnId = dateColumnId == null || dateColumnId.isBlank() ? null : dateColumnId.trim();
        labelColumnIds = labelColumnIds == null ? List.of() : labelColumnIds.stream()
                .filter(column -> column != null && !column.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        minParentScore = Double.isNaN(minParentScore) ? 0.0 : Math.max(0.0, minParentScore);
        xSpacing = xSpacing <= 0.0 ? 150.0 : xSpacing;
        laneSpacing = laneSpacing <= 0.0 ? 48.0 : laneSpacing;
        timeBatchSize = timeBatchSize < 1 ? 25 : timeBatchSize;
        nodeScale = Double.isNaN(nodeScale) ? 1.0 : Math.max(0.45, Math.min(4.0, nodeScale));
        nodeColorMode = nodeColorMode == null ? ProjectRiverNodeColorMode.ROOT_LINEAGE : nodeColorMode;
        colorColumnId = colorColumnId == null || colorColumnId.isBlank() ? null : colorColumnId.trim();
        if (!nodeColorMode.usesColumn()) colorColumnId = null;
    }

    @Override
    public String viewType() {
        return VIEW_TYPE;
    }

    @Override
    public Set<String> referencedRowSetIds() {
        return Set.of(rowSetId);
    }

    @Override
    public Set<String> referencedColumnIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        ids.add(structureColumnId);
        if (dateColumnId != null) ids.add(dateColumnId);
        ids.addAll(labelColumnIds);
        if (colorColumnId != null) ids.add(colorColumnId);
        return Set.copyOf(new ArrayList<>(ids));
    }
}

enum ProjectRiverNodeColorMode {
    ROOT_LINEAGE("Root lineage", false),
    GRAPH_COMPONENT("Graph component", false),
    NUMERIC_COLUMN("Numeric column", true),
    CATEGORICAL_COLUMN("Categorical column", true);

    private final String label;
    private final boolean usesColumn;

    ProjectRiverNodeColorMode(String label, boolean usesColumn) {
        this.label = label;
        this.usesColumn = usesColumn;
    }

    boolean usesColumn() {
        return usesColumn;
    }

    @Override
    public String toString() {
        return label;
    }
}
