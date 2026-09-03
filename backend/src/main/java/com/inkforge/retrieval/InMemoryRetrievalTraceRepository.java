package com.inkforge.retrieval;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Default in-memory trace store. */
@Component
public class InMemoryRetrievalTraceRepository implements RetrievalTraceRepository {

    private final Map<String, RetrievalTrace> store = new ConcurrentHashMap<>();

    @Override
    public void save(RetrievalTrace trace) {
        store.put(trace.id(), trace);
    }

    @Override
    public Optional<RetrievalTrace> findById(String traceId) {
        return Optional.ofNullable(store.get(traceId));
    }

    @Override
    public List<RetrievalTrace> findByNovelId(String novelId, int limit) {
        return store.values().stream()
                .filter(t -> t.novelId().equals(novelId))
                .sorted(Comparator.comparing(RetrievalTrace::createdAt).reversed())
                .limit(limit)
                .toList();
    }
}
