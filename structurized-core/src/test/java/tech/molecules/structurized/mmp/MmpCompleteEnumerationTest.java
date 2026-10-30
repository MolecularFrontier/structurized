package tech.molecules.structurized.mmp;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MmpCompleteEnumerationTest {
    private static List<MmpFragmentationRecord> records() {
        var records=new ArrayList<MmpFragmentationRecord>();
        for(int i=0;i<500;i++)records.add(new MmpFragmentationRecord("sample-"+i,1,"constant","variable-"+i,6,1,List.of(),null));
        return records;
    }
    @Test void completeStreamExceedsTheFormerDefaultCap() {
        var count=new AtomicInteger();
        MmpMiner.forEachPair(records(),MmpMiningConfig.defaults(),p->count.incrementAndGet());
        assertEquals(249_500,count.get());
    }
    @Test void explicitBudgetFailsAndInterruptionCancels() {
        assertThrows(IllegalStateException.class,()->MmpMiner.forEachPair(records(),
                MmpMiningConfig.defaults().toBuilder().maxPairsPerKey(200_000).build(),p->{}));
        Thread.currentThread().interrupt();
        try {assertThrows(CancellationException.class,()->MmpMiner.forEachPair(records(),MmpMiningConfig.defaults(),p->{}));}
        finally {Thread.interrupted();}
    }
}
