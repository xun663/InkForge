package com.inkforge.novel;

import java.util.List;
import java.util.Optional;

/**
 * Persistence port for novels. Phase 1 uses an in-memory implementation;
 * Phase 2/3 replaces it with JPA + PostgreSQL without touching callers.
 */
public interface NovelRepository {

    Novel save(Novel novel);

    Optional<Novel> findById(String id);

    List<Novel> findAll();
}
