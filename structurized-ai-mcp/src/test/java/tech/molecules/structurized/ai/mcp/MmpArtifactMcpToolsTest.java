package tech.molecules.structurized.ai.mcp;

import com.actelion.research.chem.SmilesParser;
import com.actelion.research.chem.StereoMolecule;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.molecules.structurized.analytics.mmp.MmpAnalyticsHashes;
import tech.molecules.structurized.analytics.mmp.MmpEndpointStatsRun;
import tech.molecules.structurized.analytics.mmp.MmpMiningConfigSnapshot;
import tech.molecules.structurized.analytics.mmp.SqliteMmpAnalyticsRepository;
import tech.molecules.structurized.mmp.MmpInputCompound;
import tech.molecules.structurized.mmp.MmpMiner;
import tech.molecules.structurized.mmp.MmpMiningConfig;
import tech.molecules.structurized.mmp.MmpStatsAggregator;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MmpArtifactMcpToolsTest {
    @Test
    void opensArtifactAndGeneratesEvidenceBackedCompounds(@TempDir Path tempDir) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path database = tempDir.resolve("mmp.sqlite");
        MmpMiningConfig config = MmpMiningConfig.builder()
                .maxCuts(2).minKeyHeavyAtoms(1).maxVariableHeavyAtoms(20)
                .maxVariableToMolHeavyAtomFraction(1.0).minTransformSupport(1).build();
        var stats = MmpStatsAggregator.aggregate(MmpMiner.mine(List.of(
                new MmpInputCompound("source", parse("Cc1ccccc1"), 1.0),
                new MmpInputCompound("target", parse("CCc1ccccc1"), 2.0)), config).pairs(), config);
        String hash = MmpAnalyticsHashes.mmpConfigHash(config);
        try (SqliteMmpAnalyticsRepository repository = SqliteMmpAnalyticsRepository.open(database)) {
            repository.saveMiningConfig(hash, MmpMiningConfigSnapshot.from(config));
            repository.saveStatsRun(new MmpEndpointStatsRun(
                    "primary", "solubility", "set", "universe", hash, "stats",
                    Instant.parse("2026-08-18T10:00:00Z"), 2, 2, 2, stats.size(), null), stats);
        }

        McpArtifactService artifacts = new McpArtifactService(mapper, tempDir.resolve("outputs"));
        MmpArtifactMcpTools tools = new MmpArtifactMcpTools(new McpToolOutputSupport(artifacts));
        ObjectNode openArgs = mapper.createObjectNode().put("path", database.toString());
        String artifactId = String.valueOf(tools.open(openArgs).get("artifact_id"));

        ObjectNode recommend = mapper.createObjectNode()
                .put("artifact_id", artifactId)
                .put("input_smiles", "Cc1ccccc1")
                .put("selection_mode", "all_sites")
                .put("primary_run_id", "primary")
                .put("max_results", 20);
        recommend.putArray("endpoint_preferences").addObject()
                .put("run_id", "primary").put("direction", "higher_is_better");
        JsonNode result = mapper.valueToTree(tools.recommend(recommend));

        assertFalse(result.path("candidates").isEmpty(), result.toPrettyString());
        assertTrue(result.at("/candidates/0/product_smiles").isTextual());
        assertTrue(result.at("/candidates/0/endpoint_evidence/primary/support_count").asInt() > 0);
        assertThrows(RuntimeException.class, () -> tools.recommend(
                recommend.deepCopy().put("selection_mode", "editable_region")));
    }

    private static StereoMolecule parse(String smiles) throws Exception {
        StereoMolecule molecule = new StereoMolecule();
        new SmilesParser().parse(molecule, smiles);
        return molecule;
    }
}
