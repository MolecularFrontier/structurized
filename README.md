structurized
=================

Modular Java 23 Maven stack for cheminformatics and downstream analytics.

Project coordinates:
- Parent build: `tech.molecules:structurized:0.3.7`
- Core module: `tech.molecules:structurized-core:0.3.7`
- AI inspection module: `tech.molecules:structurized-ai-core:0.3.7`
- AI Prism bridge module: `tech.molecules:structurized-ai-prism:0.3.7`
- AI MCP adapter module: `tech.molecules:structurized-ai-mcp:0.3.7`
- Analytics module: `tech.molecules:structurized-analytics:0.3.7`
- Workbench module: `tech.molecules:structurized-workbench:0.3.7`

Build
-----
- Requires Java 23 and Maven 3.9+
- Commands:
- `mvn package` – build all modules
- `mvn test` – run all module tests
- `mvn -pl structurized-core test` – run core-module tests only
- `mvn -pl structurized-ai-core -am test` – run the AI inspection module and its dependencies
- `mvn -pl structurized-ai-prism -am test` – run the AI Prism bridge and its dependencies
- `mvn -pl structurized-ai-mcp -am test` – run the AI MCP adapter and its dependencies
- `mvn -pl structurized-ai-mcp -am -Dgpg.skip=true -DskipTests package` – build the MCP standalone jar
- `java -jar structurized-ai-mcp/target/structurized-ai-mcp-0.3.7-standalone.jar` – launch the stdio MCP adapter
- `mvn -pl structurized-prismlite-app -am -Dgpg.skip=true -DskipTests package` – build the shared PrismLite + MCP desktop jar
- `java -jar structurized-prismlite-app/target/structurized-prismlite-app-0.3.7-standalone.jar [--session-id=workspace] [--record-agent-trace=/path/session.jsonl | --replay-agent-trace=/path/session.jsonl] [dataset]` – launch PrismLite and stdio MCP over one managed session
- The desktop `Agent Trace` tab visualizes live or recorded tool activity over the Project River and the selected MMP/similarity graph, with presentation-timed playback, scrubbing, and speed controls.
- `Export Bundle` writes an opt-in `.agenttrace.zip` containing the trace, selected graph snapshot, proposed molecule sidecars, and a dataset fingerprint. The project dataset itself remains external.
- `mvn -pl structurized-core exec:java -Dexec.mainClass=tech.molecules.structurized.gui.PairTransformationSwingApp` – launch the A/B transformation debugger
- `mvn -pl structurized-workbench -am exec:java -Dexec.mainClass=tech.molecules.structurized.workbench.PrismWorkbenchApp -Dexec.args=/path/to/prism-tsv-folder` – launch the PRISM workbench
- The workbench includes MMP statistics browsing plus a `Recommendations` editor. Draw or paste a structure, select editable atoms with the lasso tool, choose endpoint directions, and apply persisted one-cut/two-cut transformations in a cancellable background search.
- Recommendation selection modes are `Editable region`, `Exact fragment`, `Attachment vicinity`, and `All sites`. Selecting a proposal shows the persisted endpoint-valued example pairs as reconstructed full structures.

Release
-------
- Releases are published by GitHub Actions from pushed version tags or manual `workflow_dispatch`.
- Publishing uses the Sonatype Central Publisher Portal flow and waits until upload completion.
- Artifacts are signed with the configured GitHub Actions GPG secrets and include sources and javadocs.
- Example release tag:
- `mvn versions:set -DnewVersion=0.1.1`
- `git tag v0.1.1`
- `git push origin v0.1.1`

Usage
-----
Example of counting heavy atoms from a SMILES string using OpenChemLib:

```java
import tech.molecules.structurized.OpenChemLibUtil;

public class Demo {
  public static void main(String[] args) {
    int atoms = OpenChemLibUtil.atomCountFromSmiles("c1ccccc1");
    System.out.println(atoms); // 6
  }
}
```

Notes
-----
- Base package: `tech.molecules.structurized`
- Java release: 23
- Parent POM: [`pom.xml`](/home/lithom/dev_chem/structurized/pom.xml)
- Core module POM: [`structurized-core/pom.xml`](/home/lithom/dev_chem/structurized/structurized-core/pom.xml)
- Analytics module POM: [`structurized-analytics/pom.xml`](/home/lithom/dev_chem/structurized/structurized-analytics/pom.xml)
- Workbench module POM: [`structurized-workbench/pom.xml`](/home/lithom/dev_chem/structurized/structurized-workbench/pom.xml)
- Existing cheminformatics implementation now lives in `structurized-core`
- Addressable AI molecular graph inspection lives in `structurized-ai-core`
- Prism subject sets can be materialized into AI chemistry repositories via `structurized-ai-prism`
- The stdio MCP adapter for those operations lives in `structurized-ai-mcp`
- Main pairwise engine: `tech.molecules.structurized.transforms.TransformationSplitter`
- Scaffold-mode entry point: `tech.molecules.structurized.scaffolds.ScaffoldAnalyzer`
- Scaffold discovery entry point: `tech.molecules.structurized.scaffolds.ScaffoldDiscoveryEngine`
- Internal Swing validation GUI: `tech.molecules.structurized.gui.ScaffoldDiscoverySwingApp`
- Internal A/B transformation debugger: `tech.molecules.structurized.gui.PairTransformationSwingApp`
- PRISM now lives in the separate `prism` repository as its own multi-module project
- `structurized-analytics` is reserved for analytics that combine structural methods with external endpoint/protocol layers
- `structurized-workbench` contains reusable Swing components, a PRISM repository explorer app, and the first MMP endpoint-statistics workbench
- Conceptual overview: `docs/STRUCTURIZED_CONCEPTS.md`
- Prism managed-session integration: [`docs/PRISM_SESSION_INTEGRATION.md`](docs/PRISM_SESSION_INTEGRATION.md)
- Parent-aware context shell spec: `docs/CONTEXT_SHELL_ENCODING.md`
- Scaffold-mode notes: `docs/SCAFFOLD_MODE.md`
- Scaffold discovery notes: `docs/SCAFFOLD_DISCOVERY.md`
- Series decomposition notes: `docs/SERIES_DECOMPOSITION.md`
- Fast AI clustering notes: `docs/AI_CLUSTERING.md`
- MCP / Kiro setup notes: `docs/MCP_KIRO_SETUP.md`
- GUI test app notes: `docs/GUI_TEST_APP.md`
- Review and project notes: `docs/STRUCTURIZED_REVIEW.md`
- Verified OpenChemLib usage notes: `docs/OPENCHEMLIB_METHODS.md`
- Accessing existing MMP analytics artifacts: [`docs/mmp_agent_workflow.md`](docs/mmp_agent_workflow.md)

MMP artifact access workflow
----------------------------
The stdio MCP server can consume persisted SQLite MMP statistics without modifying them:

1. `open_mmp_artifact` opens any existing regular file read-only and returns a
   session-scoped handle. `list_mmp_artifacts` and `describe_mmp_artifact` expose
   identity, universes, endpoint runs, counts, and the resolved mining configuration.
2. `recommend_mmp_transformations` accepts ordinary SMILES for all-sites searches or
   atom-mapped SMILES plus `selected_atom_maps` for targeted modes. One endpoint run is
   primary for ranking; additional runs provide evidence only.
3. Results are generated compounds backed by observed MMP statistics, not property
   predictions. Compact/full evidence and managed JSON file output are available.

New artifacts persist the complete resolved mining configuration. Legacy artifacts remain
browseable; recommendation requires defaults or explicit curated overrides whose hash
matches the selected endpoint run. Artifact identity is checked again on every operation.
