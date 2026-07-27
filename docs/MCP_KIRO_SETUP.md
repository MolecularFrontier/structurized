# MCP and Kiro Setup

## Build the standalone MCP jar

The `structurized-ai-mcp` module builds a runnable standalone jar for stdio MCP clients.

From any directory:

```bash
mvn -f /home/liphath1/dev_cheminfo/structurized/pom.xml \
  -pl structurized-ai-mcp -am \
  -Dgpg.skip=true -DskipTests package
```

The runnable jar is produced at:

```text
/home/liphath1/dev_cheminfo/structurized/structurized-ai-mcp/target/structurized-ai-mcp-0.2.1-standalone.jar
```

Launch it directly with:

```bash
java -jar /home/liphath1/dev_cheminfo/structurized/structurized-ai-mcp/target/structurized-ai-mcp-0.2.1-standalone.jar
```

The server speaks JSON-RPC MCP over stdin/stdout. It does not print a prompt or banner.

## Kiro configuration

For a workspace-local Kiro configuration, create or edit:

```text
<kiro-project>/.kiro/settings/mcp.json
```

Example:

```json
{
  "mcpServers": {
    "structurized": {
      "command": "java",
      "args": [
        "-jar",
        "/home/liphath1/dev_cheminfo/structurized/structurized-ai-mcp/target/structurized-ai-mcp-0.2.1-standalone.jar"
      ],
      "timeout": 120000,
      "disabled": false
    }
  }
}
```

Inside Kiro, use `/mcp` to verify that the `structurized` MCP server is loaded.

## Prism dataset workflow

A Prism TSV folder should contain these files:

```text
endpoints.prism.tsv
subjects.prism.tsv
values.prism.tsv
```

Optional files:

```text
subject_sets.prism.tsv
subject_set_memberships.prism.tsv
```

The `subjects.prism.tsv` file needs a `smiles` column for structure materialization.

A typical first agent workflow is:

1. `open_prism_dataset` with the Prism TSV folder path.
2. `get_prism_dataset_info` to inspect counts, subject sets, and endpoints.
3. `list_prism_subject_sets` to choose a subset.
4. `materialize_prism_subject_set` to create a normal chemistry repository.
5. `cluster_structures` to get rough representative-led clusters.
6. `get_clustering` to inspect compact cluster summaries. Use `get_cluster_members` only for bounded drill-down.
7. `summarize_clusters_by_endpoint` to compare a paged non-singleton cluster table against potency/property distributions without returning raw member IDs; use `output_target:"file"` for the full filtered table.
8. Use `search_substructure` in default count mode first; request `output_mode:"ids"` or `output_mode:"full"` only when the count looks useful.
9. Use `create_endpoint_selection` for numeric potency/property filters such as `pIC50 >= 7.0`; it creates a server-side selection from a repository or filters an existing selection.
10. Use `create_selection:true` on searches or cluster-member calls when a server-side handle is needed for `summarize_selection_by_endpoint` or scoped `evaluate_decomposition`; use `combine_selections` for `union`/`merge`, `intersect`, and `subtract`.
11. Use decomposition tools for deeper analysis: pass `selection_id` to `evaluate_decomposition` for selected chemotypes, then use `get_decomposition_fragment_summary` to find terminal paths and `get_decomposition_fragment_histogram` to rank distinct fragments with optional endpoint medians/hit rates.
12. Use `export_selection_table` when the next step is Python, DuckDB, or plotting. It writes a TSV artifact with structure rows, optional long endpoint rows, and optional decomposition fragment columns.

Embedded MCP guidance:

- Use `get_structurized_tool_guide` for server-provided workflow notes. Topics are `overview`,
  `payload_hygiene`, `prism_workflow`, `clustering_workflow`, `decomposition_rules`, and
  `artifact_output`. This works from the standalone jar without relying on repository Markdown files.

Payload hygiene defaults:

- `search_substructure` defaults to `output_mode:"count"`, returning counts but no rows. Use `limit` and `offset` with `ids` or `full`.
- `get_clustering` returns compact cluster rows, not full member lists.
- `get_decomposition_fragment_summary` returns compact examples by default. Set `include_details:true` only when signatures and atom arrays are needed.
- `get_decomposition_fragment_histogram` is the compact R-group SAR view: default `limit:50`, optional `dataset_id` + `endpoint_id`, and `output_target:"file"` for the full fragment table.
- Server-side selections avoid copying large ID lists into the chat context; `create_endpoint_selection` creates numeric endpoint-filter subsets, `combine_selections` can merge/intersect/subtract handles, `evaluate_decomposition` accepts `selection_id` directly, and `export_selection_table` writes TSV artifacts for external analysis.
- For large drill-downs, set `output_target:"file"` and optionally provide a safe relative `output_name`. The response returns an artifact receipt instead of the full payload.

Managed artifacts:

- Artifact files are written under a server-owned session directory. By default this is under the JVM temp directory at `structurized-mcp-artifacts/<session-id>/`.
- The base directory can be overridden with the Java system property `-Dstructurized.mcp.artifactDir=/path/to/base`.
- `output_name` is optional and must be relative, for example `series_A/matches.json`. Absolute paths, `.` and `..` path segments, and symlink traversal are rejected.
- Existing files are not overwritten by default; the server appends `_2`, `_3`, and so on. Use `overwrite:true` only when intentionally replacing a caller-named artifact.
- Use `list_artifacts` and `get_artifact_info` to recover artifact paths created during the MCP session.

Example file-output search:

```json
search_substructure({
  "query": "c1ccncc1",
  "query_type": "smiles",
  "repository_ids": ["prism:demo:series:Kinase:A"],
  "output_mode": "full",
  "limit": 5000,
  "output_target": "file",
  "output_name": "series_A/pyridine_matches.json"
})
```

Example endpoint-filter selection:

```json
create_endpoint_selection({
  "dataset_id": "project1",
  "repository_id": "prism:project1:series:Kinase:A",
  "endpoint_id": "primary_pIC50",
  "operator": "gte",
  "value": 7.0,
  "selection_id": "potent_primary"
})
```

Then intersect it with a structural selection or cluster-member selection using `combine_selections`.

Example TSV export for Python/DuckDB:

```json
export_selection_table({
  "selection_id": "potent_primary",
  "dataset_id": "project1",
  "endpoint_ids": ["primary_pIC50"],
  "decomposition_evaluation_id": "series_eval_v1",
  "output_name": "exports/potent_primary.tsv"
})
```

The response contains an artifact receipt and schema. Data rows are written to the TSV file, not returned inline.

Example prompt for Kiro:

```text
Use the structurized MCP server. Open the Prism TSV dataset at /path/to/prism-tsv as dataset_id "project1". List dataset info, subject sets, and endpoints. Materialize the most relevant subject set into a chemistry repository. Then run rough clustering at threshold 0.80 and summarize representative clusters.
```

## Development launch alternative

The standalone jar is preferred for MCP clients. For local development, Maven can launch the server
when the exec goal is run from the MCP module project directly:

```bash
mvn -q -f /home/liphath1/dev_cheminfo/structurized/structurized-ai-mcp/pom.xml \
  exec:java \
  -Dexec.mainClass=tech.molecules.structurized.ai.mcp.McpStdioServer
```

Avoid running `exec:java` against the parent aggregator POM. In that case Maven may execute the goal
on the parent project and fail with `ClassNotFoundException` for `McpStdioServer`.
