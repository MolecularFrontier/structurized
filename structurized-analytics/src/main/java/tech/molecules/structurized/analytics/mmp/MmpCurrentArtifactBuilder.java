package tech.molecules.structurized.analytics.mmp;

import tech.molecules.structurized.mmp.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Disposable artifact builder. Structures/fragments occupy Java memory; pairs and sorting use disk. */
public final class MmpCurrentArtifactBuilder {
    public void build(Path path, String universeId, MmpAnalyticsSnapshot snapshot, MmpMiningConfig config) {
        if (Files.exists(path)) throw new IllegalArgumentException("Destination already exists");
        if (snapshot.endpoints().isEmpty()) throw new IllegalArgumentException("No endpoints selected");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
             SqliteMmpAnalyticsRepository repository = new SqliteMmpAnalyticsRepository(c)) {
            try (Statement s = c.createStatement()) { s.execute("pragma temp_store=FILE"); }
            org.sqlite.ProgressHandler.setHandler(c, 10_000, new org.sqlite.ProgressHandler() {
                @Override protected int progress() { return Thread.currentThread().isInterrupted() ? 1 : 0; }
            });
            var subjects = new TreeSet<String>();
            snapshot.endpoints().forEach(e -> subjects.addAll(e.subjectIds()));
            var structures = snapshot.structuresBySubjectId();
            if (!structures.keySet().containsAll(subjects)) throw new IllegalArgumentException("Missing input structures");
            String hash = MmpAnalyticsHashes.mmpConfigHash(config);
            Instant now = Instant.now();
            repository.saveMiningConfig(hash, MmpMiningConfigSnapshot.from(config));
            repository.saveUniverse(new MmpUniverse(universeId, universeId,
                    snapshot.endpoints().stream().map(MmpEndpointSnapshot::subjectSetId).distinct().toList(),
                    hash, now, "complete=true"), List.copyOf(subjects));
            var records = new ArrayList<MmpFragmentationRecord>();
            for (String id : subjects) {
                checkCancelled();
                records.addAll(MmpFragmenter.fragment(new MmpInputCompound(id, structures.get(id), null), config));
            }
            repository.replaceFragmentationRecords(universeId, records);
            long[] pairCount = {0};
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("insert into mmp_pair (universe_id, transform_id, compound_id_a, compound_id_b, key_idcode, from_value_idcode, to_value_idcode, cut_count) values (?,?,?,?,?,?,?,?)")) {
                int[] batch = {0};
                MmpMiner.forEachPair(records, config, pair -> {
                    try {
                        ps.setString(1, universeId); ps.setString(2, pair.transformId());
                        ps.setString(3, pair.compoundIdA()); ps.setString(4, pair.compoundIdB());
                        ps.setString(5, pair.keyIdcode()); ps.setString(6, pair.fromValueIdcode());
                        ps.setString(7, pair.toValueIdcode()); ps.setInt(8, pair.cutCount());
                        pairCount[0]++;
                        ps.addBatch(); if (++batch[0] % 1000 == 0) ps.executeBatch();
                    } catch (SQLException e) { throw new IllegalStateException("Pair write failed", e); }
                });
                ps.executeBatch(); c.commit();
            }
            c.setAutoCommit(true);
            execute(c, """
                create table mmp_universe_counts (
                  universe_id text primary key,
                  compound_count integer not null,
                  fragmentation_count integer not null,
                  pair_count integer not null
                )
                """);
            execute(c, """
                insert into mmp_universe_counts (universe_id, compound_count, fragmentation_count, pair_count)
                values (?, ?, ?, ?)
                """, universeId, subjects.size(), records.size(), pairCount[0]);
            var endpointIds = new HashSet<String>();
            for (var endpoint : snapshot.endpoints()) {
                checkCancelled();
                if (!endpointIds.add(endpoint.endpointId())) throw new IllegalArgumentException("Duplicate endpoint");
                String run = UUID.randomUUID().toString();
                var statsConfig = MmpEndpointStatsConfig.builder().endpointIds(List.of(endpoint.endpointId()))
                        .putEndpointSubjectSetId(endpoint.endpointId(), endpoint.subjectSetId()).build();
                repository.saveStatsRun(new MmpEndpointStatsRun(run, endpoint.endpointId(), endpoint.subjectSetId(),
                        universeId, hash, MmpAnalyticsHashes.statsConfigHash(statsConfig), now,
                        endpoint.subjectIds().size(), endpoint.valuesBySubjectId().size(), 0, 0, "complete=true"), List.of());
                repository.saveSubjectValues(run, endpoint.valuesBySubjectId());
                execute(c, """
                    insert into mmp_endpoint_pair
                    (run_id, transform_id, compound_id_a, compound_id_b, key_idcode, value_a, value_b, delta)
                    select ?, p.transform_id, p.compound_id_a, p.compound_id_b, p.key_idcode,
                           a.value, b.value, b.value-a.value from mmp_pair p
                    join mmp_endpoint_subject_value a on a.run_id=? and a.subject_id=p.compound_id_a
                    join mmp_endpoint_subject_value b on b.run_id=? and b.subject_id=p.compound_id_b
                    where p.universe_id=?
                    """, run, run, run, universeId);
                // Centered population variance matches MmpStatsAggregator; window median is exact.
                execute(c, """
                    insert into mmp_endpoint_transform_stats
                    (run_id, transform_id, from_value_idcode, to_value_idcode, cut_count, support_count,
                     mean_delta, median_delta, standard_deviation, min_delta, max_delta, positive_fraction,
                     up_count, down_count, tie_count)
                    with means as (
                      select transform_id, avg(delta) mean from mmp_endpoint_pair where run_id=? group by transform_id
                    ), ranked as (
                      select p.*, m.mean, row_number() over(partition by p.transform_id order by delta) rn,
                             count(*) over(partition by p.transform_id) n
                      from mmp_endpoint_pair p join means m using(transform_id) where p.run_id=?
                    ), fragments as (
                      select transform_id, min(from_value_idcode) f, min(to_value_idcode) t, min(cut_count) cuts
                      from mmp_pair where universe_id=? group by transform_id
                    )
                    select ?, r.transform_id, f.f, f.t, f.cuts, count(*), max(mean),
                           avg(case when rn in ((n+1)/2,(n+2)/2) then delta end),
                           sqrt(avg((delta-mean)*(delta-mean))), min(delta), max(delta),
                           avg(case when delta>0 then 1.0 else 0.0 end),
                           sum(delta>0), sum(delta<0), sum(delta=0)
                    from ranked r join fragments f using(transform_id)
                    group by r.transform_id having count(*)>=?
                    """, run, run, universeId, run, config.minTransformSupport());
                execute(c, """
                    update mmp_endpoint_stats_run set
                      pair_count=(select count(*) from mmp_endpoint_pair where run_id=?),
                      stats_count=(select count(*) from mmp_endpoint_transform_stats where run_id=?) where run_id=?
                    """, run, run, run);
            }
            execute(c, "create index if not exists idx_mmp_stats_source on mmp_endpoint_transform_stats(run_id,cut_count,from_value_idcode,transform_id)");
            execute(c, "create index if not exists idx_mmp_stats_support on mmp_endpoint_transform_stats(run_id,support_count desc,transform_id)");
            execute(c, "create index if not exists idx_mmp_pair_page on mmp_endpoint_pair(run_id,transform_id,compound_id_a,compound_id_b,key_idcode)");
            execute(c, "insert into mmp_artifact_metadata (metadata_key, metadata_value) values ('build_id',?)", UUID.randomUUID().toString());
            execute(c, "create index if not exists idx_mmp_endpoint_pair_a on mmp_endpoint_pair(run_id,compound_id_a)");
            execute(c, "create index if not exists idx_mmp_endpoint_pair_b on mmp_endpoint_pair(run_id,compound_id_b)");
            execute(c, "create index if not exists idx_mmp_fragment_compound on mmp_fragmentation_record(compound_id)");
            checkCancelled();
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("pragma integrity_check")) {
                if (!r.next() || !"ok".equals(r.getString(1))) throw new IllegalStateException("Artifact integrity check failed");
            }
        } catch (SQLException e) { throw new IllegalStateException("Artifact computation failed", e); }
    }
    private static void execute(Connection c, String sql, Object... args) throws SQLException {
        checkCancelled();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i=0; i<args.length; i++) ps.setObject(i+1, args[i]);
            ps.executeUpdate();
        }
    }
    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Build cancelled");
    }
}
