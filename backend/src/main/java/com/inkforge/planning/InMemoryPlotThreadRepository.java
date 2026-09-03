package com.inkforge.planning;

import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** InMemory PlotThread 仓储：id 主索引 + (novelId, normalizedTitle) 查找索引。 */
@Repository
public class InMemoryPlotThreadRepository implements PlotThreadRepository {

    private final Map<String, PlotThread> byId = new ConcurrentHashMap<>();

    @Override
    public PlotThread save(PlotThread thread) {
        byId.put(thread.id(), thread);
        return thread;
    }

    @Override
    public Optional<PlotThread> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<PlotThread> findByTitle(String novelId, String normalizedTitle) {
        return byId.values().stream()
                .filter(t -> t.novelId().equals(novelId))
                .filter(t -> PlotThread.normalized(t.title()).equals(normalizedTitle))
                .findFirst();
    }

    @Override
    public List<PlotThread> findByNovelId(String novelId) {
        return byId.values().stream()
                .filter(t -> t.novelId().equals(novelId))
                .sorted(Comparator.comparing(PlotThread::createdAt))
                .toList();
    }

    @Override
    public List<PlotThread> findOpenByNovelId(String novelId) {
        return findByNovelId(novelId).stream()
                .filter(t -> t.status() == PlotThreadStatus.OPEN)
                .toList();
    }
}
