package tech.molecules.structurized.workbench.prism;

import org.junit.jupiter.api.Test;
import tech.molecules.structurized.mmp.MmpPair;
import tech.molecules.structurized.mmp.MmpTransformStats;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MmpChemistryDetailPanelTest {
    @Test
    void selectedTwoCutPairShowsKeyDirectionAndMetadata() {
        MmpPair pair = pair(2, MmpTestFragments.idcode(6, 2),
                MmpTestFragments.idcode(7, 2), MmpTestFragments.idcode(8, 2));
        MmpTransformStats stats = stats(pair);
        MmpChemistryDetailPanel panel = new MmpChemistryDetailPanel();

        panel.showPair(stats, pair);

        assertEquals(2, panel.displayedCutCount());
        assertNotNull(panel.displayedKey());
        assertNotNull(panel.displayedFrom());
        assertNotNull(panel.displayedTo());
        assertTrue(panel.displayedMetadata().contains("cmp-a"));
        assertTrue(panel.displayedMetadata().contains("cmp-b"));
        assertTrue(panel.displayedMetadata().contains("R1 and R2"));
    }

    @Test
    void malformedFragmentsDoNotHidePairMetadataOrThrow() {
        MmpPair pair = pair(1, "bad-key", "bad-from", "bad-to");
        MmpChemistryDetailPanel panel = new MmpChemistryDetailPanel();

        panel.showPair(stats(pair), pair);

        assertNull(panel.displayedKey());
        assertNull(panel.displayedFrom());
        assertNull(panel.displayedTo());
        assertTrue(panel.displayedMetadata().contains("cmp-a"));
    }

    static MmpPair pair(int cuts, String key, String from, String to) {
        return new MmpPair("cmp-a", "cmp-b", 1.0, 2.5, 1.5,
                key, from, to, null, cuts);
    }

    static MmpTransformStats stats(MmpPair pair) {
        return new MmpTransformStats(pair.transformId(), pair.fromValueIdcode(), pair.toValueIdcode(),
                pair.cutCount(), 4, 1.5, 1.4, 0.2, 1.1, 1.8, 1.0, List.of(pair));
    }
}
