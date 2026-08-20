package tech.molecules.structurized.analytics.mmp;

import java.util.Optional;

/** Optional artifact capability for storing resolved mining configurations by hash. */
public interface MmpMiningConfigRepository {
    void saveMiningConfig(String configHash, MmpMiningConfigSnapshot config);
    Optional<MmpMiningConfigSnapshot> findMiningConfig(String configHash);
}
