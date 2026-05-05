
# structurized review

## What the project currently does

`structurized-core` is currently the main implemented module of the Structurized stack.
It is a pairwise transformation extractor built around a strict preserved core.

Given two molecules `A` and `B`, the code:

1. obtains an atom mapping for a strict MCS,
2. treats the mapped atoms as preserved core,
3. finds connected components outside the core on both sides,
4. links those components through the core attachment atoms,
5. turns each connected tripartite component into one transformation group,
6. canonicalizes each group into a `TransformationSignature`.

The main implementation is in `structurized-core/src/main/java/tech/molecules/structurized/transforms/TransformationSplitter.java`.
`TransformationBench` runs this pairwise over many molecules.
`TransformationBenchDemo` provides a small OpenChemLib-backed example.

The public model is now split into top-level classes:

- `PairTransformation` for the full `A -> B` relation,
- `TransformationGroup` for one independent local edit,
- `TransformationSignature` for the canonical identity of one local edit,
- `TransformationType` for the group classification enum.

There is now also a first scaffold-mode package:

- `ScaffoldTemplate` for the unsubstituted scaffold graph plus symmetry classes,
- `ScaffoldAnalyzer` for scaffold-to-compound decomposition,
- `ScaffoldDecomposition` / `SubstitutionEvent` for scaffold-centric outputs.
- `ScaffoldDiscoveryEngine` for V1 scaffold candidate mining from compound neighborhoods.

## What is correct

- The overall decomposition model is coherent for medchem pair analysis: preserved core plus local edits.
- The splitter now separates independent edits by connectivity to shared attachment atoms.
- The demo now produces sensible outputs for simple examples such as `toluene -> fluorobenzene`, which is now one `REPLACEMENT` instead of multiple fake edits.
- Attachment dummy labels are now encoded into fragment IDCodes, which is required for stable multi-attachment signatures.

## Problems found and status

### Fixed in this pass

1. Bogus zero-change transformation groups
   The previous splitter treated isolated unchanged core atoms as standalone transformation groups. This produced many fake `MULTI_CENTER` edits. These are now filtered out.

2. Attachment labels were not encoded
   The previous fragment canonization used `new Canonizer(frag).getIDCode()`, but custom labels are only encoded when `Canonizer.ENCODE_ATOM_CUSTOM_LABELS` is enabled. This meant attachment ordering information was silently lost. This is now fixed.

3. `TransformationBench` pair enumeration bugs
   `symmetricPairs=true` previously included self-pairs and did not actually enumerate both directions correctly.
   `maxPairs` also only counted successful analyses, not attempted pairs.
   Both are now fixed.

4. Demo/provider hardening
   The MCS demo provider now checks for null/empty MCS results, asks `SSSearcher` for a first match explicitly, and validates strict atom identity in addition to bond identity.

5. Context feature extraction was split out from signatures
   The project now has a separate `tech.molecules.structurized.context` package with `ContextFeatureExtractor` and immutable feature records.
   `TransformationSignature` keeps the raw `contextShellIdcode`, while shell summary, attachment atom, and attachment pair features are computed externally.

6. Context shell encoding is now parent-aware
   The stored `contextShellIdcode` is no longer just a plain copied shell fragment.
   The splitter annotates every shell atom with parent-core ring, aromatic, pi, and smallest-ring-size data before canonization.
   Attachment atoms also carry encoded pairwise relation data, so multi-anchor context features can be reconstructed from the IDCode alone.

7. Expanded raw context is now stored separately
   Each `TransformationSignature` now also carries an `expandedRawContextIdcode` built with a larger radius (`3` by default, or larger if the canonical radius is already larger).
   This broader neighborhood is intended for downstream feature derivation and exploratory analysis, while the compact `contextShellIdcode` remains the canonical signature context.

8. First scaffold-mode implementation exists
   The code can now analyze one predefined scaffold against one compound by treating the scaffold as the preserved core and reusing the existing splitter.
   The result is exposed as scaffold-centric `SubstitutionEvent`s with concrete scaffold atom indices and scaffold symmetry classes.

9. First scaffold discovery implementation exists
   The code can now mine scaffold candidates from a set of compounds using OpenChemLib FFP512 neighborhoods, strict pairwise MCS-derived cores, canonical scaffold merging, and local scaffold-support rescoring.
   This is intentionally a V1 local discovery strategy, not a full global scaffold-mining pipeline.

### Still important limitations

1. `featureMask` is not implemented yet
   `featureMask` is currently carried into the signature hash, but it does not change the context-shell extraction itself.
   So the code exposes a granularity control that is only metadata right now.

2. Standardization is assumed, not implemented
   The docs and comments assume normalized molecules, but the project does not yet contain a standardization pipeline.
   For medchem data this matters for salts, charge states, aromaticity handling, and tautomer policy.

3. Symmetry handling is still minimal
   The current MCS-to-signature path uses the first valid substructure match for the MCS in each molecule.
   This is acceptable for a first pairwise engine, but it is not yet enough for robust scaffold exit-vector aggregation across symmetry-equivalent positions.

4. No scaffold-centered layer yet
   A first scaffold-centered decomposition layer and a first local discovery engine now exist.
   However, global support expansion, scaffold hierarchy, multi-compound core mining, and richer exit-vector analytics are not implemented yet.

5. Context shell remains structurally truncated
   The stored shell is still a copied shell fragment, not the full original core.
   Ring/aromatic/pi/ring-size semantics are now preserved explicitly through parent-aware annotations, but anything not encoded into that payload is still unavailable from the IDCode alone.
   If later algorithms need richer preserved-core semantics, the context payload or surrounding data model will need to grow further.

## Recommended next steps

1. Implement a real normalization layer before MCS calculation.
2. Either remove `featureMask` from the signature API or implement a true mask-aware abstraction layer on top of the raw context object.
3. Add explicit symmetry-class handling for scaffold atoms and exit vectors.
4. Generalize the current core-relative extractor so the core can be either:
   - a pairwise MCS, or
   - a predefined scaffold template.

## Local verification done

- Direct `javac` compilation against local `openchemlib-2025.5.1.jar`
- Demo run of `TransformationBenchDemo`
- 15 local JUnit tests executed through the local JUnit launcher jars
