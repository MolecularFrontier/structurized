# Fast AI Clustering

## Purpose

The fast clustering tool gives an agent a cheap first look at a compound set before proposing series
or decomposition rules. It is intentionally approximate. The goal is not final series assignment; the
goal is to quickly find representative neighborhoods worth inspecting with substructure search,
structure inspection, scaffold discovery, and series decomposition.

## Algorithm

The V1 implementation uses OpenChemLib `DescriptorHandlerSkeletonSpheres` byte descriptors and a
deterministic greedy leader strategy.

Default settings:

- descriptor: `skelspheres`
- strategy: `greedy_leaders`
- threshold: `0.80`
- maximum cross-cluster neighbors: `5`

The engine computes descriptors for all input molecules, builds the pairwise similarity matrix, then
repeatedly chooses a representative among unassigned molecules. The representative is the molecule
with the most unassigned neighbors above the threshold. Ties are resolved by mean neighbor
similarity, heavy atom count, and input order. All unassigned molecules with representative
similarity greater than or equal to the threshold are assigned to that cluster.

This behavior is deliberately representative-led rather than connected-component based. One bridging
compound therefore does not automatically merge two neighboring chemical series.

## Result model

`SimilarityClusteringResult` contains:

- descriptor and strategy metadata
- threshold
- molecule count
- cluster count
- singleton count
- unclustered descriptor-failure count
- ordered clusters
- unclustered molecules

Each `SimilarityCluster` contains:

- deterministic ID such as `cluster_1`
- representative structure ID and label
- members with similarity to the representative
- nearest cross-cluster neighbors, useful for finding borderline or related clusters

Descriptor failures are kept as explicit unclustered records with reason `DESCRIPTOR_FAILED`.

## MCP usage

The clustering MCP tools operate on normal AI chemistry repositories. Repositories can be populated
by direct SMILES registration or by materializing a Prism subject set.

Direct session example:

```json
register_structure({
  "smiles": "c1ccccc1",
  "structure_id": "benzene_a",
  "label": "Benzene A"
})
```

```json
cluster_structures({
  "clustering_id": "rough1",
  "repository_id": "session",
  "threshold": 0.80,
  "max_cross_neighbors": 5
})
```

Prism-backed example:

```json
open_prism_dataset({
  "path": "/path/to/prism-tsv",
  "dataset_id": "demo"
})
```

```json
materialize_prism_subject_set({
  "dataset_id": "demo",
  "subject_set_id": "series:Kinase:A"
})
```

```json
cluster_structures({
  "repository_id": "prism:demo:series:Kinase:A",
  "threshold": 0.80
})
```

Follow-up tools:

- `list_clusterings`: list stored clustering runs.
- `get_clustering`: fetch summary and paged cluster summaries.
- `get_cluster`: fetch one full cluster with all members and nearest cross-cluster neighbors.

A useful agent workflow is:

1. Run `cluster_structures` at `0.80`.
2. Inspect the largest representatives with `inspect_structure`.
3. Use `get_cluster` to inspect members and cross-cluster neighbors.
4. Use `search_substructure` to test candidate series SMARTS.
5. Create and evaluate a series decomposition config on selected clusters or the full repository.

## Java entry points

Core engine:

- `tech.molecules.structurized.clustering.SimilarityClusteringEngine`
- `tech.molecules.structurized.clustering.SimilarityClusteringConfig`
- `tech.molecules.structurized.clustering.SimilarityClusteringResult`

AI service:

- `tech.molecules.structurized.ai.clustering.SimilarityClusteringAiService`

## Current limitations

1. V1 supports only `skelspheres` descriptors.
2. Runtime is quadratic in the number of clustered molecules because all pairwise similarities are
   computed.
3. Clustering results are session-scoped and in-memory in the MCP adapter.
4. The clusters are exploratory neighborhoods, not authoritative chemical series assignments.
