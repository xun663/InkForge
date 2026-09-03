package com.inkforge.retrieval;

import java.util.List;
import java.util.Optional;

/** Persistence port for retrieval traces (observation data, separate from Story Memory). */
public interface RetrievalTraceRepository {

    void save(RetrievalTrace trace);

    Optional<RetrievalTrace> findById(String traceId);

    List<RetrievalTrace> findByNovelId(String novelId, int limit);
}
