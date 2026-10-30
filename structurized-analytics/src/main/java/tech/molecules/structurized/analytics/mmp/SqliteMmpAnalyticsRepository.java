package tech.molecules.structurized.analytics.mmp;

import tech.molecules.structurized.mmp.MmpFragmentationRecord;
import tech.molecules.structurized.mmp.MmpPair;
import tech.molecules.structurized.mmp.MmpTransformStats;

import java.nio.file.Files;
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
        MmpEndpointStatsRepository, MmpMiningConfigRepository, AutoCloseable {
    private static final int SQLITE_IN_CHUNK_SIZE = 400;
    private final Connection connection;

    public SqliteMmpAnalyticsRepository(Connection connection) {
        this(connection, true);
    }

    private SqliteMmpAnalyticsRepository(Connection connection, boolean initializeSchema) {
        this.connection = Objects.requireNonNull(connection, "connection");
        if (initializeSchema) createSchema();
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


    /** Opens an existing regular SQLite artifact without creating or modifying any file or schema. */
    public static SqliteMmpAnalyticsRepository openReadOnly(Path databasePath) {
        Objects.requireNonNull(databasePath, "databasePath");
        try {
            Path real = databasePath.toRealPath();
            if (!Files.isRegularFile(real)) {
                throw new IllegalArgumentException(
                        "MMP artifact must be an existing regular file: " + databasePath);
            }
            Connection connection = DriverManager.getConnection("jdbc:sqlite:file:" + real + "?mode=ro");
            SqliteMmpAnalyticsRepository repository = new SqliteMmpAnalyticsRepository(connection, false);
            repository.validateReadableSchema();
            return repository;
        } catch (java.io.IOException | SQLException e) {
            throw new IllegalStateException(
                    "failed to open read-only SQLite MMP analytics artifact " + databasePath, e);
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
        return listUniverses(Integer.MAX_VALUE);
    }

    /** Bounded metadata read for capability discovery; does not load subjects or pairs. */
    public List<MmpUniverse> listUniverses(int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        try (PreparedStatement ps = connection.prepareStatement("""
                select universe_id, name, subject_set_ids, mmp_config_hash, created_at, metadata
                from mmp_universe
                order by created_at desc, universe_id asc limit ?
                """)) {
            ps.setInt(1, limit);
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
                    (universe_id, transform_id, compound_id_a, compound_id_b,
                     key_idcode, from_value_idcode, to_value_idcode, cut_count)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
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
                select compound_id_a, compound_id_b, key_idcode,
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
            executeUpdate("delete from mmp_endpoint_pair where run_id = ?", run.runId());
            insertStats(run.runId(), stats == null ? List.of() : stats);
        });
    }

    @Override
    public void saveSubjectValues(String runId, Map<String, Double> valuesBySubjectId) {
        Objects.requireNonNull(runId, "runId");
        Map<String, Double> values = valuesBySubjectId == null ? Map.of() : valuesBySubjectId;
        runInTransaction(() -> {
            executeUpdate("delete from mmp_endpoint_subject_value where run_id = ?", runId);
            try (PreparedStatement ps = connection.prepareStatement("""
                    insert or replace into mmp_endpoint_subject_value (run_id, subject_id, value)
                    values (?, ?, ?)
                    """)) {
                for (Map.Entry<String, Double> entry : values.entrySet()) {
                    if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                        continue;
                    }
                    ps.setString(1, runId);
                    ps.setString(2, entry.getKey());
                    ps.setDouble(3, entry.getValue());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }

    @Override
    public Map<String, Double> findSubjectValues(String runId) {
        if (!hasTable("mmp_endpoint_subject_value")) return Map.of();
        try (PreparedStatement ps = connection.prepareStatement("""
                select subject_id, value from mmp_endpoint_subject_value where run_id = ?
                """)) {
            ps.setString(1, runId);
            LinkedHashMap<String, Double> values = new LinkedHashMap<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    values.put(rs.getString("subject_id"), rs.getDouble("value"));
                }
            }
            return Map.copyOf(values);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to read MMP subject values for run " + runId, e);
        }
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
        return listStatsRuns(Integer.MAX_VALUE);
    }

    /** Bounded metadata read for capability discovery; does not load transform statistics. */
    public List<MmpEndpointStatsRun> listStatsRuns(int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        try (PreparedStatement ps = connection.prepareStatement("""
                select run_id, endpoint_id, endpoint_subject_set_id, universe_id, mmp_config_hash,
                       stats_config_hash, created_at, subject_count, value_count, pair_count, stats_count, metadata
                from mmp_endpoint_stats_run
                order by created_at desc, run_id asc limit ?
                """)) {
            ps.setInt(1, limit);
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
        try (PreparedStatement ps = connection.prepareStatement("""
                select transform_id, from_value_idcode, to_value_idcode, cut_count, support_count,
                       mean_delta, median_delta, standard_deviation, min_delta, max_delta, positive_fraction,
                       up_count, down_count, tie_count
                from mmp_endpoint_transform_stats
                where run_id = ?
                order by transform_id
                """)) {
            ps.setString(1, runId);
            ArrayList<MmpTransformStats> stats = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    // Supporting pairs are not attached here: one run can hold millions of them.
                    // Use findEndpointPairs for a specific transform.
                    stats.add(readTransformStats(rs, List.of()));
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
                           mean_delta, median_delta, standard_deviation, min_delta, max_delta, positive_fraction,
                           up_count, down_count, tie_count
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
        return attachSupportingPairs(runId, stats);
    }

    @Override
    public List<MmpTransformStats> findTransformStatsByIds(
            String runId,
            java.util.Set<String> transformIds
    ) {
        return attachSupportingPairs(runId, findTransformStatsByIdsWithoutPairs(runId, transformIds));
    }

    /**
     * Raw by-ID stats lookup that never loads pairs. The pair loaders need transform identity, so
     * they must not call the public variant, which would recurse back into pair loading.
     */
    private List<MmpTransformStats> findTransformStatsByIdsWithoutPairs(
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
                           mean_delta, median_delta, standard_deviation, min_delta, max_delta, positive_fraction,
                           up_count, down_count, tie_count
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
        return List.copyOf(stats);
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
    public void saveMiningConfig(String configHash, MmpMiningConfigSnapshot config) {
        Objects.requireNonNull(configHash, "configHash");
        Objects.requireNonNull(config, "config");
        runInTransaction(() -> {
            executeUpdate("""
                insert or replace into mmp_mining_config
                (config_hash, max_cuts, single_bonds_only, skip_small_rings, allow_macrocycle_ring_cuts,
                 macrocycle_min_ring_size, allow_mixed_ring_chain_cut_sets, min_key_heavy_atoms,
                 min_variable_heavy_atoms, max_variable_heavy_atoms, max_variable_fraction,
                 max_fragmentation_records, max_pairs_per_key, emit_reverse_transforms,
                 min_transform_support, allow_hydrogen_cuts, no_cut_rule_profile)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, configHash, config.maxCuts(), config.singleBondsOnly(), config.skipSmallRings(),
                config.allowMacrocycleRingCuts(), config.macrocycleMinRingSize(),
                config.allowMixedRingChainCutSets(), config.minKeyHeavyAtoms(), config.minVariableHeavyAtoms(),
                config.maxVariableHeavyAtoms(), config.maxVariableToMolHeavyAtomFraction(),
                config.maxFragmentationRecordsPerCompound(), config.maxPairsPerKey(),
                config.emitReverseTransforms(), config.minTransformSupport(),
                config.allowHydrogenCuts(), config.noCutRuleProfile());
        });
    }

    @Override
    public Optional<MmpMiningConfigSnapshot> findMiningConfig(String configHash) {
        if (!hasTable("mmp_mining_config")) return Optional.empty();
        try (PreparedStatement ps = connection.prepareStatement("""
                select max_cuts, single_bonds_only, skip_small_rings, allow_macrocycle_ring_cuts,
                       macrocycle_min_ring_size, allow_mixed_ring_chain_cut_sets, min_key_heavy_atoms,
                       min_variable_heavy_atoms, max_variable_heavy_atoms, max_variable_fraction,
                       max_fragmentation_records, max_pairs_per_key, emit_reverse_transforms,
                       min_transform_support, no_cut_rule_profile, allow_hydrogen_cuts
                from mmp_mining_config where config_hash = ?
                """)) {
            ps.setString(1, configHash);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new MmpMiningConfigSnapshot(
                        rs.getInt(1), rs.getBoolean(2), rs.getBoolean(3), rs.getBoolean(4),
                        rs.getInt(5), rs.getBoolean(6), rs.getInt(7), rs.getInt(8), rs.getInt(9),
                        rs.getDouble(10), rs.getInt(11), rs.getInt(12), rs.getBoolean(13),
                        rs.getInt(14),
                        rs.getBoolean(16),
                        rs.getString(15)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to read MMP mining configuration " + configHash, e);
        }
    }

    /** Reads optional artifact header metadata through this already-open connection. */
    public Optional<String> findArtifactMetadata(String key) {
        Objects.requireNonNull(key, "key");
        if (!hasTable("mmp_artifact_metadata")) return Optional.empty();
        try (PreparedStatement ps = connection.prepareStatement(
                "select metadata_value from mmp_artifact_metadata where metadata_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to read MMP artifact metadata", e);
        }
    }

    /** Returns 1 for legacy artifacts and the persisted schema version for newer artifacts. */
    public int artifactSchemaVersion() {
        if (!hasTable("mmp_artifact_metadata")) return 1;
        try (PreparedStatement ps = connection.prepareStatement(
                "select metadata_value from mmp_artifact_metadata where metadata_key = 'schema_version'");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Integer.parseInt(rs.getString(1)) : 1;
        } catch (SQLException | NumberFormatException e) {
            throw new IllegalStateException("failed to read MMP artifact schema version", e);
        }
    }

    private void validateReadableSchema() {
        for (String table : List.of("mmp_universe", "mmp_fragmentation_record", "mmp_pair",
                "mmp_endpoint_stats_run", "mmp_endpoint_transform_stats", "mmp_endpoint_pair",
                "mmp_endpoint_subject_value")) {
            if (!hasTable(table)) {
                close();
                throw new IllegalArgumentException("not a readable MMP analytics artifact; missing table " + table);
            }
        }
    }

    private boolean hasTable(String table) {        try (PreparedStatement ps = connection.prepareStatement(
                "select 1 from sqlite_master where type = 'table' and name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to inspect SQLite MMP artifact schema", e);
        }
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
                    create table if not exists mmp_artifact_metadata (
                      metadata_key text primary key,
                      metadata_value text not null
                    )
                    """);
            execute("""
                    create table if not exists mmp_mining_config (
                      config_hash text primary key,
                      max_cuts integer not null,
                      single_bonds_only integer not null,
                      skip_small_rings integer not null,
                      allow_macrocycle_ring_cuts integer not null,
                      macrocycle_min_ring_size integer not null,
                      allow_mixed_ring_chain_cut_sets integer not null,
                      min_key_heavy_atoms integer not null,
                      min_variable_heavy_atoms integer not null,
                      max_variable_heavy_atoms integer not null,
                      max_variable_fraction real not null,
                      max_fragmentation_records integer not null,
                      max_pairs_per_key integer not null,
                      emit_reverse_transforms integer not null,
                      min_transform_support integer not null,
                      allow_hydrogen_cuts integer not null,
                      no_cut_rule_profile text not null
                    )
                    """);
            executeUpdate("""
                    insert or replace into mmp_artifact_metadata (metadata_key, metadata_value)
                    values ('schema_version', '4')
                    """);
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
                      up_count integer not null,
                      down_count integer not null,
                      tie_count integer not null,
                      primary key (run_id, transform_id)
                    )
                    """);
            execute("""
                    create table if not exists mmp_endpoint_subject_value (
                      run_id text not null,
                      subject_id text not null,
                      value real not null,
                      primary key (run_id, subject_id)
                    )
                    """);
            // Every supporting pair of every transform, per endpoint run. This replaces the old
            // capped example-pair table so that low-support transforms can be shown in full.
            execute("""
                    create table if not exists mmp_endpoint_pair (
                      run_id text not null,
                      transform_id text not null,
                      compound_id_a text not null,
                      compound_id_b text not null,
                      key_idcode text not null,
                      value_a real not null,
                      value_b real not null,
                      delta real not null
                    )
                    """);
            execute("create index if not exists idx_mmp_pair_universe_transform on mmp_pair(universe_id, transform_id)");
            execute("create index if not exists idx_mmp_pair_universe_compounds on mmp_pair(universe_id, compound_id_a, compound_id_b)");
            execute("create index if not exists idx_mmp_stats_endpoint on mmp_endpoint_stats_run(endpoint_id, universe_id)");
            execute("create index if not exists idx_mmp_endpoint_pair_transform on mmp_endpoint_pair(run_id, transform_id)");
        });
    }

    private void insertStats(String runId, List<MmpTransformStats> stats) throws SQLException {
        try (PreparedStatement statPs = connection.prepareStatement("""
                insert into mmp_endpoint_transform_stats
                (run_id, transform_id, from_value_idcode, to_value_idcode, cut_count, support_count,
                 mean_delta, median_delta, standard_deviation, min_delta, max_delta, positive_fraction,
                 up_count, down_count, tie_count)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """);
             PreparedStatement pairPs = connection.prepareStatement("""
                insert into mmp_endpoint_pair
                (run_id, transform_id, compound_id_a, compound_id_b, key_idcode,
                 value_a, value_b, delta)
                values (?, ?, ?, ?, ?, ?, ?, ?)
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
                statPs.setInt(13, stat.upCount());
                statPs.setInt(14, stat.downCount());
                statPs.setInt(15, stat.tieCount());
                statPs.addBatch();

                // Fragment identity and cut count live on the stats row and are not repeated per
                // pair, which is what keeps the complete pair table affordable. key_idcode is kept
                // because the constant part differs from pair to pair within one transform.
                for (MmpPair pair : stat.supportingPairs()) {
                    pairPs.setString(1, runId);
                    pairPs.setString(2, stat.transformId());
                    pairPs.setString(3, pair.compoundIdA());
                    pairPs.setString(4, pair.compoundIdB());
                    pairPs.setString(5, pair.keyIdcode());
                    pairPs.setDouble(6, requireValue(pair.valueA(), "valueA", stat.transformId()));
                    pairPs.setDouble(7, requireValue(pair.valueB(), "valueB", stat.transformId()));
                    pairPs.setDouble(8, requireValue(pair.delta(), "delta", stat.transformId()));
                    pairPs.addBatch();
                }
            }
            statPs.executeBatch();
            pairPs.executeBatch();
        }
    }

    private static double requireValue(Double value, String field, String transformId) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "supporting pair of transform " + transformId + " has no " + field);
        }
        return value;
    }

    /**
     * Attaches supporting pairs to a bounded set of transforms. Used by the targeted lookups that
     * back recommendation evidence; {@link #listTransformStats(String)} deliberately does not do
     * this because a run can hold millions of pairs.
     */
    private List<MmpTransformStats> attachSupportingPairs(
            String runId,
            List<MmpTransformStats> stats
    ) {
        if (stats.isEmpty()) return List.of();
        LinkedHashMap<String, MmpTransformStats> byId = new LinkedHashMap<>();
        for (MmpTransformStats stat : stats) {
            byId.put(stat.transformId(), stat);
        }
        Map<String, List<MmpPair>> pairs = findEndpointPairs(runId, byId.keySet());
        return byId.values().stream()
                .map(stat -> stat.withSupportingPairs(
                        pairs.getOrDefault(stat.transformId(), List.of())))
                .sorted(java.util.Comparator.comparing(MmpTransformStats::transformId))
                .toList();
    }

    @Override
    public List<MmpPair> findEndpointPairs(String runId, String transformId, int limit, int offset) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(transformId, "transformId");
        if (limit < 1 || offset < 0) {
            throw new IllegalArgumentException("limit must be positive and offset non-negative");
        }
        Optional<MmpTransformStats> stat =
                findTransformStatsByIdsWithoutPairs(runId, java.util.Set.of(transformId))
                        .stream().findFirst();
        if (stat.isEmpty()) return List.of();
        MmpTransformStats identity = stat.get();
        try (PreparedStatement ps = connection.prepareStatement("""
                select transform_id, compound_id_a, compound_id_b, key_idcode,
                       value_a, value_b, delta
                from mmp_endpoint_pair
                where run_id = ? and transform_id = ?
                order by compound_id_a, compound_id_b
                limit ? offset ?
                """)) {
            ps.setString(1, runId);
            ps.setString(2, transformId);
            ps.setInt(3, limit);
            ps.setInt(4, offset);
            ArrayList<MmpPair> pairs = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    pairs.add(readEndpointPair(rs, identity.fromValueIdcode(),
                            identity.toValueIdcode(), identity.cutCount()));
                }
            }
            return List.copyOf(pairs);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "failed to read MMP endpoint pairs for run " + runId, e);
        }
    }

    /** Loads all supporting pairs for a bounded set of transform IDs. */
    private Map<String, List<MmpPair>> findEndpointPairs(
            String runId,
            java.util.Set<String> transformIds
    ) {
        List<String> ids = normalizedKeys(transformIds);
        if (ids.isEmpty()) return Map.of();
        Map<String, MmpTransformStats> identities = new LinkedHashMap<>();
        for (MmpTransformStats stat : findTransformStatsByIdsWithoutPairs(
                runId, java.util.Set.copyOf(ids))) {
            identities.put(stat.transformId(), stat);
        }
        LinkedHashMap<String, ArrayList<MmpPair>> mutable = new LinkedHashMap<>();
        for (List<String> chunk : chunks(ids)) {
            String placeholders = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
            String sql = """
                    select transform_id, compound_id_a, compound_id_b, key_idcode,
                           value_a, value_b, delta
                    from mmp_endpoint_pair
                    where run_id = ? and transform_id in (%s)
                    order by transform_id, compound_id_a, compound_id_b
                    """.formatted(placeholders);
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, runId);
                bindStrings(ps, 2, chunk);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String transformId = rs.getString("transform_id");
                        MmpTransformStats identity = identities.get(transformId);
                        if (identity == null) continue;
                        mutable.computeIfAbsent(transformId, ignored -> new ArrayList<>())
                                .add(readEndpointPair(rs, identity.fromValueIdcode(),
                                        identity.toValueIdcode(), identity.cutCount()));
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException(
                        "failed to find MMP endpoint pairs for run " + runId, e);
            }
        }
        LinkedHashMap<String, List<MmpPair>> result = new LinkedHashMap<>();
        mutable.forEach((transformId, pairs) -> result.put(transformId, List.copyOf(pairs)));
        return Map.copyOf(result);
    }

    @Override
    public int countEndpointPairs(String runId, String transformId) {
        try (PreparedStatement ps = connection.prepareStatement("""
                select count(*) from mmp_endpoint_pair where run_id = ? and transform_id = ?
                """)) {
            ps.setString(1, runId);
            ps.setString(2, transformId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "failed to count MMP endpoint pairs for run " + runId, e);
        }
    }

    private static MmpTransformStats readTransformStats(
            ResultSet rs,
            List<MmpPair> supportingPairs
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
                rs.getInt("up_count"),
                rs.getInt("down_count"),
                rs.getInt("tie_count"),
                supportingPairs);
    }

    /** Reads an endpoint-independent structural pair; value columns do not exist on mmp_pair. */
    private static MmpPair readPair(ResultSet rs) throws SQLException {
        return new MmpPair(
                rs.getString("compound_id_a"),
                rs.getString("compound_id_b"),
                null,
                null,
                null,
                rs.getString("key_idcode"),
                rs.getString("from_value_idcode"),
                rs.getString("to_value_idcode"),
                rs.getString("transform_id"),
                rs.getInt("cut_count"));
    }

    /** Reads one valued pair of an endpoint run, reconstructing the fragment identity from stats. */
    private static MmpPair readEndpointPair(
            ResultSet rs, String fromValueIdcode, String toValueIdcode, int cutCount
    ) throws SQLException {
        return new MmpPair(
                rs.getString("compound_id_a"),
                rs.getString("compound_id_b"),
                rs.getDouble("value_a"),
                rs.getDouble("value_b"),
                rs.getDouble("delta"),
                rs.getString("key_idcode"),
                fromValueIdcode,
                toValueIdcode,
                rs.getString("transform_id"),
                cutCount);
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
        bindStructuralPair(ps, 3, pair);
    }

    private static void bindStructuralPair(PreparedStatement ps, int offset, MmpPair pair) throws SQLException {
        ps.setString(offset, pair.compoundIdA());
        ps.setString(offset + 1, pair.compoundIdB());
        ps.setString(offset + 2, pair.keyIdcode());
        ps.setString(offset + 3, pair.fromValueIdcode());
        ps.setString(offset + 4, pair.toValueIdcode());
        ps.setInt(offset + 5, pair.cutCount());
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
