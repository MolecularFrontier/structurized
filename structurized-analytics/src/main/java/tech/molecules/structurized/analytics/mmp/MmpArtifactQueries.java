package tech.molecules.structurized.analytics.mmp;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import static tech.molecules.structurized.analytics.mmp.MmpArtifactException.Reason.*;

/** Bounded, indexed, read-only evidence queries shared by transport adapters. */
public final class MmpArtifactQueries {
    public Map<String,Object> read(Path artifact, String operation, String runId, String compoundId,
            String transformId, int limit, int offset, String expectedBuild) {
        return read(artifact,operation,runId,compoundId,transformId,limit,offset,expectedBuild,null);
    }

    public Map<String,Object> read(Path artifact, String operation, String runId, String compoundId,
            String transformId, int limit, int offset, String expectedBuild, String expectedScope) {
        if (limit<1 || limit>500 || offset<0) throw new IllegalArgumentException("limit must be 1..500; offset must be non-negative");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + artifact.toAbsolutePath().toUri() + "?mode=ro")) {
            String build = scalar(c, "select metadata_value from mmp_artifact_metadata where metadata_key='build_id'");
            if (expectedScope != null && !expectedScope.equals(scalar(c,
                    "select metadata_value from mmp_artifact_metadata where metadata_key='scope_key'")))
                throw new MmpArtifactException(SCOPE_MISMATCH,"Artifact belongs to a different scope");
            if (expectedBuild != null && !expectedBuild.equals(build))
                throw new MmpArtifactException(REPLACED,"Artifact replaced; restart pagination");
            Query query = query(c,operation,runId,compoundId,transformId);
            long total;
            try (PreparedStatement ps = prepare(c,query.countSql(),query.args()); ResultSet rs=ps.executeQuery()) {
                rs.next(); total=rs.getLong(1);
            }
            var pageArgs = new ArrayList<>(query.args());
            pageArgs.add(limit);pageArgs.add(offset);
            List<Map<String,Object>> rows = new ArrayList<>();
            try (PreparedStatement ps=prepare(c,query.pageSql(),pageArgs); ResultSet rs=ps.executeQuery()) {
                while(rs.next()) {
                    Map<String,Object> row=new LinkedHashMap<>();
                    for(int i=1;i<=rs.getMetaData().getColumnCount();i++) row.put(rs.getMetaData().getColumnLabel(i),rs.getObject(i));
                    if (operation.equals("universes") && (row.get("compound_count")==null
                            || row.get("fragmentation_count")==null || row.get("pair_count")==null))
                        throw new MmpArtifactException(INVALID_ARTIFACT,"Universe counts are missing; rebuild the artifact");
                    rows.add(row);
                }
            }
            var result=new LinkedHashMap<String,Object>();
            result.put("buildId",build);result.put("total",total);result.put("limit",limit);result.put("offset",offset);
            if(Set.of("transforms","pairs","neighbors").contains(operation))result.put("runId",runId);
            result.put("rows",List.copyOf(rows));return result;
        } catch(SQLException e) { throw new MmpArtifactException(QUERY_FAILED,"Artifact query failed",e); }
    }

    record Query(String pageSql, String countSql, List<Object> args) {
        Query { args=List.copyOf(args); }
    }

    /** Count and page statements share predicates and bindings, never parsed SQL text. */
    static Query query(Connection c,String operation,String runId,String compoundId,String transformId) throws SQLException {
        switch(operation) {
            case "universes": {
                String projection;
                try(var ps=c.prepareStatement("select 1 from sqlite_master where type='table' and name='mmp_universe_counts'");
                    var rs=ps.executeQuery()) {
                    if(rs.next()) {
                        projection="select u.*, n.compound_count, n.fragmentation_count, n.pair_count "
                                +"from mmp_universe u left join mmp_universe_counts n using(universe_id)";
                    } else {
                        // Compatibility for artifacts built before counts were persisted. The next
                        // rebuild automatically removes these per-row counts; total is cheap either way.
                        projection="select u.*, (select count(*) from mmp_universe_subject where universe_id=u.universe_id) compound_count, "
                                +"(select count(*) from mmp_fragmentation_record where universe_id=u.universe_id) fragmentation_count, "
                                +"(select count(*) from mmp_pair where universe_id=u.universe_id) pair_count from mmp_universe u";
                    }
                }
                return new Query(projection+" order by universe_id limit ? offset ?",
                        "select count(*) from mmp_universe",List.of());
            }
            case "fragments":
                require(compoundId,"compoundId");
                return table("mmp_fragmentation_record","compound_id=?","canonical_record_id",List.of(compoundId));
            case "runs":
                return table("mmp_endpoint_stats_run","","endpoint_id,run_id",List.of());
            case "transforms": {
                requireRun(c,runId);
                return transformId==null
                        ?table("mmp_endpoint_transform_stats","run_id=?","support_count desc,transform_id",List.of(runId))
                        :table("mmp_endpoint_transform_stats","run_id=? and transform_id=?","support_count desc,transform_id",List.of(runId,transformId));
            }
            case "pairs":
                requireRun(c,runId);require(transformId,"transformId");
                return table("mmp_endpoint_pair","run_id=? and transform_id=?",
                        "transform_id,compound_id_a,compound_id_b,key_idcode",List.of(runId,transformId));
            case "neighbors":
                requireRun(c,runId);require(compoundId,"compoundId");
                return table("mmp_endpoint_pair","run_id=? and (compound_id_a=? or compound_id_b=?)",
                        "transform_id,compound_id_a,compound_id_b,key_idcode",List.of(runId,compoundId,compoundId));
            default: throw new IllegalArgumentException("Unknown operation");
        }
    }

    private static Query table(String table,String predicate,String order,List<Object> args) {
        String from=" from "+table+(predicate.isEmpty()?"":" where "+predicate);
        return new Query("select *"+from+" order by "+order+" limit ? offset ?","select count(*)"+from,args);
    }
    private static void requireRun(Connection c,String id) throws SQLException {
        require(id,"runId");
        try(var ps=prepare(c,"select 1 from mmp_endpoint_stats_run where run_id=?",List.of(id));var rs=ps.executeQuery()) {
            if(!rs.next())throw new IllegalArgumentException("Unknown runId");
        }
    }
    private static void require(String s,String name) { if(s==null||s.isBlank())throw new IllegalArgumentException(name+" is required"); }
    private static String scalar(Connection c,String sql) throws SQLException {
        try(var s=c.createStatement();var rs=s.executeQuery(sql)) {
            if(!rs.next() || rs.getString(1)==null || rs.getString(1).isBlank())
                throw new MmpArtifactException(INVALID_ARTIFACT,"Missing artifact identity");
            return rs.getString(1);
        }
    }
    private static PreparedStatement prepare(Connection c,String sql,List<Object> args) throws SQLException {
        var ps=c.prepareStatement(sql);
        for(int i=0;i<args.size();i++)ps.setObject(i+1,args.get(i));return ps;
    }
}
