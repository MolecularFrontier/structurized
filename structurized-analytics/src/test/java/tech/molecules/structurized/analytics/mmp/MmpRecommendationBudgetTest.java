package tech.molecules.structurized.analytics.mmp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.molecules.structurized.mmp.MmpTransformStats;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class MmpRecommendationBudgetTest {
    @Test void deadlinesAndCancellationRemainStickyAfterSqliteInterruption() throws Exception {
        var clock=new AtomicLong();
        var limits=new MmpRecommendationBudget.Limits(20,100,100,100,Duration.ofNanos(5));
        var budget=new MmpRecommendationBudget(limits,()->false,clock::getAndIncrement);
        try(var connection=DriverManager.getConnection("jdbc:sqlite::memory:");var repository=new SqliteMmpAnalyticsRepository(connection)) {
            repository.cancelQueriesWhen(budget::shouldStop);
            try(var statement=connection.createStatement()) {
                assertThrows(SQLException.class,()->statement.executeQuery("with recursive n(x) as (values(1) union all select x+1 from n where x<100000) select sum(x) from n"));
            }
            clock.set(0);
            assertEquals(MmpRecommendationLimitException.Limit.TIME,assertThrows(MmpRecommendationLimitException.class,budget::check).limit());
        }
        var cancelled=new AtomicBoolean(true);
        var cancellation=new MmpRecommendationBudget(limits,cancelled::get,()->0);
        assertTrue(cancellation.shouldStop());cancelled.set(false);
        assertThrows(CancellationException.class,cancellation::check);
    }

    @Test void boundedSummaryQueriesDoNotReadPairsAndRespectTheLimitAcrossChunks(@TempDir Path directory) throws Exception {
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("synthetic.sqlite"));
            var repository=new SqliteMmpAnalyticsRepository(connection)) {
            List<MmpTransformStats> stats=List.of(stat("source-000"),stat("source-449"));
            repository.saveStatsRun(new MmpEndpointStatsRun("run","endpoint","set","universe","config","stats",Instant.EPOCH,4,4,2,2,null),stats);
            try(var statement=connection.createStatement()){statement.execute("drop table mmp_endpoint_pair");statement.execute("drop table mmp_pair");}
            Set<String> sources=new TreeSet<>();for(int i=0;i<450;i++)sources.add(String.format(Locale.ROOT,"source-%03d",i));
            assertThrows(MmpRecommendationLimitException.class,()->repository.findTransformSummariesBySourceFragments("run",1,sources,1));
            var found=repository.findTransformSummariesBySourceFragments("run",1,sources,2);
            assertEquals(2,found.size());assertTrue(found.stream().allMatch(s->s.supportingPairs().isEmpty()));
            assertEquals(2,repository.findTransformSummariesByIds("run",Set.of("source-000","source-449")).size());
            assertEquals(List.of(),repository.findTransformSummariesBySourceFragments("run",1,Set.of("absent"),0));
            assertThrows(MmpRecommendationLimitException.class,()->repository.findTransformSummariesBySourceFragments("run",1,Set.of("source-000"),0));
        }
    }
    private static MmpTransformStats stat(String source) {
        return new MmpTransformStats(source,source,"target",1,1,0,0,0,0,0,0,0,0,1,List.of());
    }
}
