# GUI Test Apps

## A/B transformation debugger

`PairTransformationSwingApp` is a minimal internal Swing frontend for inspecting the pairwise
strict-MCS transformation split.

Entry point:

- `tech.molecules.structurized.gui.PairTransformationSwingApp`

Launch command:

```bash
mvn -pl structurized-core exec:java -Dexec.mainClass=tech.molecules.structurized.gui.PairTransformationSwingApp
```

The window contains:

- two OpenChemLib structure editors for molecule A and molecule B
- optional SMILES text fields for quick loading
- a context-radius spinner
- an `Allow non-strict MCS` checkbox for falling back to the first MCS mapping when no mapping
  satisfies strict atom/bond identity checks
- a `Debug` tab with the shared MCS core, independent `TransformationGroup` table, selected
  removed/added/context depictions, and raw signature details
- a `Visual` tab with the shared scaffold and only the difference-relevant scaffold atoms highlighted
- a visual transformation list showing every `from A -> in B` fragment pair at once, with compact
  metadata beside each graphical transformation

The computation path is:

1. compute an A-to-B MCS with `OclStrictMcsProvider`
2. split the pair with `TransformationSplitter.splitIntoTransformations(...)`
3. render each resulting `TransformationGroup` and its `TransformationSignature`

By default the app requires strict mappings. If relaxed mode is enabled and no strict mapping is
found, the app ranks the available MCS-to-A and MCS-to-B mapping pairs by ring-bond coverage,
preferring mappings that preserve more ring bonds on both sides. It then uses the best-ranked
non-strict mapping and displays a warning. This is intended as a diagnostic/debug view: differences
inside the non-strict mapped core are not represented as outside-core transformation groups.

## Purpose

`ScaffoldDiscoverySwingApp` is a minimal internal Swing GUI for validating scaffold discovery on
real datasets.

It is not intended to be the final user-facing chemistry GUI. Its purpose is:

- load a simple SMILES file
- run scaffold discovery
- inspect candidate scaffolds and their score components
- open a full-dataset scaffold decomposition viewer for one selected scaffold

## Entry point

Main class:

- `tech.molecules.structurized.gui.ScaffoldDiscoverySwingApp`

Current location:

- `structurized-core/src/main/java/tech/molecules/structurized/gui/ScaffoldDiscoverySwingApp.java`

## Supported input format

The GUI expects a simple text file with one compound per line.

Accepted conventions:

- first token on each line is treated as the SMILES string
- additional columns are ignored
- empty lines are ignored
- lines starting with `#` are ignored
- a first header token `smiles` is ignored

Examples:

```text
c1ccccc1C toluene
c1ccccc1F fluorobenzene
COc1ccccc1 anisole
```

## What the GUI shows

For every discovered scaffold candidate, the table shows:

- rank
- combined score
- support count
- average explained fraction
- scaffold heavy atom count
- observed exit vector count
- scaffold SMILES

Selecting a row shows:

- scaffold depiction
- observed exit vectors as labeled `R1`, `R2`, ... pseudo atoms on the depicted scaffold
- scaffold SMILES
- scaffold IDCode
- detailed score values
- discovery/support compound indices
- observed exit-vector atoms and symmetry classes

The discovery window also provides an `Open Decomposition Viewer` action for the selected scaffold.
This runs full scaffold-to-compound decomposition over the loaded dataset and opens a separate
tabbed viewer.

## Decomposition viewer

The decomposition viewer currently contains three tabs:

- `Compounds`
  - row-wise full-dataset decomposition table
  - unmatched compounds are hidden by default
  - selection shows the original compound and decomposition details

- `1D Projection`
  - choose one observed exit vector via `R1`, `R2`, ... toggle buttons
  - compounds are grouped by substituent at that position
  - unmatched compounds are hidden by default
  - hovering or selecting an `R` button highlights the corresponding exit vector in the scaffold view
  - special buckets are used for unsubstituted and multi-attachment compounds

- `2D Projection`
  - choose two observed exit vectors via `R1`, `R2`, ... toggle buttons
  - compounds are grouped into a count matrix across the two chosen positions
  - the first selected exit vector becomes the row axis, the second becomes the column axis
  - hovering or selecting `R` buttons highlights the corresponding exit vectors in the scaffold view
  - unmatched compounds are hidden by default
  - selecting a cell shows the compound indices currently landing in that cell

## Current limitations

1. This is a local validation tool.
   It is functional, but intentionally minimal.

2. There is no progress granularity yet.
   The UI only shows a busy indicator while discovery runs.

3. The table is candidate-centric only.
   There is no separate support-compound browser yet.

4. Discovery parameters are still minimal.
   The GUI currently exposes neighbor count, minimum scaffold size, and minimum support.

## Depiction note

The GUI adds observed exit vectors only to the displayed scaffold copy.
These are shown as OpenChemLib pseudo atoms with custom labels such as `R1` and `R2`.
The canonical `ScaffoldTemplate` itself remains unchanged.
