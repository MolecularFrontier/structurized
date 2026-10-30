package tech.molecules.structurized.analytics.mmp;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;

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
                throw new IllegalStateException("Artifact belongs to a different scope");
            if (expectedBuild != null && !expectedBuild.equals(build)) throw new IllegalStateException("Artifact replaced; restart pagination");
            String sql; List<Object> args = new ArrayList<>();
            switch (operation) {
                case "universes" -> sql = "select u.*, (select count(*) from mmp_universe_subject where universe_id=u.universe_id) compound_count, (select count(*) from mmp_fragmentation_record where universe_id=u.universe_id) fragmentation_count, (select count(*) from mmp_pair where universe_id=u.universe_id) pair_count from mmp_universe u order by universe_id";
                case "fragments" -> {
                    require(compoundId,"compoundId");args.add(compoundId);
                    sql="select * from mmp_fragmentation_record where compound_id=? order by canonical_record_id";
                }
                case "runs" -> sql = "select * from mmp_endpoint_stats_run order by endpoint_id,run_id";
                case "transforms" -> {
                    requireRun(c,runId); args.add(runId);
                    sql = "select * from mmp_endpoint_transform_stats where run_id=?";
                    if (transformId!=null) { sql+=" and transform_id=?"; args.add(transformId); }
                    sql+=" order by support_count desc,transform_id";
                }
                case "pairs", "neighbors" -> {
                    requireRun(c,runId); args.add(runId);
                    sql="select * from mmp_endpoint_pair where run_id=?";
                    if (operation.equals("pairs")) { require(transformId,"transformId"); sql+=" and transform_id=?"; args.add(transformId); }
                    else { require(compoundId,"compoundId"); sql+=" and (compound_id_a=? or compound_id_b=?)"; args.add(compoundId); args.add(compoundId); }
                    sql+=" order by transform_id,compound_id_a,compound_id_b,key_idcode";
                }
                default -> throw new IllegalArgumentException("Unknown operation");
            }
            long total;
            try (PreparedStatement ps = prepare(c,"select count(*) from ("+sql.substring(0,sql.lastIndexOf(" order by"))+")",args); ResultSet rs=ps.executeQuery()) {
                rs.next(); total=rs.getLong(1);
            }
            args.add(limit);args.add(offset);
            List<Map<String,Object>> rows = new ArrayList<>();
            try (PreparedStatement ps=prepare(c,sql+" limit ? offset ?",args); ResultSet rs=ps.executeQuery()) {
                while(rs.next()) {
                    Map<String,Object> row=new LinkedHashMap<>();
                    for(int i=1;i<=rs.getMetaData().getColumnCount();i++) row.put(rs.getMetaData().getColumnLabel(i),rs.getObject(i));
                    rows.add(row);
                }
            }
            var result=new LinkedHashMap<String,Object>();
            result.put("buildId",build);result.put("total",total);result.put("limit",limit);result.put("offset",offset);
            if(Set.of("transforms","pairs","neighbors").contains(operation))result.put("runId",runId);
            result.put("rows",List.copyOf(rows));return result;
        } catch(SQLException e) { throw new IllegalStateException("Artifact query failed",e); }
    }
    private static void requireRun(Connection c,String id) throws SQLException {
        require(id,"runId");
        try(var ps=prepare(c,"select 1 from mmp_endpoint_stats_run where run_id=?",List.of(id));var rs=ps.executeQuery()) {
            if(!rs.next())throw new IllegalArgumentException("Unknown runId");
        }
    }
    private static void require(String s,String name) { if(s==null||s.isBlank())throw new IllegalArgumentException(name+" is required"); }
    private static String scalar(Connection c,String sql) throws SQLException {
        try(var s=c.createStatement();var rs=s.executeQuery(sql)) {if(!rs.next())throw new IllegalStateException("Missing build identity");return rs.getString(1);}
    }
    private static PreparedStatement prepare(Connection c,String sql,List<Object> args) throws SQLException {
        var ps=c.prepareStatement(sql);
        for(int i=0;i<args.size();i++)ps.setObject(i+1,args.get(i));return ps;
    }
}
