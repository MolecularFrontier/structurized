# Accessing MMP analytics artifacts

This guide describes how to use an existing SQLite matched molecular pair (MMP)
analytics artifact through Structurized MCP. Artifact production, endpoint governance,
and source-system integration are intentionally outside the scope of this repository.

The workflow begins with a completed artifact that is already available to the
Structurized MCP process:

~~~text
Existing SQLite MMP artifact
        |
        | open read-only and inspect endpoint runs
        v
Structurized MCP artifact session
        |
        | apply observed transforms to a query compound
        v
Ranked structure proposals + endpoint evidence for scientific review
~~~

The result is evidence-guided structure generation. It is not a property prediction,
an automated design decision, or a compound registration workflow.

## Artifact expectations

An artifact is a regular SQLite file containing:

- one or more structure universes;
- endpoint-statistics runs over those universes;
- observed transform statistics and supporting counts;
- a mining-configuration hash; and
- for current artifacts, the complete resolved mining configuration.

One artifact may contain several endpoint runs over the same structure universe. During
recommendation, one run is the primary ranking objective. Other requested runs are
reported as secondary evidence.

The artifact path may be anywhere readable by the Structurized MCP process. If an
artifact is transferred between systems, complete that transfer before opening the file
and treat the delivered file as immutable.

## Tool sequence

The normal access sequence is:

1. `open_mmp_artifact` opens an existing artifact and returns a session handle.
2. `describe_mmp_artifact` exposes universes, endpoint runs, counts, and configuration.
3. `recommend_mmp_transformations` applies observed transforms to a query structure.
4. `list_mmp_artifacts` lists handles in the current MCP session and reports whether
   each backing file is unchanged.

MCP examples below show only the tool argument object, not the surrounding JSON-RPC
envelope.

## 1. Open an artifact

Call `open_mmp_artifact` with an existing regular file:

~~~json
{
  "path": "/approved/mmp-artifacts/example-mmp-statistics.sqlite",
  "label": "Potency and solubility evidence"
}
~~~

The response includes:

- `artifact_id`: a session-scoped handle used by the remaining tools;
- canonical path and byte size;
- universe and endpoint-run counts;
- artifact schema version;
- whether a persisted mining configuration is present; and
- `read_only: true`.

The `artifact_id` is not a persistent identifier. Open the artifact again after
restarting the MCP server.

Structurized opens SQLite with read-only semantics and rechecks the file's size,
modification time, and filesystem identity before every subsequent operation. If the file
changes after it was opened, the handle is rejected rather than silently reading a
different artifact.

## 2. Inspect universes and endpoint runs

Call `describe_mmp_artifact`:

~~~json
{
  "artifact_id": "mmp-..."
}
~~~

The response lists each universe and endpoint-statistics run. A run includes its
`run_id`, `endpoint_id`, `universe_id`, creation time, configuration hash, subject and
value counts, pair count, transform count, and persisted mining configuration when
available.

Before generating structures:

- confirm that the universe is appropriate for the query and scientific question;
- record the exact `run_id` values required by the design request;
- inspect subject, pair, and transform counts for useful coverage; and
- verify that the resolved mining configuration matches the intended analysis.

Use `list_mmp_artifacts` with an empty argument object to inspect all handles in the
current session:

~~~json
{}
~~~

An `unchanged: false` result means the handle must no longer be used.

## 3. Generate all-sites proposals

For an unconstrained search, supply an ordinary SMILES and select `all_sites`:

~~~json
{
  "artifact_id": "mmp-...",
  "input_smiles": "CCOc1ccc(NC(=O)C)cc1",
  "selection_mode": "all_sites",
  "primary_run_id": "POTENCY_RUN_ID",
  "endpoint_preferences": [
    {
      "run_id": "POTENCY_RUN_ID",
      "direction": "higher_is_better"
    },
    {
      "run_id": "SOLUBILITY_RUN_ID",
      "direction": "higher_is_better"
    }
  ],
  "max_results": 25,
  "detail": "compact"
}
~~~

`recommend_mmp_transformations` fragments the query using the artifact's resolved
mining configuration, finds applicable observed transforms, applies them, and removes
invalid, unchanged, and duplicate products.

Each endpoint preference contains a run ID and one interpretation direction:

- `higher_is_better` treats a positive directed delta as desirable;
- `lower_is_better` treats a negative directed delta as desirable; and
- `neutral` reports available evidence without treating either direction as desirable.

The `primary_run_id` must also occur in `endpoint_preferences`. It alone determines
ranking by desired mean delta, followed by transform support and stable transform
identity. Additional endpoint runs annotate candidates with secondary evidence; they do
not form a hidden multi-objective score.

The default result limit is 50 and the hard maximum is 200. Use
`max_application_attempts` to impose an additional bound on attempted transform
applications.

## 4. Restrict proposals to a selected region

For targeted design, atom-map the input SMILES and pass positive map labels through
`selected_atom_maps`:

~~~json
{
  "artifact_id": "mmp-...",
  "input_smiles": "CCO[c:1]1[cH:2][cH:3][c:4](NC(=O)C)[cH:5][cH:6]1",
  "selection_mode": "attachment_vicinity",
  "selected_atom_maps": [1, 2, 3],
  "primary_run_id": "POTENCY_RUN_ID",
  "endpoint_preferences": [
    {
      "run_id": "POTENCY_RUN_ID",
      "direction": "higher_is_better"
    }
  ],
  "max_results": 20,
  "detail": "compact"
}
~~~

Atom maps are request-local selection labels. Structurized removes them before
canonicalizing the query and generating products.

| Mode | Accepted replacement region |
| --- | --- |
| `editable_region` | The selected atoms contain the complete variable fragment. |
| `exact_fragment` | The selected atoms equal the complete variable fragment. |
| `attachment_vicinity` | The variable fragment touches at least one selected atom. |
| `all_sites` | No atom selection is required; all applicable sites are considered. |

Selected map labels must be unique, must exist in `input_smiles`, and are required for
every mode except `all_sites`.

## 5. Interpret recommendation results

Each candidate includes:

- rank, product SMILES, and product IDCode;
- transform identity and cut count;
- source value-atom indices and attachment information; and
- evidence available for each requested endpoint run.

Compact evidence includes support count, mean and median delta, standard deviation,
minimum and maximum delta, positive fraction, and desired mean delta. With
`detail: "full"`, supporting example pairs are included as well.

The response also includes diagnostics for fragmentations, selected fragmentations,
primary transforms, application attempts, applied transforms, rejected products,
duplicates, result count, truncation, and duration.

The top-level metadata explicitly reports `evidence_type: "observed_mmp_statistics"` and
`prediction_type: "none"`.

## 6. Keep large results as managed JSON

Recommendation output defaults to the MCP response. For larger explorations, request a
server-managed JSON artifact:

~~~json
{
  "artifact_id": "mmp-...",
  "input_smiles": "CCOc1ccc(NC(=O)C)cc1",
  "selection_mode": "all_sites",
  "primary_run_id": "POTENCY_RUN_ID",
  "endpoint_preferences": [
    {
      "run_id": "POTENCY_RUN_ID",
      "direction": "higher_is_better"
    }
  ],
  "max_results": 100,
  "detail": "full",
  "output_target": "file",
  "output_name": "mmp/query-001.json",
  "overwrite": false,
  "format": "json"
}
~~~

File mode returns a compact summary and artifact receipt instead of embedding the
complete candidate table in the tool response. The output belongs to Structurized's
managed artifact area and does not modify the source SQLite artifact.

## Reproducibility and legacy artifacts

Current artifacts persist the complete resolved mining configuration and associate it
with a configuration hash. Recommendation reuses that configuration and validates its
hash against the primary endpoint run, ensuring that query fragmentation and transform
lookup use the artifact's settings.

Older artifacts without a persisted configuration remain browseable. Recommendation
uses the defaults only if their hash matches the selected run. If it does not match,
provide the exact historical values with these optional arguments:

~~~text
max_cuts
min_transform_support
max_variable_heavy_atoms
max_variable_to_mol_heavy_atom_fraction
max_fragmentation_records_per_compound
max_pairs_per_key
~~~

These overrides reconstruct a legacy configuration. They do not alter a current
artifact's configuration.

## Scientific interpretation

MMP deltas summarize measured differences from observed matched pairs. They are useful
local evidence, but a generated product may lie outside the chemical context of the
examples behind a transform.

- A mean delta is not a predicted property for the generated molecule.
- Support, variance, range, example pairs, assay comparability, structural context, and
  potential activity cliffs should be reviewed together.
- Secondary endpoints reveal available evidence and possible trade-offs but do not
  guarantee simultaneous improvement.
- Products still require chemistry, novelty, selectivity, ADME, safety, synthesis, and
  project-context review.
- This workflow proposes structures only; it does not register compounds or write to an
  upstream data system.

A sensible agent loop is to generate a bounded candidate set, explain the primary
evidence and secondary trade-offs, let a scientist select promising ideas, and then send
only those ideas to the appropriate prediction, synthesis-planning, or registration
workflow.

## Common failures and recovery

| Symptom | Likely cause and response |
| --- | --- |
| Artifact cannot be opened | Confirm that the path identifies an existing regular SQLite artifact readable by the MCP process. |
| Artifact changed error | Stop using the stale handle, investigate the file lifecycle, and reopen only the intended immutable file. |
| Unknown artifact ID | The handle is session-scoped; open the artifact in the current MCP session. |
| Unknown run ID | Call `describe_mmp_artifact` and use a run ID from that artifact. |
| Mining configuration hash mismatch | For a legacy artifact, supply the exact historical overrides; otherwise verify the selected run and artifact. |
| Invalid selected atom map | Use unique positive atom maps that occur in the mapped input SMILES. |
| No candidates | Inspect diagnostics, support, endpoint coverage, query applicability, selection mode, and selected atom maps. An empty result can be scientifically valid. |

## Verification during development

From the Structurized repository, run:

~~~text
mvn -pl structurized-analytics,structurized-ai-mcp -am test
~~~

The contract and end-to-end tests cover persisted mining configuration, read-only
artifact opening, artifact identity checks, legacy configuration validation, and
generation of a product from an existing artifact.
