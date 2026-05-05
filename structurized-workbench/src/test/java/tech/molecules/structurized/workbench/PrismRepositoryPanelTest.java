package tech.molecules.structurized.workbench;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.molecules.structurized.workbench.prism.PrismRepositoryPanel;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PrismRepositoryPanelTest {

    @Test
    void loadsMinimalPrismTsvRepository(@TempDir Path tempDir) throws Exception {
        writePrismTsv(tempDir);
        AtomicReference<PrismRepositoryPanel> panelRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                PrismRepositoryPanel panel = new PrismRepositoryPanel();
                panel.loadRepository(tempDir);
                panelRef.set(panel);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        PrismRepositoryPanel panel = panelRef.get();
        assertNotNull(panel.getModel());
        assertEquals(1, panel.getModel().dataset().getEndpointDefinitions().size());
        assertEquals(2, panel.getModel().dataset().getSubjectRecords().size());
    }

    private static void writePrismTsv(Path directory) throws Exception {
        Files.writeString(directory.resolve("endpoints.prism.tsv"), """
                endpoint_id\tname\tpath\tdatatype\tendpoint_type\tevaluation_mode\tunit\tscale\tdomain_lower_bound\tdomain_upper_bound\tcategories\tdescription
                ic50\tIC50\tassay/ic50\tNUMERIC\tMEASURED\tIMMEDIATE\tnM\tLOG\t0.001\t10000\t\t
                """);
        Files.writeString(directory.resolve("subjects.prism.tsv"), """
                subject_id\tstructure_id\tbatch_id\tproject\tseries\tsmiles
                cmp-1\t\t\tProject A\tSeries 1\tCc1ccccc1
                cmp-2\t\t\tProject A\tSeries 1\tCCc1ccccc1
                """);
        Files.writeString(directory.resolve("values.prism.tsv"), """
                subject_id\tendpoint_id\tstate\tmean\tlower\tupper\tn\traw_values\traw_value_ids\tfirst_measurement\tlast_measurement\tdetails
                cmp-1\tic50\tVALUE\t1.0\t\t\t1\t1.0\traw-1\t2026-01-01\t2026-01-01\t
                cmp-2\tic50\tVALUE\t3.0\t\t\t1\t3.0\traw-2\t2026-01-02\t2026-01-02\t
                """);
    }
}
