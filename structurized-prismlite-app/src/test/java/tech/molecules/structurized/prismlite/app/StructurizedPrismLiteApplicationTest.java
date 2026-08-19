package tech.molecules.structurized.prismlite.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StructurizedPrismLiteApplicationTest {
    @TempDir Path temp;

    @Test
    void parsesRecordAndReplayOptions() throws Exception {
        Path dataset = Files.createDirectory(temp.resolve("dataset"));
        Path replay = Files.createFile(temp.resolve("trace.jsonl"));

        var record = StructurizedPrismLiteApplication.LaunchOptions.parse(new String[]{
                "--session-id=demo", "--record-agent-trace=" + temp.resolve("new.jsonl"), dataset.toString()});
        var replayed = StructurizedPrismLiteApplication.LaunchOptions.parse(new String[]{
                "--replay-agent-trace=" + replay, dataset.toString()});

        assertEquals("demo", record.sessionId());
        assertEquals(temp.resolve("new.jsonl"), record.recordTrace());
        assertEquals(replay, replayed.replayTrace());
    }

    @Test
    void rejectsConflictingModes() throws Exception {
        Path dataset = Files.createDirectory(temp.resolve("dataset"));
        Path replay = Files.createFile(temp.resolve("trace.jsonl"));
        assertThrows(IllegalArgumentException.class, () -> StructurizedPrismLiteApplication.LaunchOptions.parse(new String[]{
                "--record-agent-trace=" + temp.resolve("new.jsonl"), "--replay-agent-trace=" + replay, dataset.toString()}));
    }
}
