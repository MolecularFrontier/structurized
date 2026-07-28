# Scaffold Mode

## Current scope

The first scaffold-mode implementation treats a predefined scaffold as the preserved core and
reuses the existing core-relative transformation splitter to decompose a compound into
scaffold-relative substitution events.

Implemented objects:

- `ScaffoldTemplate`
- `ExitVector`
- `ScaffoldMatch`
- `ScaffoldDecomposition`
- `SubstitutionEvent`
- `ScaffoldAnalyzer`
- `ScaffoldDatasetDecomposition`

## Main idea

Instead of comparing compound `A` against compound `B`, scaffold mode compares:

- `scaffold -> compound`

The scaffold atoms are treated as the full preserved core. Everything present in the compound
outside the scaffold match is interpreted as an added region.

This means the existing `TransformationSplitter` can already do most of the heavy lifting.

## What a `ScaffoldTemplate` stores

The template is intentionally minimal:

- the unsubstituted scaffold graph
- a canonical scaffold IDCode
- one symmetry class per scaffold atom
- one candidate `ExitVector` per scaffold atom

No explicit manually defined exit vectors are required.
Observed occupied positions emerge from real scaffold-to-compound matches.

## Matching strategy

`ScaffoldAnalyzer` uses OpenChemLib `SSSearcher` with:

- scaffold copied as a fragment query
- unique-match mode

If multiple unique matches exist, the analyzer currently evaluates every match, runs the splitter
for each one, converts the result into substitution events, and then picks the lexicographically
smallest event signature set. This gives a deterministic first-pass symmetry handling strategy.

## Decomposition result

A successful `ScaffoldDecomposition` contains:

- the selected `ScaffoldMatch`
- the raw `TransformationGroup`s from scaffold-to-compound splitting
- scaffold-centric `SubstitutionEvent`s

Each `SubstitutionEvent` contains:

- the underlying `TransformationGroup`
- the concrete scaffold atom indices
- the corresponding scaffold symmetry classes
- the `ExitVector`s
- the added fragment IDCode
- a scaffold-centric event type

## Current substitution event typing

The current event typing is intentionally simple:

- `SINGLE_ATTACHMENT`
- `MULTI_ATTACHMENT`

This is enough to distinguish ordinary substituents from bridge / annulation / multi-anchor cases.
Finer distinctions such as `BRIDGE` versus `ANNULATION` are not implemented yet.

## Dataset-level scaffold SAR

`ScaffoldDatasetDecomposition` applies one scaffold template to a compound set and records:

- matched, unmatched, and multi-attachment compound counts;
- observed exit-vector scaffold atoms and symmetry classes;
- per-compound substituent assignments at single-attachment exit vectors;
- 1D substituent projections for one scaffold atom;
- sparse 2D projections for two scaffold atoms.

The MCP layer exposes this as a compact scaffold SAR workflow over managed Prism row sets:

- `discover_prism_scaffolds` mines candidate scaffold handles;
- `analyze_prism_scaffold` stores a scaffold-analysis handle;
- `get_prism_scaffold_projection` returns compact 1D, 2D, or n-dimensional bucket counts;
- `create_prism_scaffold_bucket_row_set` turns a returned bucket key into a Prism row set;
- `export_prism_scaffold_projection` writes a full TSV projection artifact.

## Current limitations

1. Scaffold matching and scaffold discovery are separate concepts.
   Scaffold matching requires a provided template, while candidate discovery is available through
   `ScaffoldDiscoveryEngine` and the MCP `discover_prism_scaffolds` workflow.

2. Exit vectors are implicit.
   All scaffold atoms are candidate exit vectors; no chemical filtering of “reasonable”
   substitution sites is applied yet.

3. Match selection is deterministic but still heuristic.
   The analyzer chooses the smallest decomposition key among unique substructure matches.

4. Multi-attachment events are grouped, but not yet subclassified into bridge / cyclization /
   fused-ring extension categories.

5. N-dimensional projections in the MCP layer are compact grouping views over the existing
   per-exit-vector assignments. They are not yet a full interactive SAR table editor.

## Recommended next steps

1. Add more specific multi-anchor event classes:
   bridge, annulation, cyclization, multi-anchor extension.

2. Add richer symmetry-aware aggregation:
   concrete scaffold atom versus symmetry class versus matched-orientation information.

3. Decide whether to introduce optional chemical filtering of candidate exit vectors.

4. Add richer endpoint-aware ranking/scoring of scaffold projection buckets.
