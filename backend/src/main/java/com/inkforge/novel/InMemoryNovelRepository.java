package com.inkforge.novel;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 1 storage: process-local, lost on restart (accepted limitation,
 * see docs/architecture.md). The NovelRepository interface isolates callers
 * from this implementation.
 */
@Component
public class InMemoryNovelRepository implements NovelRepository {

    private final Map<String, Novel> store = new ConcurrentHashMap<>();

    @Override
    public Novel save(Novel novel) {
        store.put(novel.id(), novel);
        return novel;
    }

    @Override
    public Optional<Novel> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<Novel> findAll() {
        return List.copyOf(store.values());
    }
}
