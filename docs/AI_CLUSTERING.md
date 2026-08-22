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
open_prism_snapshot({
  "path": "/path/to/prism-tsv",
  "session_id": "demo"
})
```

```json
materialize_prism_row_set({
  "session_id": "demo",
  "row_set_id": "series:Kinase:A"
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
- `get_clustering`: fetch compact, paged cluster summaries. Each row has size,
  representative ID/SMILES, nearest cross-cluster neighbors, and a few example members. Full
  member lists are deliberately omitted.
- `get_cluster`: fetch one compact cluster summary.
- `get_cluster_members`: drill into one cluster with `offset` and `limit`. Set
  `create_selection:true` to store the complete cluster as a server-side selection handle, or
  `output_target:"file"` to write larger member lists to a managed artifact.
- `summarize_clusters_by_endpoint`: compute numeric Prism endpoint statistics per cluster without
  returning member IDs. Defaults to `include_singletons:false`, `offset:0`, and `limit:50` so the
  response is a compact non-singleton cluster page. Use `include_singletons:true` only when auditing
  singleton behavior, and `output_target:"file"` for the full filtered table.

Example endpoint summary:

```json
summarize_clusters_by_endpoint({
  "clustering_id": "rough1",
  "dataset_id": "demo",
  "endpoint_id": "pIC50",
  "include_singletons": false,
  "offset": 0,
  "limit": 50,
  "threshold": 7.0,
  "threshold_direction": "gte"
})
```

A useful agent workflow is:

1. Run `cluster_structures` at `0.80`.
2. Use `get_clustering` to rank clusters by size and representatives.
3. Use `summarize_clusters_by_endpoint` to see SAR-relevant endpoint distributions server-side; request `output_target:"file"` for complete cluster tables.
4. Inspect selected representatives with `inspect_structure`.
5. Use `get_cluster_members` only for bounded drill-down or to create a selection handle.
6. Use `search_substructure` in count mode first to test candidate series SMARTS.
7. Create and evaluate a series decomposition config on selected clusters or the full repository.

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
