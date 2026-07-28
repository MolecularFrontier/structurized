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
Observed occupied positions emerge from real scaffold-to-compound matches. In practice, draw the
conserved core rather than dummy attachment points: if a matched scaffold atom has extra bonds in
a target compound, those extra bonds define observed R-groups at that scaffold atom.

For MCP usage, concrete scaffold SMILES may include atom-map numbers on actual scaffold
atoms to provide stable human labels for later projections. For example:

```json
{
  "scaffold_smiles": "[cH:1]1ccc(N[C:2](=O)N)cc1",
  "exit_atom_map_labels": {"1":"cap", "2":"tail"}
}
```

The map number is used only as a stable scaffold-atom selector. It is not a SMARTS atom
label and it is not interpreted as a chemical constraint. During matching, simple mapped atoms
are normalized so bracket-induced hydrogen/valence metadata from the label does not constrain
the query. Put the map on the scaffold atom itself, not on a dummy or branch atom unless that
atom is truly part of the scaffold.

## Matching strategy

`ScaffoldAnalyzer` uses OpenChemLib `SSSearcher` with:

- scaffold copied as a fragment query
- unique-match mode

The supplied `scaffold_smiles` is concrete substructure mode, not generalized SMARTS.
When the concrete scaffold matches inside a compound, extra bonds from matched scaffold atoms
to atoms outside the scaffold are interpreted as exit vectors. Simple scaffold atoms may match
more-substituted target atoms; the additional target bonds become the R-groups. This is why a
hand-edited smaller conserved core can produce useful R-group projections even though no exit
vectors were predefined manually.

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
- `analyze_prism_scaffold` stores a scaffold-analysis handle and returns compact match diagnostics;
- `get_prism_scaffold_projection` returns compact 1D, 2D, or n-dimensional bucket counts;
- `create_prism_scaffold_bucket_row_set` turns a returned bucket key into a Prism row set;
- `export_prism_scaffold_projection` writes a full TSV projection artifact.

## Matched-context recipe

Start from a candidate `scaffold_smiles` returned by `discover_prism_scaffolds`, or hand-write
a conserved core. Discovered cores are often fully elaborated; trim them toward the smallest
conserved core that answers the SAR question to raise exit-vector support.

A useful agent workflow is to move from a broad marginal trend to a cleaner matched observation:

1. Run `analyze_prism_scaffold` with a small conserved core and mapped labels for the positions
   of interest, e.g. `exit_atom_map_labels: {"1":"cap", "2":"tail"}`.
2. Run `get_prism_scaffold_projection` for one position, e.g. `scaffold_atom_maps:[2]`, with
   endpoint `column_ids` to see a 1D R-group trend. `column_ids` may list several endpoints to
   read potency, selectivity, and liabilities across the same buckets in one call.
3. If the 1D rows show `cleanMatchedContext:false`, add the co-varying position to the projection,
   e.g. `scaffold_atom_maps:[1,2]`, to produce a sparse cap-by-tail matrix.
4. Promote a useful clean cell with `create_prism_scaffold_bucket_row_set`, using the same
   `scaffold_atom_maps` or `scaffold_atoms` dimensions and the returned `bucket_key`, then use
   the row set for MMP neighborhoods, endpoint summaries, or export.

`scaffold_atom_maps` and zero-based `scaffold_atoms` are interchangeable selectors for the same
scaffold positions. Map numbers are usually safer for agent-authored scaffolds because they survive
SMILES canonicalization.

## Projection semantics

Projection buckets are marginal unless all other observed exit-vector positions are constant.
For example, projecting only `cap` pools compounds that may still differ at `tail`. This is
useful for broad trends, but it is not the same as a fully matched context. MCP projection rows
therefore include context metadata:

- `cleanMatchedContext`: `true` when all observed exit-vector positions outside the projection are
  constant across the bucket; endpoint summaries are then clean matched observations.
- `otherPositionCount`: number of observed exit-vector positions not included in the projection.
- `diverseOtherPositionCount`: number of those other positions that vary inside the bucket;
  nonzero values mean the bucket is a marginal trend pooled over the listed `diverseOtherPositions`.

Bucket types are compact:

- `none`: the position is unsubstituted.
- `multi`: multiple or ambiguous attachments at that scaffold atom are reported jointly.
- `unmatched`: the scaffold did not match the compound.

Unmatched buckets are suppressed from scaffold-analysis top buckets and projections by default,
because they often dominate payloads without adding SAR signal. The unmatched count remains in
the analysis diagnostics, and callers can opt back in with `include_unmatched_buckets:true`.

## Choosing scaffold generality and diagnosing zero hits

A hand scaffold must be a conserved substructure. Common zero-hit causes are wrong ring size,
protonation or aromaticity mismatch, over-specific caps, CF2/CF3 mismatches, or including atoms
that are actually variable. The zero-hit warning and matched/unmatched examples are intended to
make this diagnosable.

Prefer the smallest conserved core that still defines the SAR question. A fully elaborated scaffold
may match only a narrow subseries, while a hand-edited smaller core can match many more compounds
and produce higher-support exit vectors for useful R-group SAR.

## Current limitations

1. Scaffold matching and scaffold discovery are separate concepts.
   Scaffold matching requires a provided template, while candidate discovery is available through
   `ScaffoldDiscoveryEngine` and the MCP `discover_prism_scaffolds` workflow.

2. Exit vectors are implicit.
   All scaffold atoms are candidate exit vectors; no chemical filtering of “reasonable”
   substitution sites is applied yet. MCP callers can label or select positions with mapped
   scaffold atoms or raw zero-based scaffold atom indices.

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
