package com.inkforge.generation;

import java.util.List;
import java.util.Optional;

/**
 * Persistence port for generation logs. Phase 1 in-memory; Phase 2/3 JPA.
 */
public interface GenerationLogRepository {

    void save(GenerationLog log);

    Optional<GenerationLog> findById(String generationId);

    List<GenerationLog> findByNovelId(String novelId);
}
