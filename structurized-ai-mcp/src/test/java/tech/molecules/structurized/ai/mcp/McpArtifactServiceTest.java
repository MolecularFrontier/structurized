package tech.molecules.structurized.ai.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.molecules.structurized.ai.model.ChemOperationException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpArtifactServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void writesJsonArtifactsUnderManagedBaseWithSafeRelativeNames() throws Exception {
        McpArtifactService service = new McpArtifactService(mapper, tempDir);

        McpArtifactService.ArtifactRecord record = service.writeJson(
                "search_substructure",
                "series_A/matches.json",
                false,
                Map.of("status", "ok"),
                1
        );

        Path path = Path.of(record.path());
        assertTrue(path.startsWith(tempDir.toRealPath()));
        assertEquals("series_A/matches.json", record.relativePath());
        assertEquals("json", record.format());
        assertEquals(1, record.rowCount());
        assertEquals("ok", mapper.readTree(path.toFile()).get("status").asText());
    }

    @Test
    void autoSuffixesCollisionsUnlessOverwriteIsExplicit() throws Exception {
        McpArtifactService service = new McpArtifactService(mapper, tempDir);

        McpArtifactService.ArtifactRecord first = service.writeJson("tool", "same.json", false, Map.of("n", 1), 1);
        McpArtifactService.ArtifactRecord second = service.writeJson("tool", "same.json", false, Map.of("n", 2), 1);
        McpArtifactService.ArtifactRecord overwrite = service.writeJson("tool", "same.json", true, Map.of("n", 3), 1);

        assertNotEquals(first.path(), second.path());
        assertTrue(second.relativePath().endsWith("same_2.json"));
        assertEquals(first.path(), overwrite.path());
        assertEquals(3, mapper.readTree(Path.of(first.path()).toFile()).get("n").asInt());
    }

    @Test
    void rejectsUnsafeRelativeNames() {
        McpArtifactService service = new McpArtifactService(mapper, tempDir);

        assertThrows(ChemOperationException.class, () -> service.writeJson("tool", "/tmp/out.json", false, Map.of(), 0));
        assertThrows(ChemOperationException.class, () -> service.writeJson("tool", "../out.json", false, Map.of(), 0));
        assertThrows(ChemOperationException.class, () -> service.writeJson("tool", "a/../out.json", false, Map.of(), 0));
        assertThrows(ChemOperationException.class, () -> service.writeJson("tool", "./out.json", false, Map.of(), 0));
    }

    @Test
    void rejectsSymlinkTraversalInsideBase() throws Exception {
        McpArtifactService service = new McpArtifactService(mapper, tempDir);
        Path outside = Files.createDirectories(tempDir.resolve("outside_target"));
        Path link = tempDir.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException e) {
            return;
        }

        ChemOperationException exception = assertThrows(
                ChemOperationException.class,
                () -> service.writeJson("tool", "link/out.json", false, Map.of(), 0)
        );

        assertEquals("invalid_artifact_path", exception.code());
    }
}
