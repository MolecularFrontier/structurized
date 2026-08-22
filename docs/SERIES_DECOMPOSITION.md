# Series Decomposition

## Purpose

The series decomposition engine applies an explicit, versioned rule list to split molecules into a
recursive tree of chemically named parts. It is intended for project-specific medchem series where a
chemist or agent can define clear SMARTS-based decomposition rules and then evaluate them
deterministically across a compound set.

This is different from the older scaffold-mode tools. Scaffold mode starts from a scaffold and
computes scaffold-relative substituents. Series decomposition starts from a rule program and keeps
the full hierarchy of matched regions, intermediate fragments, final leaves, cut bonds, boundary
bonds, and rule history.

## Rule model

A configuration is an ordered JSON object:

```json
{
  "version": "series-decomposition-v1",
  "rules": [
    {
      "id": "split_root",
      "title": "Root split",
      "labelToSplit": null,
      "smarts": "CCO",
      "atomLabels": {
        "0": "alkyl",
        "1": "linker",
        "2": "head"
      },
      "enabled": true
    }
  ]
}
```

Fields:

- `version`: configuration version string; defaults to `series-decomposition-v1`.
- `rules`: ordered list of candidate decomposition rules.
- `id`: stable rule identifier.
- `title` and `description`: optional human-readable metadata.
- `labelToSplit`: label targeted by this rule; `null` means root molecule.
- `smarts`: OpenChemLib SMARTS query.
- `atomLabels`: map from zero-based SMARTS query atom index to output fragment label. These
  keys are not SMARTS atom-map numbers such as `:1` or `:2`.
- `enabled`: optional; omitted or `true` means active.

Multiple rules may target the same `labelToSplit`. They are tried in list order. The first rule that
produces a valid unique split becomes the canonical applied rule for that node.

## Splitting semantics

Rules usually match only the chemically meaningful center of a split, not the full molecule.
Matched query atoms carry output labels. Bonds between differently labeled matched atoms are treated
as cut bonds. After those cuts, connected components are assigned to the single label type they
contain.

A rule application is valid only if every resulting component has exactly one matched label type.
Unlabeled atoms are allowed and are absorbed into the labeled component they remain connected to.
This lets a compact SMARTS split a full substituent region, linker, or scaffold part without labeling
every atom in the molecule.

Atom-label keys are zero-based positions in the parsed SMARTS query graph. They are not atom-map
numbers. For example, in `[C:1](=O)[NX3:2]`, the query atom indices are carbon `0`, oxygen `1`,
and nitrogen `2`. To split an amide into `acyl` and `amine`, use `{"0":"acyl","2":"amine"}`
and leave the oxygen unlabeled. The tempting `{"1":"acyl","2":"amine"}` labels oxygen and
nitrogen while leaving carbon unlabeled, so the query component still contains both label types and
is invalid.

Example intent:

```text
root molecule
├── core
└── tail
    ├── headgroup
    └── terminal_group
```

This is represented as an ordered list, not as nested JSON:

```json
{
  "version": "series-decomposition-v1",
  "rules": [
    {
      "id": "root_core_tail",
      "labelToSplit": null,
      "smarts": "cNCC",
      "atomLabels": {
        "0": "core",
        "1": "core",
        "2": "tail",
        "3": "tail"
      }
    },
    {
      "id": "tail_head_terminal",
      "labelToSplit": "tail",
      "smarts": "NCC",
      "atomLabels": {
        "0": "headgroup",
        "1": "headgroup",
        "2": "terminal_group"
      }
    }
  ]
}
```

## Result model

For each molecule the engine returns a `DecompositionResult` containing:

- root node and recursive child nodes
- final terminal nodes
- original atom indices for every node
- rule attempts and canonical applied rule IDs
- cut bonds produced by each split
- boundary bonds connecting a fragment to neighboring fragments
- status for every node

The main statuses are intentionally compact:

- `UNIQUE_MATCH`: rule matched and produced one effective decomposition.
- `NO_MATCH`: no candidate rule matched this node.
- `MATCHED_NON_UNIQUE`: more than one distinct valid decomposition was possible.
- `INVALID_RULE_OR_ASSIGNMENT`: SMARTS or label assignment could not produce a valid split.
- `SKIPPED`: no successful split was applied; the node remains terminal.

## Dataset evaluation

`DecompositionDatasetEvaluator` applies one config to a list of molecules and computes dataset-level
summary statistics:

- molecule count
- successful count and coverage
- root no-match count
- non-unique count
- invalid count
- terminal fragment frequencies by path and fragment signature

The Swing app `tech.molecules.structurized.gui.DecompositionSwingApp` is a lightweight viewer for
loading a SMILES file plus decomposition config JSON, running the evaluation, and inspecting the
result table, selected decomposition tree details, terminal fragments, and atom highlights.

## MCP usage

The MCP adapter exposes the same headless engine. A typical flow is:

```json
validate_decomposition_config({
  "config": {
    "version": "series-decomposition-v1",
    "rules": [
      {
        "id": "split_root",
        "labelToSplit": null,
        "smarts": "CCO",
        "atomLabels": { "0": "alkyl", "1": "linker", "2": "head" }
      }
    ]
  }
})
```

```json
create_decomposition_config({
  "config_id": "demo_split",
  "label": "Demo split",
  "config": {
    "version": "series-decomposition-v1",
    "rules": [
      {
        "id": "split_root",
        "labelToSplit": null,
        "smarts": "CCO",
        "atomLabels": { "0": "alkyl", "1": "linker", "2": "head" }
      }
    ]
  }
})
```

```json
evaluate_decomposition({
  "evaluation_id": "eval1",
  "config_id": "demo_split",
  "repository_id": "session"
})
```

For scoped analysis, define endpoint scopes as snapshot-native row sets. Materialize
only the selected row set when a repository-only operation such as decomposition
needs it, then create server-side selections from structural searches:

```json
create_prism_endpoint_row_set({
  "session_id": "project1",
  "endpoint_id": "primary_pIC50",
  "operator": "gte",
  "value": 7.0,
  "row_set_id": "potent_primary"
})

materialize_prism_row_set({
  "session_id": "project1",
  "row_set_id": "potent_primary",
  "repository_id": "potent_primary"
})

search_substructure({
  "query": "CCO",
  "repository_ids": ["potent_primary"],
  "output_mode": "ids",
  "create_selection": true,
  "selection_id": "potent_alcohol_hits"
})

evaluate_decomposition({
  "evaluation_id": "eval_alcohol_hits",
  "config_id": "demo_split",
  "selection_id": "potent_alcohol_hits"
})
```

Useful follow-up tools:

- `create_prism_endpoint_row_set`: create a reusable snapshot row set from a
  numeric endpoint filter using `gt`, `gte`, `lt`, `lte`, or `eq`, optionally
  with measurement-date bounds.
- `materialize_prism_row_set`: copy a chosen row set into a chemistry repository
  only for tools that require that representation.
- `combine_selections`: create a new selection by `union`/`merge`, `intersect`, or `subtract` over
  existing selection handles.
- `evaluate_decomposition`: evaluate a full repository, explicit `structure_ids`, or a server-side
  `selection_id` created by search, endpoint-filter, cluster-member, or selection-combination tools.
- `export_selection_table`: write a TSV artifact for a selection, optionally joined to PRISM
  endpoint long rows and decomposition fragment columns from an `evaluation_id`.
- `get_decomposition_evaluation`: summary and optional molecule-level result list.
- `get_decomposition_result`: full tree for one molecule.
- `get_decomposition_failures`: non-successful molecules grouped by status.
- `get_decomposition_fragment_summary`: terminal fragment support and examples by path. The MCP tool
  returns compact rows by default: counts plus representative fragment SMILES. Set
  `include_details:true` only when the caller needs fragment signatures, atom IDs, and atom
  indices. Set `output_target:"file"` to write compact or detailed rows to a managed artifact.
- `get_decomposition_fragment_histogram`: ranked distinct-fragment vocabulary for one terminal
  `path` or unambiguous `label`. It returns support counts, representative fragment SMILES, and
  example structure IDs. Add `dataset_id` and `endpoint_id` to attach PRISM endpoint summaries per
  fragment, for example median potency and threshold hit rate. Response mode is paged; set
  `output_target:"file"` for the full compact histogram table.

Example R-group SAR histogram:

```json
get_decomposition_fragment_histogram({
  "evaluation_id": "eval1",
  "path": "root.tail.cap",
  "dataset_id": "project1",
  "endpoint_id": "primary_pIC50",
  "threshold": 7.0,
  "threshold_direction": "gte",
  "limit": 25
})
```

This returns rows such as `fragmentSmiles`, `support`, `exampleStructureIds`, and an optional
`endpoint` object containing count, median, quartiles, threshold hit count, and hit rate.

Example selection export for Python/DuckDB:

```json
export_selection_table({
  "selection_id": "potent_alcohol_hits",
  "dataset_id": "project1",
  "endpoint_ids": ["primary_pIC50"],
  "decomposition_evaluation_id": "eval_alcohol_hits",
  "output_name": "exports/potent_alcohol_hits.tsv"
})
```

The TSV contains structure identifiers, optional long-format endpoint values, and wide fragment
columns such as `decomp_root_tail_cap_fragment_smiles` for terminal decomposition paths.

## Current limitations

1. Rules use OpenChemLib SMARTS as provided; there is no graphical rule editor yet.
2. The engine records non-unique decompositions as a status, but does not yet expose a full candidate
   comparison UI.
3. Fragment signatures are derived representations. The authoritative result remains the original
   molecule plus atom membership and boundary metadata.
4. MCP configs and evaluations are session-scoped and in-memory.
