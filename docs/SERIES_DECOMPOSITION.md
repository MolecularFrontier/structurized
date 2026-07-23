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
- `atomLabels`: map from query atom index to output fragment label.
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

Useful follow-up tools:

- `get_decomposition_evaluation`: summary and optional molecule-level result list.
- `get_decomposition_result`: full tree for one molecule.
- `get_decomposition_failures`: non-successful molecules grouped by status.
- `get_decomposition_fragment_summary`: terminal fragment support and examples by path.

## Current limitations

1. Rules use OpenChemLib SMARTS as provided; there is no graphical rule editor yet.
2. The engine records non-unique decompositions as a status, but does not yet expose a full candidate
   comparison UI.
3. Fragment signatures are derived representations. The authoritative result remains the original
   molecule plus atom membership and boundary metadata.
4. MCP configs and evaluations are session-scoped and in-memory.
