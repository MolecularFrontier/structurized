Prism managed-session integration
=================================

Status
------

This document describes the currently implemented same-JVM integration between
Structurized, PrismEngine, PrismLite, and MCP. Native Prism groupings, virtual
exclusive-group facets, and provider-owned clustering artifacts are current
behavior. Proposed similarity spaces and row graphs are described in the Prism
repository document `SMART_TABLE_SEMANTIC_RESOURCES.md`.

Architecture
------------

The combined application composes one shared runtime:

```text
PrismLite Swing UI
        |
        +---------------------------+
                                    |
Structurized MCP tools              v
        +----------------> ManagedPrismSession
        |                       +-- PrismSession workspace
        |                       +-- optional PRISM data context
        |                       +-- lightweight molecule workspace
        |
        +----------------> PrismArtifactRegistry
                                +-- rich provider-owned results by session/artifact ID
```

PrismLite and MCP resolve the same `ManagedPrismSession` through one
`PrismSessionRegistry`. They do not maintain synchronized copies of the table.
Both clients read and modify the exact same in-memory `PrismSession`.

`PrismSession` remains authoritative for:

* the immutable base and full runtime table;
* filters, sorting, and the visible row projection;
* visible columns;
* computed and materialized columns;
* stable row IDs;
* row sets;
* groupings and their runtime categorical facets;
* declarative views;
* registered Prism operations.

The managed wrapper adds application-level concerns:

* stable session ID and display label;
* source path and open time;
* optional canonical PRISM dataset context;
* monotonic revision;
* mutation origin and change notifications;
* execution policy for UI-thread publication;
* ordered lists of newly drawn or agent-proposed molecule documents.

Molecule workspace
------------------

The molecule workspace is deliberately independent from the immutable Prism
table. It is a small session-local chemical scratch space:

```text
PrismMoleculeWorkspace
    Scratchpad
        molecule document
        fragment document
    Proposed analogues
        candidate 1
        candidate 2
```

Lists are ordered and may contain chemically duplicate documents. Document ID,
not chemical equality, defines identity. A document stores only a title, an OCL
IDCode with encoded 2D coordinates, a revision, and either `MOLECULE` or
`FRAGMENT` mode. Fragment mode preserves OpenChemLib query features and is
exported as SMARTS when exposed to an agent.

Molecule lists are not row sets, analyses, or table projections. Opening a
table structure in the sketcher merely initializes a new independent document;
editing it never mutates the source row. Future similarity and substructure
filters may consume a document as an input without changing this ownership
boundary.

Session registry
----------------

`PrismSessionRegistry` registers and resolves managed sessions by stable
session ID. It also indexes by `PrismSession` object identity so a PrismLite
window can discover the already registered managed wrapper for its workspace.

Registering the same workspace returns its existing managed session. Reusing a
session ID for another workspace is rejected. Registry listeners receive the
same managed changes emitted by contained sessions.

The combined `structurized-prismlite-app` creates:

1. one session registry with a Swing-aware executor;
2. one Structurized Prism bridge using that registry;
3. one MCP JSON-RPC handler using that bridge;
4. one PrismLite extension using that registry;
5. one displayed PrismSession registered under the selected session ID.

The headless MCP application remains supported. Its default constructors create
a private in-memory registry instead of sharing a PrismLite runtime.

Data context
------------

A managed session always has a `PrismSession` workspace. It may additionally
retain the canonical PRISM provider dataset used to create the flattened table.

```text
PrismSession
    analysis-ready runtime dataframe and workspace state

PRISM data context
    subject sets, endpoint definitions, measurements, and provider provenance
```

Sessions imported from canonical PRISM TSV data can answer endpoint and
measurement queries through the data context. A generic PrismPack session may
have only the analysis-ready table. Tools requiring canonical endpoint records
report that the context is unavailable; table-, column-, and row-set-based
operations continue to work.

Revision and change model
-------------------------

A managed revision is a monotonically increasing synchronization marker:

```text
revision 17
    MCP operation publishes related workspace changes

revision 18
    one consolidated event is emitted
```

It is not a retained state snapshot and does not currently provide undo, redo,
rollback, or conflict rebasing.

Workspace changes are currently classified as:

* `PROJECTION`: filters or sorting changed visible rows/order;
* `STRUCTURE`: runtime columns, groupings, visible columns, or row sets changed;
* `VIEWS`: declarative views changed.
* `MOLECULES`: molecule lists or their documents changed.

Here `STRUCTURE` means table/workspace structure, not a chemical structure.

Origins identify who initiated the logical mutation:

* `LOCAL_UI`;
* `MCP`;
* `SYSTEM`.

A managed mutation scope collects all PrismSession notifications produced by
one logical action. On successful completion it increments the revision once
and publishes one merged event:

```text
managedSession.runAs(MCP)
    publish clustering grouping and optional similarity column
        |
        v
one revision increment and one notification
```

This is a notification transaction. It avoids exposing intermediate redraw
states, but it is not an ACID transaction and does not automatically undo
already applied mutations when later code fails. Prism operation results perform
their own validation before changing the workspace.

Changes made directly through `PrismSession` outside a managed scope are
reported as `LOCAL_UI` and increment the managed revision immediately.

Swing synchronization
---------------------

Expensive chemistry computations execute outside the Swing event dispatch
thread. The managed executor moves only the short final publication step onto
that thread.

The shared PrismLite extension subscribes to managed-session changes:

```text
MCP computes result
    -> publication on Swing event thread
    -> managed revision/event
    -> PrismLite coordinated refresh
```

PrismLite ignores managed notifications whose origin is `LOCAL_UI` because the
local controller has already refreshed the workspace. This prevents duplicate
redraws and selection flicker. External MCP or system changes trigger a
coordinated refresh of the table, navigator, inspector, filter shelf, status,
and views. Molecule-only changes refresh the persistent Molecules tab directly
and intentionally avoid rebuilding the table.

Opening another dataset through PrismLite registers that new workspace through
the same extension. Injected extensions survive frame replacement so the new
window remains connected to the shared registry.

MCP workflow
------------

The session-native agent workflow is:

```text
open or resolve session_id
    -> inspect columns, endpoints, and row sets
    -> create or reuse a PrismRowSet scope
    -> run a Structurized operation
    -> publish Prism columns, row sets, or views
    -> observe the result live in PrismLite
```

Stable Prism row IDs are authoritative throughout the workflow. A structure ID
is not used as dataframe identity because several scientific rows may
legitimately share a nominal structure.

The main shared effects available today are:

* creating endpoint-, date-, column-, or composition-based row sets;
* publishing exclusive groupings with virtual categorical facets;
* publishing representative-similarity columns when requested;
* creating a row set from any selected group;
* creating or updating Prism views through registered operations.

Clustering ownership and publication
------------------------------------

The ownership boundary is now implemented for session-backed clustering:

> Prism owns generic row-anchored workspace resources. Structurized owns
> algorithms and detailed algorithm-specific artifacts.

`PrismArtifactRegistry` is a Structurized provider registry keyed by session ID
and artifact ID. A `PrismClusteringAnalysis` stored there contains the complete
clustering result, summary, resolved structures, representatives, examples,
and cross-cluster neighbors. It supports the existing rich MCP workflows for
listing and inspecting analyses without adding an algorithm-specific object to
`ManagedPrismSession` or PrismEngine.

Every successful clustering publishes one authoritative `PrismGrouping` into
the shared workspace:

```text
Structurized clustering artifact
    |
    +-- Prism exclusive grouping
    |       groups, representatives, membership weights
    |       source row-set scope and provenance
    |
    +-- runtime categorical grouping facet
    |       <analysisId>.cluster_id
    |
    +-- optional materialized column
            <analysisId>.similarity_to_representative
```

`publish_columns = true` makes the grouping facet visible and publishes the
similarity column. `publish_columns = false` still publishes the authoritative
grouping and its addressable runtime facet, but leaves the facet hidden and
does not add the similarity column. This allows later generic operations to use
the scientific result without forcing presentation choices.

Generic MCP tools can list groupings, page through groups and their hierarchy,
and create a row set from any group. Existing clustering-specific tools remain
as compatibility and rich-artifact APIs. Creating a cluster row set now reads
membership from the Prism grouping, not from a second copied membership list in
the clustering artifact.

The managed session registry remains responsible for shared session identity,
revision ordering, execution policy, and UI/MCP synchronization. A Prism
session revision changes only when shared workspace state changes. Adding or
reading a provider artifact alone does not define a managed workspace change,
and the obsolete `ANALYSES` managed change category has been removed.

Other providers should follow the same boundary:

```text
prediction      -> value and uncertainty columns
substructure    -> row set
MMP analysis    -> row graph plus selected columns/views
future decomposition -> grouping, facets, and failure row sets
```

Prism provenance links each projection to its producer artifact without
embedding detailed algorithm payloads. Similarity spaces and row graphs remain
future Prism semantic resources; grouping is the implemented first vertical
slice.

Agent mutation boundary
-----------------------

Agents should operate through a small semantic vocabulary rather than arbitrary
access to session internals:

* invoke registered operations;
* add validated materialized columns or semantic facets;
* create named row sets;
* create or update declarative views;
* apply semantic filters when explicitly requested.

The operation layer validates stable row IDs, column references, resource IDs,
and collisions before publication. Algorithm-specific inputs and outputs remain
inside their provider.

This boundary keeps Prism intelligent about the shape and interactive meaning
of scientific results while keeping chemistry implementation and large result
payloads in Structurized.
