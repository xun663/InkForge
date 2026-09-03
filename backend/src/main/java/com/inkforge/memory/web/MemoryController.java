package com.inkforge.memory.web;

import com.inkforge.memory.MemoryExtractionRecord;
import com.inkforge.memory.StoryMemoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Story Memory API. Extraction is synchronous and explicit — "upload" and
 * "build story memory" are two different user actions (docs/phase2-design.md §21).
 */
@RestController
@RequestMapping("/api/novels/{novelId}/memory")
public class MemoryController {

    private final StoryMemoryService storyMemoryService;

    public MemoryController(StoryMemoryService storyMemoryService) {
        this.storyMemoryService = storyMemoryService;
    }

    /**
     * Extracts memory for the most recent unprocessed chapters (default: extract-window).
     * Returns the per-chapter extraction records; empty list when nothing to do.
     */
    @PostMapping("/extract")
    public List<MemoryExtractionRecord> extract(@PathVariable String novelId,
                                                @RequestBody(required = false) ExtractRequest request) {
        int count = request == null || request.count() == null ? 0 : request.count();
        return storyMemoryService.extractRecent(novelId, count);
    }

    @GetMapping
    public StoryMemoryService.MemoryOverview overview(@PathVariable String novelId) {
        return storyMemoryService.overview(novelId);
    }

    public record ExtractRequest(Integer count) {
    }
}
