# Agent Exploration Trace

Structurized can expose a session-scoped stream of observable MCP chemistry tool activity. The stream is intended for agent observability and visual replay. It records which tool was used and which stable compound identities were requested or returned; it never records prompts or model reasoning.

## Composition

Recording is opt-in. Existing `McpJsonRpcHandler` factories create no files. A host that wants a trace creates one shared publisher, optionally attaches listeners, and injects it into the handler:

```java
AgentExplorationTrace trace = new AgentExplorationTrace();
try (JsonlAgentExplorationRecorder recorder =
         JsonlAgentExplorationRecorder.open(Path.of("agent-session.jsonl"), trace)) {
    McpJsonRpcHandler handler = McpJsonRpcHandler.create(repositories, prismBridge, trace);
    handler.runStdio(System.in, System.out);
}
```

The same `AgentExplorationTrace` may be shared with a live UI subscriber. Subscriber failures are isolated and cannot change the chemistry operation being observed. Failure to create the requested recording file is reported immediately. A later write failure is available through `recorder.failure()` and does not fail MCP calls.

## Lifecycle and identity

Each accepted chemistry `tools/call` produces a `STARTED` event and then either a correlated `COMPLETED` or `FAILED` event. Events contain a trace ID, monotonic sequence, invocation ID, timestamp, elapsed trace time, duration for terminal events, tool name, activity type, display label, typed element references, and an error code when applicable.

Element references use stable identities and retain their owner:

- `PRISM_ROW`: a stable row ID within a managed Prism session.
- `PRISM_SUBJECT`: a canonical subject ID within a Prism dataset.
- `REPOSITORY_STRUCTURE`: a structure ID within an AI repository.
- `PRISM_MOLECULE_DOCUMENT`: a proposed or scratch molecule document within a managed Prism session.

Attention roles are semantic rather than visual: `FOCUS` is an explicitly inspected element, `TOUCHED` participates in an analysis, `RETURNED` merely appears in a result, and `PROPOSED` is a newly created molecule document. A player decides how these roles map to intensity, animation, and decay.

## JSONL format

A recording begins with one `trace_header`, followed by independently parseable event records. Schema version 1 uses snake-case fields and ISO-8601 UTC timestamps:

```json
{"record_type":"trace_header","format":"structurized-agent-exploration","schema_version":1,"trace_id":"...","started_at":"2026-08-19T12:04:00Z"}
{"record_type":"event","schema_version":1,"trace_id":"...","sequence":1,"invocation_id":"...","occurred_at":"2026-08-19T12:04:01Z","elapsed_ms":1000,"phase":"started","tool_name":"inspect_prism_graph_neighborhood","activity_type":"graph_expand","label":"Inspect prism graph neighborhood","references":[{"kind":"prism_row","context_id":"project","element_id":"A19","role":"focus","source":"request"}]}
```

The recorder flushes every line and refuses to overwrite an existing path. A truncated recording remains readable up to its final complete line.

## Data boundary

All chemistry calls are represented at tool level, but only curated identifier fields from known compound-bearing tools are enriched. Unknown or administrative tools remain valid activity events with no element references. The extractor does not make additional row-set or repository queries.

Raw MCP arguments and results, SMILES/SMARTS/IDCodes, endpoint values, arbitrary metadata, file paths, prompts, and chain-of-thought are excluded. Failed calls record a normalized error code, not the exception message or rejected payload.
