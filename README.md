structurized
=================

Modular Java 23 Maven stack for cheminformatics and downstream analytics.

Project coordinates:
- Parent build: `tech.molecules:structurized:0.1.1`
- Core module: `tech.molecules:structurized-core:0.1.1`
- Analytics module: `tech.molecules:structurized-analytics:0.1.1`
- Workbench module: `tech.molecules:structurized-workbench:0.1.1`

Build
-----
- Requires Java 23 and Maven 3.9+
- Commands:
- `mvn package` – build all modules
- `mvn test` – run all module tests
- `mvn -pl structurized-core test` – run core-module tests only
- `mvn -pl structurized-workbench -am exec:java -Dexec.mainClass=tech.molecules.structurized.workbench.PrismWorkbenchApp -Dexec.args=/path/to/prism-tsv-folder` – launch the PRISM workbench
- The workbench includes an `MMP Analytics` tab for selecting numeric PRISM endpoints, mapping measured subject sets, computing MMP endpoint statistics into SQLite, and browsing persisted runs.

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
- Main pairwise engine: `tech.molecules.structurized.transforms.TransformationSplitter`
- Scaffold-mode entry point: `tech.molecules.structurized.scaffolds.ScaffoldAnalyzer`
- Scaffold discovery entry point: `tech.molecules.structurized.scaffolds.ScaffoldDiscoveryEngine`
- Internal Swing validation GUI: `tech.molecules.structurized.gui.ScaffoldDiscoverySwingApp`
- PRISM now lives in the separate `prism` repository as its own multi-module project
- `structurized-analytics` is reserved for analytics that combine structural methods with external endpoint/protocol layers
- `structurized-workbench` contains reusable Swing components, a PRISM repository explorer app, and the first MMP endpoint-statistics workbench
- Conceptual overview: `docs/STRUCTURIZED_CONCEPTS.md`
- Parent-aware context shell spec: `docs/CONTEXT_SHELL_ENCODING.md`
- Scaffold-mode notes: `docs/SCAFFOLD_MODE.md`
- Scaffold discovery notes: `docs/SCAFFOLD_DISCOVERY.md`
- GUI test app notes: `docs/GUI_TEST_APP.md`
- Review and project notes: `docs/STRUCTURIZED_REVIEW.md`
- Verified OpenChemLib usage notes: `docs/OPENCHEMLIB_METHODS.md`
