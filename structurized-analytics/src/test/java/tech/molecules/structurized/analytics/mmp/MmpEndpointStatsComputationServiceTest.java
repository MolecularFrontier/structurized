package tech.molecules.structurized.analytics.mmp;

import com.actelion.research.chem.Molecule;
import com.actelion.research.chem.SmilesParser;
import com.actelion.research.chem.StereoMolecule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.molecules.structurized.mmp.MmpMiningConfig;
import tech.molecules.structurized.prism.model.EndpointDataType;
import tech.molecules.structurized.prism.model.EndpointDefinition;
import tech.molecules.structurized.prism.model.EndpointType;
import tech.molecules.structurized.prism.model.EvaluationMode;
import tech.molecules.structurized.prism.provider.SubjectRecord;
import tech.molecules.structurized.prism.provider.SubjectSet;
import tech.molecules.structurized.prism.provider.inmemory.InMemoryPrismDataset;
import tech.molecules.structurized.prism.query.EndpointValueRecord;
import tech.molecules.structurized.prism.result.NumericResult;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MmpEndpointStatsComputationServiceTest {

    @Test
    void computesUnionUniverseAndPersistsEndpointStats(@TempDir Path tempDir) throws Exception {
        InMemoryPrismDataset dataset = dataset();
        try (SqliteMmpAnalyticsRepository repository = SqliteMmpAnalyticsRepository.open(tempDir.resolve("mmp.db"))) {
            MmpEndpointStatsComputationService service = new MmpEndpointStatsComputationService(
                    dataset.endpointProvider(),
                    dataset.subjectSetProvider(),
                    structureProvider(),
                    repository,
                    repository,
                    repository
            );

            MmpEndpointStatsComputationResult result = service.computeAndPersist(
                    MmpEndpointStatsConfig.builder()
                            .putEndpointSubjectSetId("ic50", "assay:ic50:measured")
                            .putEndpointSubjectSetId("logd", "assay:logd:measured")
                            .batchSize(2)
                            .build(),
                    mmpConfig()
            );

            assertEquals(1, result.universes().size());
            assertEquals(2, result.statsRuns().size());
            assertEquals(3, result.structuralSubjectCount());
            assertEquals(0, result.missingStructureCount());
            assertFalse(repository.listPairs(result.universes().getFirst().universeId()).isEmpty());
            assertEquals(List.of("ethylbenzene", "anisole", "toluene").stream().sorted().toList(),
                    repository.listUniverseSubjects(result.universes().getFirst().universeId()).stream().sorted().toList());
            assertEquals(1, repository.listUniverses().size());
            assertEquals(2, repository.listStatsRuns().size());

            for (MmpEndpointStatsRun run : result.statsRuns()) {
                assertTrue(repository.findStatsRun(run.runId()).isPresent());
                assertFalse(repository.listTransformStats(run.runId()).isEmpty());
            }
        }
    }

    @Test
    void canComputeSeparatePerEndpointUniverses(@TempDir Path tempDir) throws Exception {
        InMemoryPrismDataset dataset = dataset();
        try (SqliteMmpAnalyticsRepository repository = SqliteMmpAnalyticsRepository.open(tempDir.resolve("mmp.db"))) {
            MmpEndpointStatsComputationService service = new MmpEndpointStatsComputationService(
                    dataset.endpointProvider(),
                    dataset.subjectSetProvider(),
                    structureProvider(),
                    repository,
                    repository,
                    repository
            );

            MmpEndpointStatsComputationResult result = service.computeAndPersist(
                    MmpEndpointStatsConfig.builder()
                            .universeMode(MmpUniverseMode.PER_ENDPOINT)
                            .putEndpointSubjectSetId("ic50", "assay:ic50:measured")
                            .putEndpointSubjectSetId("logd", "assay:logd:measured")
                            .build(),
                    mmpConfig()
            );

            assertEquals(2, result.universes().size());
            assertEquals(2, result.statsRuns().size());
            assertTrue(result.universes().stream()
                    .allMatch(universe -> repository.findUniverse(universe.universeId()).isPresent()));
        }
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

    private static InMemoryPrismDataset dataset() {
        EndpointDefinition ic50 = numericEndpoint("ic50");
        EndpointDefinition logd = numericEndpoint("logd");
        SubjectSet ic50Set = measuredSet("assay:ic50:measured", "IC50 measured");
        SubjectSet logdSet = measuredSet("assay:logd:measured", "LogD measured");

        return InMemoryPrismDataset.builder()
                .addEndpointDefinition(ic50)
                .addEndpointDefinition(logd)
                .addSubjectRecord(subject("toluene"))
                .addSubjectRecord(subject("ethylbenzene"))
                .addSubjectRecord(subject("anisole"))
                .addSubjectSet(ic50Set)
                .addSubjectSet(logdSet)
                .addSubjectMembership(ic50Set.getId(), "toluene")
                .addSubjectMembership(ic50Set.getId(), "ethylbenzene")
                .addSubjectMembership(ic50Set.getId(), "anisole")
                .addSubjectMembership(logdSet.getId(), "toluene")
                .addSubjectMembership(logdSet.getId(), "anisole")
                .addEndpointValue(value("toluene", "ic50", 1.0))
                .addEndpointValue(value("ethylbenzene", "ic50", 3.5))
                .addEndpointValue(value("anisole", "ic50", 2.0))
                .addEndpointValue(value("toluene", "logd", 2.2))
                .addEndpointValue(value("anisole", "logd", 1.7))
                .build();
    }

    private static EndpointDefinition numericEndpoint(String id) {
        return EndpointDefinition.builder()
                .id(id)
                .name(id.toUpperCase())
                .path("assay/" + id)
                .datatype(EndpointDataType.NUMERIC)
                .endpointType(EndpointType.MEASURED)
                .evaluationMode(EvaluationMode.IMMEDIATE)
                .build();
    }

    private static SubjectRecord subject(String id) {
        return SubjectRecord.builder().subjectId(id).build();
    }

    private static SubjectSet measuredSet(String id, String name) {
        return SubjectSet.builder()
                .id(id)
                .name(name)
                .setType("ASSAY_MEASURED")
                .subjectSetScope("ASSAYS")
                .build();
    }

    private static EndpointValueRecord value(String subjectId, String endpointId, double value) {
        return EndpointValueRecord.builder()
                .subjectId(subjectId)
                .endpointId(endpointId)
                .result(NumericResult.builder().mean(value).build())
                .build();
    }

    private static StructureProvider structureProvider() throws Exception {
        Map<String, StereoMolecule> structures = new LinkedHashMap<>();
        structures.put("toluene", parse("Cc1ccccc1"));
        structures.put("ethylbenzene", parse("CCc1ccccc1"));
        structures.put("anisole", parse("COc1ccccc1"));
        return subjectId -> Optional.ofNullable(structures.get(subjectId)).map(StereoMolecule::new);
    }

    private static StereoMolecule parse(String smiles) throws Exception {
        StereoMolecule molecule = new StereoMolecule();
        new SmilesParser().parse(molecule, smiles);
        molecule.ensureHelperArrays(Molecule.cHelperRings);
        return molecule;
    }
}
