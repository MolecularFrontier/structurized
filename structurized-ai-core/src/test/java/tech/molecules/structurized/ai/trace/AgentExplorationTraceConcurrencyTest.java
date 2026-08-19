package tech.molecules.structurized.ai.trace;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentExplorationTraceConcurrencyTest {
    @Test
    void concurrentPublicationsReachListenersInSequenceOrder() throws Exception {
        AgentExplorationTrace trace = new AgentExplorationTrace();
        List<Long> sequences = Collections.synchronizedList(new ArrayList<>());
        trace.subscribe(event -> sequences.add(event.sequence()));
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            for (int index = 0; index < 100; index++) {
                int invocation = index;
                executor.submit(() -> trace.publish("call-" + invocation, AgentExplorationPhase.STARTED,
                        "list_repositories", AgentActivityType.OTHER, "List repositories",
                        null, List.of(), null, null));
            }
        } finally {
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }

        assertEquals(100, sequences.size());
        for (int index = 0; index < sequences.size(); index++) {
            assertEquals(index + 1L, sequences.get(index));
        }
    }
}
