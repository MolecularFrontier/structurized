package tech.molecules.structurized.analytics.mmp;

import tech.molecules.structurized.mmp.MmpFragmentationRecord;
import tech.molecules.structurized.mmp.MmpPair;
import tech.molecules.structurized.mmp.MmpTransformStats;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * SQLite-backed reference repository for MMP universes, pairs, and endpoint statistics.
 */
public final class SqliteMmpAnalyticsRepository implements MmpUniverseRepository, MmpPairRepository,
        MmpEndpointStatsRepository, AutoCloseable {
    private static final int SQLITE_IN_CHUNK_SIZE = 400;
    private final Connection connection;

    public SqliteMmpAnalyticsRepository(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
        createSchema();
    }

    public static SqliteMmpAnalyticsRepository open(Path databasePath) {
        Objects.requireNonNull(databasePath, "databasePath");
        try {
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
            return new SqliteMmpAnalyticsRepository(connection);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to open SQLite MMP analytics database", e);
        }
    }

    @Override
    public void saveUniverse(MmpUniverse universe, List<String> subjectIds) {
        Objects.requireNonNull(universe, "universe");
        runInTransaction(() -> {
            executeUpdate("""
                    insert or replace into mmp_universe
                    (universe_id, name, subject_set_ids, mmp_config_hash, created_at, metadata)
                    values (?, ?, ?, ?, ?, ?)
                    """,
                    universe.universeId(),
                    universe.name(),
                    joinLines(universe.subjectSetIds()),
                    universe.mmpConfigHash(),
                    universe.createdAt().toString(),
                    universe.metadata());
            executeUpdate("delete from mmp_universe_subject where universe_id = ?", universe.universeId());
            try (PreparedStatement ps = connection.prepareStatement("""
                    insert into mmp_universe_subject (universe_id, subject_id, ordinal)
                    values (?, ?, ?)
                    """)) {
                int ordinal = 0;
                for (String subjectId : subjectIds == null ? List.<String>of() : subjectIds) {
                    ps.setString(1, universe.universeId());
                    ps.setString(2, subjectId);
                    ps.setInt(3, ordinal++);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }

    @Override
    public Optional<MmpUniverse> findUniverse(String universeId) {
        try (PreparedStatement ps = connection.prepareStatement("""
                select universe_id, name, subject_set_ids, mmp_config_hash, created_at, metadata
                from mmp_universe where universe_id = ?
                """)) {
            ps.setString(1, universeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new MmpUniverse(
                        rs.getString("universe_id"),
                        rs.getString("name"),
                        splitLines(rs.getString("subject_set_ids")),
                        rs.getString("mmp_config_hash"),
                        Instant.parse(rs.getString("created_at")),
                        rs.getString("metadata")
                ));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to read MMP universe " + universeId, e);
        }
    }

    @Override
    public List<MmpUniverse> listUniverses() {
        try (PreparedStatement ps = connection.prepareStatement("""
                select universe_id, name, subject_set_ids, mmp_config_hash, created_at, metadata
                from mmp_universe
                order by created_at desc, universe_id asc
                """)) {
            ArrayList<MmpUniverse> universes = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    universes.add(readUniverse(rs));
                }
            }
            return List.copyOf(universes);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to list MMP universes", e);
        }
    }

    @Override
    public List<String> listUniverseSubjects(String universeId) {
        try (PreparedStatement ps = connection.prepareStatement("""
                select subject_id from mmp_universe_subject
                where universe_id = ?
                order by ordinal
                """)) {
            ps.setString(1, universeId);
            ArrayList<String> subjects = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    subjects.add(rs.getString("subject_id"));
                }
            }
            return List.copyOf(subjects);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to list MMP universe subjects " + universeId, e);
        }
    }

    @Override
    public void replaceFragmentationRecords(String universeId, List<MmpFragmentationRecord> records) {
        runInTransaction(() -> {
            executeUpdate("delete from mmp_fragmentation_record where universe_id = ?", universeId);
            try (PreparedStatement ps = connection.prepareStatement("""
                    insert into mmp_fragmentation_record
                    (universe_id, canonical_record_id, compound_id, cut_count, key_idcode, value_idcode,
                     key_heavy_atom_count, value_heavy_atom_count, cut_bond_indices)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                for (MmpFragmentationRecord record : records == null ? List.<MmpFragmentationRecord>of() : records) {
                    ps.setString(1, universeId);
                    ps.setString(2, record.canonicalRecordId());
                    ps.setString(3, record.compoundId());
                    ps.setInt(4, record.cutCount());
                    ps.setString(5, record.keyIdcode());
                    ps.setString(6, record.valueIdcode());
                    ps.setInt(7, record.keyHeavyAtomCount());
                    ps.setInt(8, record.valueHeavyAtomCount());
                    ps.setString(9, joinInts(record.cutBondIndices()));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }

    @Override
    public void replacePairs(String universeId, List<MmpPair> pairs) {
        runInTransaction(() -> {
            executeUpdate("delete from mmp_pair where universe_id = ?", universeId);
            try (PreparedStatement ps = connection.prepareStatement("""
                    insert into mmp_pair
                    (universe_id, transform_id, compound_id_a, compound_id_b, value_a, value_b, delta,
                     key_idcode, from_value_idcode, to_value_idcode, cut_count)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                for (MmpPair pair : pairs == null ? List.<MmpPair>of() : pairs) {
                    bindPair(ps, universeId, pair);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }

    @Override
    public List<MmpPair> listPairs(String universeId) {
        try (PreparedStatement ps = connection.prepareStatement("""
                select compound_id_a, compound_id_b, value_a, value_b, delta, key_idcode,
                       from_value_idcode, to_value_idcode, transform_id, cut_count
                from mmp_pair
                where universe_id = ?
                order by transform_id, compound_id_a, compound_id_b, key_idcode
                """)) {
            ps.setString(1, universeId);
            ArrayList<MmpPair> pairs = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    pairs.add(readPair(rs));
                }
            }
            return List.copyOf(pairs);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to list MMP pairs for universe " + universeId, e);
        }
    }

    @Override
    public void saveStatsRun(MmpEndpointStatsRun run, List<MmpTransformStats> stats) {
        Objects.requireNonNull(run, "run");
        runInTransaction(() -> {
            executeUpdate("""
                    insert or replace into mmp_endpoint_stats_run
                    (run_id, endpoint_id, endpoint_subject_set_id, universe_id, mmp_config_hash,
                     stats_config_hash, created_at, subject_count, value_count, pair_count, stats_count, metadata)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    run.runId(),
                    run.endpointId(),
                    run.endpointSubjectSetId(),
                    run.universeId(),
                    run.mmpConfigHash(),
                    run.statsConfigHash(),
                    run.createdAt().toString(),
                    run.subjectCount(),
                    run.valueCount(),
                    run.pairCount(),
                    run.statsCount(),
                    run.metadata());
            executeUpdate("delete from mmp_endpoint_transform_stats where run_id = ?", run.runId());
            executeUpdate("delete from mmp_endpoint_example_pair where run_id = ?", run.runId());
            insertStats(run.runId(), stats == null ? List.of() : stats);
        });
    }

    @Override
    public Optional<MmpEndpointStatsRun> findStatsRun(String runId) {
        try (PreparedStatement ps = connection.prepareStatement("""
                select run_id, endpoint_id, endpoint_subject_set_id, universe_id, mmp_config_hash,
                       stats_config_hash, created_at, subject_count, value_count, pair_count, stats_count, metadata
                from mmp_endpoint_stats_run
                where run_id = ?
                """)) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new MmpEndpointStatsRun(
                        rs.getString("run_id"),
                        rs.getString("endpoint_id"),
                        rs.getString("endpoint_subject_set_id"),
                        rs.getString("universe_id"),
                        rs.getString("mmp_config_hash"),
                        rs.getString("stats_config_hash"),
                        Instant.parse(rs.getString("created_at")),
                        rs.getInt("subject_count"),
                        rs.getInt("value_count"),
                        rs.getInt("pair_count"),
                        rs.getInt("stats_count"),
                        rs.getString("metadata")
                ));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to read MMP stats run " + runId, e);
        }
    }

    @Override
    public List<MmpEndpointStatsRun> listStatsRuns() {
        try (PreparedStatement ps = connection.prepareStatement("""
                select run_id, endpoint_id, endpoint_subject_set_id, universe_id, mmp_config_hash,
                       stats_config_hash, created_at, subject_count, value_count, pair_count, stats_count, metadata
                from mmp_endpoint_stats_run
                order by created_at desc, run_id asc
                """)) {
            ArrayList<MmpEndpointStatsRun> runs = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    runs.add(readStatsRun(rs));
                }
            }
            return List.copyOf(runs);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to list MMP stats runs", e);
        }
    }

    @Override
    public List<MmpTransformStats> listTransformStats(String runId) {
        Map<String, List<MmpPair>> examplePairsByTransform = listExamplePairsByTransform(runId);
        try (PreparedStatement ps = connection.prepareStatement("""
                select transform_id, from_value_idcode, to_value_idcode, cut_count, support_count,
                       mean_delta, median_delta, standard_deviation, min_delta, max_delta, positive_fraction
                from mmp_endpoint_transform_stats
                where run_id = ?
                order by transform_id
                """)) {
            ps.setString(1, runId);
            ArrayList<MmpTransformStats> stats = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String transformId = rs.getString("transform_id");
                    stats.add(new MmpTransformStats(
                            transformId,
                            rs.getString("from_value_idcode"),
                            rs.getString("to_value_idcode"),
                            rs.getInt("cut_count"),
                            rs.getInt("support_count"),
                            rs.getDouble("mean_delta"),
                            rs.getDouble("median_delta"),
                            rs.getDouble("standard_deviation"),
                            rs.getDouble("min_delta"),
                            rs.getDouble("max_delta"),
                            rs.getDouble("positive_fraction"),
                            examplePairsByTransform.getOrDefault(transformId, List.of())
                    ));
                }
            }
            return List.copyOf(stats);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to list MMP transform stats for run " + runId, e);
        }
    }

    @Override
    public List<MmpTransformStats> findTransformStatsBySourceFragments(
            String runId,
            int cutCount,
            java.util.Set<String> fromValueIdcodes
    ) {
        if (cutCount < 1 || cutCount > 2) {
            throw new IllegalArgumentException("cutCount must be 1 or 2");
        }
        List<String> sources = normalizedKeys(fromValueIdcodes);
        if (sources.isEmpty()) return List.of();
        ArrayList<MmpTransformStats> stats = new ArrayList<>();
        for (List<String> chunk : chunks(sources)) {
            String placeholders = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
            String sql = """
                    select transform_id, from_value_idcode, to_value_idcode, cut_count, support_count,
                           mean_delta, median_delta, standard_deviation, min_delta, max_delta, positive_fraction
                    from mmp_endpoint_transform_stats
                    where run_id = ? and cut_count = ? and from_value_idcode in (%s)
                    order by transform_id
                    """.formatted(placeholders);
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, runId);
                ps.setInt(2, cutCount);
                bindStrings(ps, 3, chunk);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) stats.add(readTransformStats(rs, List.of()));
                }
            } catch (SQLException e) {
                throw new IllegalStateException(
                        "failed to find MMP transform stats by source fragments for run " + runId, e);
            }
        }
        return attachExamplePairs(runId, stats);
    }

    @Override
    public List<MmpTransformStats> findTransformStatsByIds(
            String runId,
            java.util.Set<String> transformIds
    ) {
        List<String> ids = normalizedKeys(transformIds);
        if (ids.isEmpty()) return List.of();
        ArrayList<MmpTransformStats> stats = new ArrayList<>();
        for (List<String> chunk : chunks(ids)) {
            String placeholders = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
            String sql = """
                    select transform_id, from_value_idcode, to_value_idcode, cut_count, support_count,
                           mean_delta, median_delta, standard_deviation, min_delta, max_delta, positive_fraction
                    from mmp_endpoint_transform_stats
                    where run_id = ? and transform_id in (%s)
                    order by transform_id
                    """.formatted(placeholders);
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, runId);
                bindStrings(ps, 2, chunk);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) stats.add(readTransformStats(rs, List.of()));
                }
            } catch (SQLException e) {
                throw new IllegalStateException(
                        "failed to find MMP transform stats by ID for run " + runId, e);
            }
        }
        return attachExamplePairs(runId, stats);
    }

    private static MmpUniverse readUniverse(ResultSet rs) throws SQLException {
        return new MmpUniverse(
                rs.getString("universe_id"),
                rs.getString("name"),
                splitLines(rs.getString("subject_set_ids")),
                rs.getString("mmp_config_hash"),
                Instant.parse(rs.getString("created_at")),
                rs.getString("metadata")
        );
    }

    private static MmpEndpointStatsRun readStatsRun(ResultSet rs) throws SQLException {
        return new MmpEndpointStatsRun(
                rs.getString("run_id"),
                rs.getString("endpoint_id"),
                rs.getString("endpoint_subject_set_id"),
                rs.getString("universe_id"),
                rs.getString("mmp_config_hash"),
                rs.getString("stats_config_hash"),
                Instant.parse(rs.getString("created_at")),
                rs.getInt("subject_count"),
                rs.getInt("value_count"),
                rs.getInt("pair_count"),
                rs.getInt("stats_count"),
                rs.getString("metadata")
        );
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new IllegalStateException("failed to close SQLite MMP analytics repository", e);
        }
    }

    private void createSchema() {
        runInTransaction(() -> {
            execute("""
                    create table if not exists mmp_universe (
                      universe_id text primary key,
                      name text not null,
                      subject_set_ids text,
                      mmp_config_hash text not null,
                      created_at text not null,
                      metadata text
                    )
                    """);
            execute("""
                    create table if not exists mmp_universe_subject (
                      universe_id text not null,
                      subject_id text not null,
                      ordinal integer not null,
                      primary key (universe_id, subject_id),
                      foreign key (universe_id) references mmp_universe(universe_id)
                    )
                    """);
            execute("""
                    create table if not exists mmp_fragmentation_record (
                      universe_id text not null,
                      canonical_record_id text not null,
                      compound_id text not null,
                      cut_count integer not null,
                      key_idcode text not null,
                      value_idcode text not null,
                      key_heavy_atom_count integer not null,
                      value_heavy_atom_count integer not null,
                      cut_bond_indices text,
                      primary key (universe_id, canonical_record_id)
                    )
                    """);
            execute("""
                    create table if not exists mmp_pair (
                      universe_id text not null,
                      transform_id text not null,
                      compound_id_a text not null,
                      compound_id_b text not null,
                      value_a real,
                      value_b real,
                      delta real,
                      key_idcode text not null,
                      from_value_idcode text not null,
                      to_value_idcode text not null,
                      cut_count integer not null
                    )
                    """);
            execute("""
                    create table if not exists mmp_endpoint_stats_run (
                      run_id text primary key,
                      endpoint_id text not null,
                      endpoint_subject_set_id text not null,
                      universe_id text not null,
                      mmp_config_hash text not null,
                      stats_config_hash text not null,
                      created_at text not null,
                      subject_count integer not null,
                      value_count integer not null,
                      pair_count integer not null,
                      stats_count integer not null,
                      metadata text
                    )
                    """);
            execute("""
                    create table if not exists mmp_endpoint_transform_stats (
                      run_id text not null,
                      transform_id text not null,
                      from_value_idcode text not null,
                      to_value_idcode text not null,
                      cut_count integer not null,
                      support_count integer not null,
                      mean_delta real not null,
                      median_delta real not null,
                      standard_deviation real not null,
                      min_delta real not null,
                      max_delta real not null,
                      positive_fraction real not null,
                      primary key (run_id, transform_id)
                    )
                    """);
            execute("""
                    create table if not exists mmp_endpoint_example_pair (
                      run_id text not null,
                      transform_id text not null,
                      ordinal integer not null,
                      compound_id_a text not null,
                      compound_id_b text not null,
                      value_a real,
                      value_b real,
                      delta real,
                      key_idcode text not null,
                      from_value_idcode text not null,
                      to_value_idcode text not null,
                      cut_count integer not null,
                      primary key (run_id, transform_id, ordinal)
                    )
                    """);
            execute("create index if not exists idx_mmp_pair_universe_transform on mmp_pair(universe_id, transform_id)");
            execute("create index if not exists idx_mmp_pair_universe_compounds on mmp_pair(universe_id, compound_id_a, compound_id_b)");
            execute("create index if not exists idx_mmp_stats_endpoint on mmp_endpoint_stats_run(endpoint_id, universe_id)");
        });
    }

    private void insertStats(String runId, List<MmpTransformStats> stats) throws SQLException {
        try (PreparedStatement statPs = connection.prepareStatement("""
                insert into mmp_endpoint_transform_stats
                (run_id, transform_id, from_value_idcode, to_value_idcode, cut_count, support_count,
                 mean_delta, median_delta, standard_deviation, min_delta, max_delta, positive_fraction)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """);
             PreparedStatement pairPs = connection.prepareStatement("""
                insert into mmp_endpoint_example_pair
                (run_id, transform_id, ordinal, compound_id_a, compound_id_b, value_a, value_b, delta,
                 key_idcode, from_value_idcode, to_value_idcode, cut_count)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (MmpTransformStats stat : stats) {
                statPs.setString(1, runId);
                statPs.setString(2, stat.transformId());
                statPs.setString(3, stat.fromValueIdcode());
                statPs.setString(4, stat.toValueIdcode());
                statPs.setInt(5, stat.cutCount());
                statPs.setInt(6, stat.supportCount());
                statPs.setDouble(7, stat.meanDelta());
                statPs.setDouble(8, stat.medianDelta());
                statPs.setDouble(9, stat.standardDeviation());
                statPs.setDouble(10, stat.minDelta());
                statPs.setDouble(11, stat.maxDelta());
                statPs.setDouble(12, stat.positiveFraction());
                statPs.addBatch();

                int ordinal = 0;
                for (MmpPair pair : stat.examplePairs()) {
                    pairPs.setString(1, runId);
                    pairPs.setString(2, stat.transformId());
                    pairPs.setInt(3, ordinal++);
                    bindPairColumns(pairPs, 4, pair);
                    pairPs.addBatch();
                }
            }
            statPs.executeBatch();
            pairPs.executeBatch();
        }
    }

    private Map<String, List<MmpPair>> listExamplePairsByTransform(String runId) {
        try (PreparedStatement ps = connection.prepareStatement("""
                select compound_id_a, compound_id_b, value_a, value_b, delta, key_idcode,
                       from_value_idcode, to_value_idcode, transform_id, cut_count
                from mmp_endpoint_example_pair
                where run_id = ?
                order by transform_id, ordinal
                """)) {
            ps.setString(1, runId);
            LinkedHashMap<String, ArrayList<MmpPair>> mutable = new LinkedHashMap<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    MmpPair pair = readPair(rs);
                    mutable.computeIfAbsent(pair.transformId(), ignored -> new ArrayList<>()).add(pair);
                }
            }
            LinkedHashMap<String, List<MmpPair>> result = new LinkedHashMap<>();
            mutable.forEach((transformId, pairs) -> result.put(transformId, List.copyOf(pairs)));
            return Map.copyOf(result);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to list MMP example pairs for run " + runId, e);
        }
    }

    private List<MmpTransformStats> attachExamplePairs(
            String runId,
            List<MmpTransformStats> stats
    ) {
        if (stats.isEmpty()) return List.of();
        java.util.Set<String> ids = stats.stream()
                .map(MmpTransformStats::transformId)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        Map<String, List<MmpPair>> examples = listExamplePairsByTransformIds(runId, ids);
        return stats.stream()
                .map(stat -> new MmpTransformStats(
                        stat.transformId(), stat.fromValueIdcode(), stat.toValueIdcode(),
                        stat.cutCount(), stat.supportCount(), stat.meanDelta(), stat.medianDelta(),
                        stat.standardDeviation(), stat.minDelta(), stat.maxDelta(),
                        stat.positiveFraction(),
                        examples.getOrDefault(stat.transformId(), List.of())))
                .sorted(java.util.Comparator.comparing(MmpTransformStats::transformId))
                .toList();
    }

    private Map<String, List<MmpPair>> listExamplePairsByTransformIds(
            String runId,
            java.util.Set<String> transformIds
    ) {
        List<String> ids = normalizedKeys(transformIds);
        if (ids.isEmpty()) return Map.of();
        LinkedHashMap<String, ArrayList<MmpPair>> mutable = new LinkedHashMap<>();
        for (List<String> chunk : chunks(ids)) {
            String placeholders = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
            String sql = """
                    select compound_id_a, compound_id_b, value_a, value_b, delta, key_idcode,
                           from_value_idcode, to_value_idcode, transform_id, cut_count
                    from mmp_endpoint_example_pair
                    where run_id = ? and transform_id in (%s)
                    order by transform_id, ordinal
                    """.formatted(placeholders);
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, runId);
                bindStrings(ps, 2, chunk);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        MmpPair pair = readPair(rs);
                        mutable.computeIfAbsent(pair.transformId(), ignored -> new ArrayList<>()).add(pair);
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException(
                        "failed to find MMP example pairs for run " + runId, e);
            }
        }
        LinkedHashMap<String, List<MmpPair>> result = new LinkedHashMap<>();
        mutable.forEach((transformId, pairs) -> result.put(transformId, List.copyOf(pairs)));
        return Map.copyOf(result);
    }

    private static MmpTransformStats readTransformStats(
            ResultSet rs,
            List<MmpPair> examples
    ) throws SQLException {
        return new MmpTransformStats(
                rs.getString("transform_id"),
                rs.getString("from_value_idcode"),
                rs.getString("to_value_idcode"),
                rs.getInt("cut_count"),
                rs.getInt("support_count"),
                rs.getDouble("mean_delta"),
                rs.getDouble("median_delta"),
                rs.getDouble("standard_deviation"),
                rs.getDouble("min_delta"),
                rs.getDouble("max_delta"),
                rs.getDouble("positive_fraction"),
                examples);
    }

    private static List<String> normalizedKeys(Iterable<String> values) {
        java.util.TreeSet<String> normalized = new java.util.TreeSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) normalized.add(value.trim());
            }
        }
        return List.copyOf(normalized);
    }

    private static List<List<String>> chunks(List<String> values) {
        ArrayList<List<String>> chunks = new ArrayList<>();
        for (int start = 0; start < values.size(); start += SQLITE_IN_CHUNK_SIZE) {
            chunks.add(values.subList(start, Math.min(values.size(), start + SQLITE_IN_CHUNK_SIZE)));
        }
        return List.copyOf(chunks);
    }

    private static void bindStrings(PreparedStatement statement, int offset, List<String> values)
            throws SQLException {
        for (int index = 0; index < values.size(); index++) {
            statement.setString(offset + index, values.get(index));
        }
    }

    private static void bindPair(PreparedStatement ps, String universeId, MmpPair pair) throws SQLException {
        ps.setString(1, universeId);
        ps.setString(2, pair.transformId());
        bindPairColumns(ps, 3, pair);
    }

    private static void bindPairColumns(PreparedStatement ps, int offset, MmpPair pair) throws SQLException {
        ps.setString(offset, pair.compoundIdA());
        ps.setString(offset + 1, pair.compoundIdB());
        setNullableDouble(ps, offset + 2, pair.valueA());
        setNullableDouble(ps, offset + 3, pair.valueB());
        setNullableDouble(ps, offset + 4, pair.delta());
        ps.setString(offset + 5, pair.keyIdcode());
        ps.setString(offset + 6, pair.fromValueIdcode());
        ps.setString(offset + 7, pair.toValueIdcode());
        ps.setInt(offset + 8, pair.cutCount());
    }

    private static MmpPair readPair(ResultSet rs) throws SQLException {
        return new MmpPair(
                rs.getString("compound_id_a"),
                rs.getString("compound_id_b"),
                getNullableDouble(rs, "value_a"),
                getNullableDouble(rs, "value_b"),
                getNullableDouble(rs, "delta"),
                rs.getString("key_idcode"),
                rs.getString("from_value_idcode"),
                rs.getString("to_value_idcode"),
                rs.getString("transform_id"),
                rs.getInt("cut_count")
        );
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void executeUpdate(String sql, Object... values) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                ps.setObject(i + 1, values[i]);
            }
            ps.executeUpdate();
        }
    }

    private void runInTransaction(SqlWork work) {
        try {
            boolean oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                work.run();
                connection.commit();
            } catch (SQLException | RuntimeException t) {
                connection.rollback();
                throw t;
            } finally {
                connection.setAutoCommit(oldAutoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite MMP analytics operation failed", e);
        }
    }

    private static void setNullableDouble(PreparedStatement ps, int index, Double value) throws SQLException {
        if (value == null) {
            ps.setObject(index, null);
        } else {
            ps.setDouble(index, value);
        }
    }

    private static Double getNullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static String joinLines(List<String> values) {
        return values == null ? "" : String.join("\n", values);
    }

    private static List<String> splitLines(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split("\\R")).filter(s -> !s.isBlank()).toList();
    }

    private static String joinInts(List<Integer> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    @FunctionalInterface
    private interface SqlWork {
        void run() throws SQLException;
    }
}
