package com.inkforge.retrieval.web;

import com.inkforge.common.NotFoundException;
import com.inkforge.retrieval.RetrievalTrace;
import com.inkforge.retrieval.RetrievalTraceRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Retrieval trace API (observability). Traces are scoped per novel — a traceId is
 * only readable under its own novel, never leaked across novels.
 */
@RestController
@RequestMapping("/api/novels/{novelId}/retrieval-traces")
public class RetrievalTraceController {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;

    private final RetrievalTraceRepository traceRepository;

    public RetrievalTraceController(RetrievalTraceRepository traceRepository) {
        this.traceRepository = traceRepository;
    }

    @GetMapping("/{traceId}")
    public RetrievalTrace get(@PathVariable String novelId, @PathVariable String traceId) {
        RetrievalTrace trace = traceRepository.findById(traceId)
                .orElseThrow(() -> new NotFoundException("检索 Trace 不存在: " + traceId));
        if (!trace.novelId().equals(novelId)) {
            throw new NotFoundException("检索 Trace 不存在: " + traceId); // 不泄露其他小说 trace
        }
        return trace;
    }

    @GetMapping
    public List<RetrievalTrace> list(@PathVariable String novelId,
                                     @RequestParam(defaultValue = "10") int limit) {
        int effectiveLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        return traceRepository.findByNovelId(novelId, effectiveLimit);
    }
}
