package tech.molecules.structurized.analytics.mmp;

import java.util.List;
import java.util.Optional;

/**
 * Persistence API for MMP universe metadata and subject membership.
 */
public interface MmpUniverseRepository {
    void saveUniverse(MmpUniverse universe, List<String> subjectIds);

    Optional<MmpUniverse> findUniverse(String universeId);

    List<MmpUniverse> listUniverses();

    List<String> listUniverseSubjects(String universeId);
}
