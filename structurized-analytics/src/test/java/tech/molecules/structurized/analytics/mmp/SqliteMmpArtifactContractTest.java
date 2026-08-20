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
            assertEquals(2, repository.artifactSchemaVersion());
            assertTrue(repository.listStatsRuns().isEmpty());
        }

        assertEquals(size, Files.size(artifact));
        assertEquals(modified, Files.getLastModifiedTime(artifact));
        assertThrows(IllegalStateException.class,
                () -> SqliteMmpAnalyticsRepository.openReadOnly(tempDir.resolve("missing.sqlite")));
        assertTrue(Files.notExists(tempDir.resolve("missing.sqlite")));
    }
}
