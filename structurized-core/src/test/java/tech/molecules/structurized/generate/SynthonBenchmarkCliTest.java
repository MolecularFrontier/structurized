package tech.molecules.structurized.generate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SynthonBenchmarkCliTest {
    @TempDir
    Path tempDir;

    @Test
    void minesGeneratesAndPerturbsSmallBenchmark() throws Exception {
        Path input = tempDir.resolve("input.tsv");
        Files.writeString(input, String.join("\n",
                "chembl_id\tsmiles",
                "T1\tCCOc1ccccc1",
                "T2\tCCNc1ccccc1",
                "T3\tCCCOc1ccccc1",
                "T4\tCCOc1ccc(Cl)cc1",
                "T5\tCCSc1ccccc1") + "\n");

        Path miningDir = tempDir.resolve("mined");
        SynthonBenchmarkCli.main(new String[]{
                "mine-synthons",
                "--input", input.toString(),
                "--output-dir", miningDir.toString(),
                "--max-cut-instances", "200",
                "--max-cuts-per-molecule", "100"
        });

        assertTrue(dataRows(miningDir.resolve("source_molecules.tsv")) >= 5);
        assertTrue(dataRows(miningDir.resolve("cut_instances.tsv")) > 0);
        assertTrue(dataRows(miningDir.resolve("synthons.tsv")) > 0);
        assertTrue(Files.readString(miningDir.resolve("mining_report.txt")).contains("Round-trip failures: 0"));
        assertTsvWidth(miningDir.resolve("cut_instances.tsv"), 7);

        Path benchmarkDir = tempDir.resolve("benchmark");
        SynthonBenchmarkCli.main(new String[]{
                "make-benchmark",
                "--mining-dir", miningDir.toString(),
                "--output-dir", benchmarkDir.toString(),
                "--max-products", "20",
                "--seed", "7",
                "--mode", "mixed"
        });

        assertTrue(dataRows(benchmarkDir.resolve("generated_molecules.tsv")) > 0);
        assertEquals(
                dataRows(benchmarkDir.resolve("generated_molecules.tsv")),
                dataRows(benchmarkDir.resolve("generation_truth.tsv")));
        assertTsvWidth(benchmarkDir.resolve("generated_molecules.tsv"), 8);

        Path perturbedDir = tempDir.resolve("perturbed");
        SynthonBenchmarkCli.main(new String[]{
                "perturb-benchmark",
                "--input-dir", benchmarkDir.toString(),
                "--output-dir", perturbedDir.toString(),
                "--seed", "7",
                "--drop-rate", "0.2",
                "--distractor-rate", "0.2",
                "--merge-rate", "0.1"
        });

        assertTrue(dataRows(perturbedDir.resolve("public_molecules.tsv")) > 0);
        assertEquals(
                dataRows(perturbedDir.resolve("public_molecules.tsv")),
                dataRows(perturbedDir.resolve("perturbation_truth.tsv")));
        assertTsvWidth(perturbedDir.resolve("public_molecules.tsv"), 3);
    }

    private static long dataRows(Path path) throws Exception {
        try (var lines = Files.lines(path)) {
            return Math.max(0, lines.count() - 1);
        }
    }

    private static void assertTsvWidth(Path path, int expectedColumns) throws Exception {
        try (var lines = Files.lines(path)) {
            lines.forEach(line -> assertEquals(expectedColumns, line.split("\t", -1).length, line));
        }
    }
}
