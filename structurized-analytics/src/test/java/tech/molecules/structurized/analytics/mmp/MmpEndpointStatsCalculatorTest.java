package tech.molecules.structurized.analytics.mmp;

import com.actelion.research.chem.Molecule;
import com.actelion.research.chem.SmilesParser;
import com.actelion.research.chem.StereoMolecule;
import org.junit.jupiter.api.Test;
import tech.molecules.structurized.mmp.MmpMiningConfig;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MmpEndpointStatsCalculatorTest {
    private static final Instant CREATED_AT = Instant.parse("2026-08-06T10:15:30Z");
    private static final Clock CLOCK = Clock.fixed(CREATED_AT, ZoneOffset.UTC);

    @Test
    void computesCompleteDeterministicUnionAndPreservesRequestedEndpointOrder() throws Exception {
        MmpAnalyticsSnapshot snapshot = snapshot();
        MmpEndpointStatsConfig config = MmpEndpointStatsConfig.builder()
                .endpointIds(List.of("logd", "ic50"))
                .putEndpointSubjectSetId("ic50", "assay:ic50:measured")
                .putEndpointSubjectSetId("logd", "assay:logd:measured")
                .build();
        MmpEndpointStatsCalculator calculator = new MmpEndpointStatsCalculator(CLOCK);

        MmpAnalyticsComputation first = calculator.compute(snapshot, config, mmpConfig());
        MmpAnalyticsComputation second = calculator.compute(snapshot, config, mmpConfig());

        assertEquals(first, second);
        assertEquals(1, first.universes().size());
        assertEquals(List.of("logd", "ic50"), first.endpointStats().stream()
                .map(stats -> stats.run().endpointId()).toList());
        assertTrue(first.endpointStats().stream()
                .allMatch(stats -> stats.run().universeId()
                        .equals(first.universes().getFirst().universe().universeId())));
        assertTrue(first.endpointStats().stream()
                .allMatch(stats -> stats.run().createdAt().equals(CREATED_AT)));
        assertFalse(first.universes().getFirst().fragmentationRecords().isEmpty());
        assertFalse(first.universes().getFirst().pairs().isEmpty());
        assertFalse(first.endpointStats().get(1).transformStats().isEmpty());
        assertEquals(List.of(
                "Endpoint 'logd' is missing usable numeric values for 1 of 2 subjects"
        ), first.warnings());
    }

    @Test
    void computesIndependentPerEndpointUniverses() throws Exception {
        MmpEndpointStatsConfig config = MmpEndpointStatsConfig.builder()
                .universeMode(MmpUniverseMode.PER_ENDPOINT)
                .putEndpointSubjectSetId("ic50", "assay:ic50:measured")
                .putEndpointSubjectSetId("logd", "assay:logd:measured")
                .build();

        MmpAnalyticsComputation computation = new MmpEndpointStatsCalculator(CLOCK)
                .compute(snapshot(), config, mmpConfig());

        assertEquals(2, computation.universes().size());
        assertEquals(2, computation.endpointStats().size());
        for (int i = 0; i < computation.endpointStats().size(); i++) {
            assertEquals(computation.universes().get(i).universe().universeId(),
                    computation.endpointStats().get(i).run().universeId());
        }
    }

    @Test
    void reportsMissingStructuresAndValuesDeterministically() throws Exception {
        MmpAnalyticsSnapshot base = snapshot();
        Map<String, StereoMolecule> incompleteStructures = new LinkedHashMap<>(base.structuresBySubjectId());
        incompleteStructures.remove("ethylbenzene");
        MmpAnalyticsSnapshot incomplete = new MmpAnalyticsSnapshot(
                base.sourceId(), incompleteStructures, base.endpoints());

        MmpAnalyticsComputation computation = new MmpEndpointStatsCalculator(CLOCK).compute(
                incomplete,
                MmpEndpointStatsConfig.builder()
                        .putEndpointSubjectSetId("ic50", "assay:ic50:measured")
                        .putEndpointSubjectSetId("logd", "assay:logd:measured")
                        .build(),
                mmpConfig()
        );

        assertEquals(1, computation.summary().missingStructureCount());
        assertEquals(2, computation.warnings().size());
        assertTrue(computation.warnings().getFirst().startsWith(
                "Skipped 1 subjects without structures for universe mmp-union-"));
        assertEquals("Endpoint 'logd' is missing usable numeric values for 1 of 2 subjects",
                computation.warnings().get(1));
    }

    @Test
    void snapshotDefensivelyCopiesChemicalStructures() throws Exception {
        StereoMolecule original = parse("Cc1ccccc1");
        int expectedAtoms = original.getAllAtoms();
        MmpAnalyticsSnapshot snapshot = new MmpAnalyticsSnapshot(
                "source", Map.of("a", original), List.of());

        original.addAtom(8);
        StereoMolecule returned = snapshot.structuresBySubjectId().get("a");
        returned.addAtom(7);

        assertEquals(expectedAtoms, snapshot.structuresBySubjectId().get("a").getAllAtoms());
    }

    @Test
    void rejectsInvalidSnapshotDataAndSubjectSetMismatch() throws Exception {
        MmpEndpointSnapshot endpoint = new MmpEndpointSnapshot(
                "e", "Endpoint", "set", List.of("a"), Map.of("a", 1.0));

        assertThrows(IllegalArgumentException.class, () -> new MmpEndpointSnapshot(
                "e", "Endpoint", "set", List.of("a", "a"), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new MmpEndpointSnapshot(
                "e", "Endpoint", "set", List.of("a"), Map.of("other", 1.0)));
        assertThrows(IllegalArgumentException.class, () -> new MmpEndpointSnapshot(
                "e", "Endpoint", "set", List.of("a"), Map.of("a", Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> new MmpAnalyticsSnapshot(
                "source", Map.of("a", parse("C")), List.of(endpoint, endpoint)));

        MmpAnalyticsSnapshot snapshot = new MmpAnalyticsSnapshot(
                "source", Map.of("a", parse("C")), List.of(endpoint));
        MmpEndpointStatsConfig mismatched = MmpEndpointStatsConfig.builder()
                .putEndpointSubjectSetId("e", "different-set")
                .build();
        assertThrows(IllegalArgumentException.class, () -> new MmpEndpointStatsCalculator(CLOCK)
                .compute(snapshot, mismatched, mmpConfig()));
    }

    private static MmpAnalyticsSnapshot snapshot() throws Exception {
        Map<String, StereoMolecule> structures = new LinkedHashMap<>();
        structures.put("toluene", parse("Cc1ccccc1"));
        structures.put("ethylbenzene", parse("CCc1ccccc1"));
        structures.put("anisole", parse("COc1ccccc1"));
        MmpEndpointSnapshot ic50 = new MmpEndpointSnapshot(
                "ic50",
                "IC50",
                "assay:ic50:measured",
                List.of("toluene", "ethylbenzene", "anisole"),
                Map.of("toluene", 1.0, "ethylbenzene", 3.5, "anisole", 2.0)
        );
        MmpEndpointSnapshot logd = new MmpEndpointSnapshot(
                "logd",
                "LOGD",
                "assay:logd:measured",
                List.of("toluene", "anisole"),
                Map.of("toluene", 2.2)
        );
        return new MmpAnalyticsSnapshot("test-snapshot", structures, List.of(ic50, logd));
    }

    private static MmpMiningConfig mmpConfig() {
        return MmpMiningConfig.builder()
                .maxCuts(1)
                .minKeyHeavyAtoms(6)
                .maxVariableHeavyAtoms(4)
                .maxVariableToMolHeavyAtomFraction(1.0)
                .minTransformSupport(1)
                .build();
    }

    private static StereoMolecule parse(String smiles) throws Exception {
        StereoMolecule molecule = new StereoMolecule();
        new SmilesParser().parse(molecule, smiles);
        molecule.ensureHelperArrays(Molecule.cHelperRings);
        return molecule;
    }
}
