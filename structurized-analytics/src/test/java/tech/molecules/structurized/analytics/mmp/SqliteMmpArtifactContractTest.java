package tech.molecules.structurized.analytics.mmp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.molecules.structurized.mmp.MmpMiningConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteMmpArtifactContractTest {
    @Test
    void capabilityMetadataReadsAreBoundedAndReadOnly(@TempDir Path tempDir) throws Exception {
        Path artifact=tempDir.resolve("metadata.sqlite");
        var time=java.time.Instant.parse("2026-01-01T00:00:00Z");
        try(var repository=SqliteMmpAnalyticsRepository.open(artifact)) {
            for(String id:java.util.List.of("a","b")) {
                repository.saveUniverse(new MmpUniverse(id,"Synthetic universe",java.util.List.of(),"synthetic-hash",time,null),java.util.List.of());
                repository.saveStatsRun(new MmpEndpointStatsRun(id,id,"synthetic-set",id,"synthetic-hash","synthetic-stats",time,0,0,0,0,null),java.util.List.of());
            }
        }
        byte[] before=Files.readAllBytes(artifact);
        try(var repository=SqliteMmpAnalyticsRepository.openReadOnly(artifact)) {
            assertEquals("4",repository.findArtifactMetadata("schema_version").orElseThrow());
            assertTrue(repository.findArtifactMetadata("missing").isEmpty());
            assertEquals(1,repository.listUniverses(1).size());assertEquals(2,repository.listUniverses().size());
            assertEquals(1,repository.listStatsRuns(1).size());assertEquals(2,repository.listStatsRuns().size());
            assertThrows(IllegalArgumentException.class,()->repository.listUniverses(0));
            assertThrows(IllegalArgumentException.class,()->repository.listStatsRuns(0));
        }
        org.junit.jupiter.api.Assertions.assertArrayEquals(before,Files.readAllBytes(artifact));
    }

    @Test
    void persistsResolvedConfigAndReadsArtifactWithoutMutation(@TempDir Path tempDir) throws Exception {
        Path artifact = tempDir.resolve("mmp.sqlite");
        MmpMiningConfig config = MmpMiningConfig.defaults().toBuilder()
                .maxCuts(1)
                .minTransformSupport(7)
                .maxVariableHeavyAtoms(9)
                .build();
        String hash = MmpAnalyticsHashes.mmpConfigHash(config);
        try (SqliteMmpAnalyticsRepository repository = SqliteMmpAnalyticsRepository.open(artifact)) {
            repository.saveMiningConfig(hash, MmpMiningConfigSnapshot.from(config));
        }
        long size = Files.size(artifact);
        FileTime modified = Files.getLastModifiedTime(artifact);

        try (SqliteMmpAnalyticsRepository repository = SqliteMmpAnalyticsRepository.openReadOnly(artifact)) {
            MmpMiningConfig loaded = repository.findMiningConfig(hash).orElseThrow().toMiningConfig();
            assertEquals(hash, MmpAnalyticsHashes.mmpConfigHash(loaded));
            assertEquals(4, repository.artifactSchemaVersion());
            assertTrue(repository.listStatsRuns().isEmpty());
        }

        assertEquals(size, Files.size(artifact));
        assertEquals(modified, Files.getLastModifiedTime(artifact));
        assertThrows(IllegalStateException.class,
                () -> SqliteMmpAnalyticsRepository.openReadOnly(tempDir.resolve("missing.sqlite")));
        assertTrue(Files.notExists(tempDir.resolve("missing.sqlite")));
    }
}
