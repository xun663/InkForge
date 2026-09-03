package com.inkforge.generation;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Phase 1 in-memory log storage (accepted limitation, see docs/architecture.md). */
@Component
public class InMemoryGenerationLogRepository implements GenerationLogRepository {

    private final Map<String, GenerationLog> store = new ConcurrentHashMap<>();

    @Override
    public void save(GenerationLog log) {
        store.put(log.generationId(), log);
    }

    @Override
    public Optional<GenerationLog> findById(String generationId) {
        return Optional.ofNullable(store.get(generationId));
    }

    @Override
    public List<GenerationLog> findByNovelId(String novelId) {
        return store.values().stream()
                .filter(log -> log.novelId().equals(novelId))
                .toList();
    }
}
