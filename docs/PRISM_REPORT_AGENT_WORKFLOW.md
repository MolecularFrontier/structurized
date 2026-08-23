# Agent-authored Prism reports

Structurized MCP exposes a small authoring loop for declarative .prism.md reports.
The report remains ordinary Markdown plus fenced prism JSON blocks; all referenced
data is resolved from one live managed Prism session.

## Tools

### get_prism_report_schema

Returns the current report version, front matter, every supported block type and field,
examples, and a starter template. It does not require an open Prism session.

Agents should use this tool instead of relying on a memorized schema. The current block
types are compound-table, compound-cards, structure-grid, scatter, column-summary, sar-1d, and sar-2d.

### Compound comparison cards

Use compound-cards for a focused medicinal-chemistry comparison of one to eight compounds.
Create a deliberately ordered row set first, then identify an optional stable reference row.
The reference is displayed first and numeric properties with showDelta enabled are compared
against it. A property may reference a numeric colorColumn such as an agent-defined endpoint
score; PrismLite applies its standard score coloring. Card selection is bidirectional with the
rest of the live workspace.

A minimal agent workflow is: choose a reference, create the comparison row set, select a small
property panel, optionally define score columns, author the compound-cards block, validate it,
and publish the report. Do not place large result sets into cards; use compound-table or
structure-grid for broader scans.

### validate_prism_report

Accepts session_id plus exactly one of:

- path: an existing .prism.md file;
- source: complete inline report source.

Validation is read-only and resolves against the current live workspace, including
runtime row sets, score columns, groupings, and materialized scaffold SAR columns.
The result contains valid, error and warning counts, metadata, referenced columns and
row sets, and structured diagnostics with source locations.

~~~json
{
  "session_id": "workspace",
  "path": "/analysis/series-a.prism.md"
}
~~~

An agent should repair every ERROR before saving or publishing. Warnings describe
conditions such as display limits and excluded SAR buckets and do not make the report
invalid.

### publish_prism_report

Accepts the same path-or-source input as validation. A valid report becomes a normal
live Prism report view in the managed workspace. In the combined PrismLite + MCP
application, the view appears in PrismLite through the existing workspace change
notification path.

Invalid reports return published: false with the complete validation result and do
not mutate the workspace. Publishing never replaces an existing view; repeated calls
receive unique view IDs.

### save_prism_report

Accepts session_id, complete inline source, and a new .prism.md output_path.
The source is validated against the live session before any file is created.

The output directory must already exist. Existing files are never overwritten. Invalid
reports return saved: false with diagnostics and create no file.

## Recommended workflow

1. Open a Prism snapshot and inspect its runtime columns and row sets.
2. Create analysis scopes, score columns, graph neighborhoods, or scaffold SAR columns.
3. Call get_prism_report_schema.
4. Author the report as a sidecar .prism.md file, or hold it as inline source.
5. Call validate_prism_report and repair all errors.
6. Call save_prism_report when the agent lacks direct filesystem access.
7. Call publish_prism_report to hand the interactive report to PrismLite immediately.

Reports are deliberately declarative. Compute results with registered Prism or
Structurized operations first, then reference the resulting stable column and row-set
IDs. Report blocks cannot run JavaScript, SQL, or arbitrary expressions.

The same guidance is available through get_structurized_tool_guide with
topic set to report_workflow.
