package com.inkforge.retrieval;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Default in-memory chunk store (rebuildable cache; lost on restart by design). */
@Component
public class InMemoryMemoryChunkRepository implements MemoryChunkRepository {

    private final Map<String, List<MemoryChunk>> chunksByNovel = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> revisions = new ConcurrentHashMap<>();

    @Override
    public List<MemoryChunk> findByNovelId(String novelId) {
        return List.copyOf(chunksByNovel.getOrDefault(novelId, List.of()));
    }

    @Override
    public List<MemoryChunk> findByNovelIdAndChapter(String novelId, int chapterOrdinal) {
        return chunksByNovel.getOrDefault(novelId, List.of()).stream()
                .filter(c -> c.chapterOrdinal() == chapterOrdinal)
                .toList();
    }

    @Override
    public synchronized void replaceForChapter(String novelId, int chapterOrdinal, List<MemoryChunk> chunks) {
        List<MemoryChunk> updated = new ArrayList<>(
                chunksByNovel.getOrDefault(novelId, List.of()));
        updated.removeIf(c -> c.chapterOrdinal() == chapterOrdinal);
        updated.addAll(chunks);
        chunksByNovel.put(novelId, List.copyOf(updated));
        revisions.computeIfAbsent(novelId, k -> new AtomicLong()).incrementAndGet();
    }

    @Override
    public synchronized void deleteByNovelId(String novelId) {
        chunksByNovel.remove(novelId);
        revisions.computeIfAbsent(novelId, k -> new AtomicLong()).incrementAndGet();
    }

    @Override
    public long revision(String novelId) {
        return revisions.getOrDefault(novelId, new AtomicLong()).get();
    }
}
