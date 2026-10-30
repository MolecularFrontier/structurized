package tech.molecules.structurized.analytics.mmp;

import com.actelion.research.chem.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.molecules.structurized.mmp.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MmpCurrentArtifactBuilderTest {
    @TempDir Path dir;
    static MmpAnalyticsSnapshot fixture() throws Exception {
        Map<String,StereoMolecule> structures=new LinkedHashMap<>();
        var smiles=List.of("Cc1ccccc1","Clc1ccccc1","CCc1ccccc1","Cc1ccncc1","Clc1ccncc1","CCc1ccncc1");
        for(int i=0;i<smiles.size();i++){var m=new StereoMolecule();new SmilesParser().parse(m,smiles.get(i));structures.put("sample-"+i,m);}
        var a=Map.of("sample-0",1.0,"sample-1",3.0,"sample-2",0.0,"sample-3",7.0,"sample-4",3.0,"sample-5",7.0);
        var b=Map.of("sample-0",2.0,"sample-1",2.0,"sample-2",1.0);
        return new MmpAnalyticsSnapshot("synthetic",structures,List.of(
                new MmpEndpointSnapshot("property-a","Property A","set-a",List.copyOf(structures.keySet()),a),
                new MmpEndpointSnapshot("property-b","Property B","set-b",List.copyOf(structures.keySet()),b)));
    }
    @Test void diskStatisticsMatchReferenceAndEveryContributingPairRemainsAvailable() throws Exception {
        var snapshot=fixture();var config=MmpMiningConfig.defaults().toBuilder().minTransformSupport(1).build();
        var expected=new MmpEndpointStatsCalculator().compute(snapshot,MmpEndpointStatsConfig.builder().universeId("test").build(),config);
        Path path=dir.resolve("artifact.sqlite");new MmpCurrentArtifactBuilder().build(path,"test",snapshot,config);
        try(var repo=SqliteMmpAnalyticsRepository.openReadOnly(path)) {
            assertEquals(2,repo.listStatsRuns().size());
            for(var computed:expected.endpointStats()) {
                var run=repo.listStatsRuns().stream().filter(r->r.endpointId().equals(computed.run().endpointId())).findFirst().orElseThrow();
                assertEquals(computed.run().pairCount(),run.pairCount());
                var actual=repo.listTransformStats(run.runId());assertFalse(actual.isEmpty());
                assertEquals(computed.transformStats().size(),actual.size());
                for(var stat:computed.transformStats()) {
                    var found=actual.stream().filter(s->s.transformId().equals(stat.transformId())).findFirst().orElseThrow();
                    assertEquals(stat.supportCount(),found.supportCount());
                    assertEquals(stat.meanDelta(),found.meanDelta(),1e-10);assertEquals(stat.medianDelta(),found.medianDelta(),1e-10);
                    assertEquals(stat.standardDeviation(),found.standardDeviation(),1e-10);
                    assertEquals(stat.positiveFraction(),found.positiveFraction(),1e-10);
                    assertEquals(stat.supportCount(),repo.countEndpointPairs(run.runId(),stat.transformId()));
                }
            }
        }
        var query=new MmpArtifactQueries();var page=query.read(path,"runs",null,null,null,1,0,null);
        assertEquals(2L,page.get("total"));
        assertThrows(IllegalStateException.class,()->query.read(path,"runs",null,null,null,1,1,"old-build"));
        assertThrows(IllegalArgumentException.class,()->query.read(path,"runs",null,null,null,501,0,null));
    }
    @Test void unmeasuredPairsAreRetainedEvenBelowStatsSupportThreshold() throws Exception {
        var path=dir.resolve("artifact.sqlite");new MmpCurrentArtifactBuilder().build(path,"test",fixture(),MmpMiningConfig.defaults().toBuilder().minTransformSupport(100).build());
        try(var repo=SqliteMmpAnalyticsRepository.openReadOnly(path)) {
            assertTrue(repo.listPairs("test").size()>0);
            for(var run:repo.listStatsRuns()){assertTrue(run.pairCount()>0);assertEquals(0,run.statsCount());}
        }
    }
    @Test void universeCatalogueReadsStoredCountsWithoutReadingFactTables() throws Exception {
        var path=dir.resolve("counts.sqlite");
        new MmpCurrentArtifactBuilder().build(path,"test",fixture(),MmpMiningConfig.defaults());
        var queries=new MmpArtifactQueries();
        var page=queries.read(path,"universes",null,null,null,50,0,null);
        @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>)page.get("rows");
        assertEquals(1L,page.get("total"));assertEquals(1,rows.size());
        try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+path);var statement=c.createStatement()) {
            for(var table:Map.of("compound_count","mmp_universe_subject","fragmentation_count","mmp_fragmentation_record","pair_count","mmp_pair").entrySet()) {
                try(var rs=statement.executeQuery("select count(*) from "+table.getValue())) {
                    rs.next();assertEquals(rs.getLong(1),((Number)rows.getFirst().get(table.getKey())).longValue());
                }
            }
            // Prove that neither the total nor the displayed counts depend on scanning facts.
            statement.execute("drop table mmp_pair");
            statement.execute("drop table mmp_fragmentation_record");
            statement.execute("drop table mmp_universe_subject");
        }
        assertEquals(page,queries.read(path,"universes",null,null,null,50,0,null));
        var empty=queries.read(path,"universes",null,null,null,50,1,null);
        assertEquals(1L,empty.get("total"));assertEquals(List.of(),empty.get("rows"));
    }

    @Test void legacyArtifactsKeepTheirCatalogueUntilRebuilt() throws Exception {
        var path=dir.resolve("legacy.sqlite");
        new MmpCurrentArtifactBuilder().build(path,"test",fixture(),MmpMiningConfig.defaults());
        var queries=new MmpArtifactQueries();var before=queries.read(path,"universes",null,null,null,50,0,null);
        try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+path);var statement=c.createStatement()) {
            statement.execute("drop table mmp_universe_counts");
        }
        assertEquals(before,queries.read(path,"universes",null,null,null,50,0,null));
    }

    @Test void filteredTotalsAndPaginationStayConsistent() throws Exception {
        var path=dir.resolve("pages.sqlite");
        new MmpCurrentArtifactBuilder().build(path,"test",fixture(),MmpMiningConfig.defaults());
        var queries=new MmpArtifactQueries();
        String run,transform;
        try(var repo=SqliteMmpAnalyticsRepository.openReadOnly(path)) {
            run=repo.listStatsRuns().stream().filter(r->r.statsCount()>0).findFirst().orElseThrow().runId();
            transform=repo.listTransformStats(run).getFirst().transformId();
        }
        for(String operation:List.of("universes","runs","fragments","transforms","pairs","neighbors")) {
            String selectedRun=Set.of("transforms","pairs","neighbors").contains(operation)?run:null;
            String selectedTransform=Set.of("pairs","transforms").contains(operation)?transform:null;
            var all=queries.read(path,operation,selectedRun,"sample-0",selectedTransform,500,0,null);
            @SuppressWarnings("unchecked") var allRows=(List<Map<String,Object>>)all.get("rows");
            assertEquals((long)allRows.size(),all.get("total"),operation);
            var page=queries.read(path,operation,selectedRun,"sample-0",selectedTransform,1,1,null);
            assertEquals(all.get("total"),page.get("total"),operation);
            assertEquals(allRows.stream().skip(1).limit(1).toList(),page.get("rows"),operation);
        }
        assertEquals(0L,queries.read(path,"transforms",run,null,"missing",10,0,null).get("total"));
        assertEquals(0L,queries.read(path,"neighbors",run,"missing",null,10,0,null).get("total"));
    }

    @Test void safetyLimitsFailInsteadOfReturningIncompleteResults() throws Exception {
        assertThrows(IllegalStateException.class,()->new MmpCurrentArtifactBuilder().build(dir.resolve("small.sqlite"),"test",fixture(),
                MmpMiningConfig.defaults().toBuilder().maxPairsPerKey(1).build()));
        assertThrows(IllegalStateException.class,()->MmpFragmenter.fragment(new MmpInputCompound("sample",fixture().structuresBySubjectId().get("sample-2"),null),
                MmpMiningConfig.defaults().toBuilder().maxFragmentationRecordsPerCompound(1).build()));
    }
}
