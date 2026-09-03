package com.inkforge.novel.web;

import com.inkforge.chapter.Chapter;
import com.inkforge.context.BreakpointAnalyzer;
import com.inkforge.context.BreakpointInfo;
import com.inkforge.novel.Novel;
import com.inkforge.novel.NovelService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/** Novel lifecycle API: upload, inspect, breakpoint. No business logic here. */
@RestController
@RequestMapping("/api/novels")
public class NovelController {

    private final NovelService novelService;
    private final BreakpointAnalyzer breakpointAnalyzer;

    public NovelController(NovelService novelService, BreakpointAnalyzer breakpointAnalyzer) {
        this.novelService = novelService;
        this.breakpointAnalyzer = breakpointAnalyzer;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public NovelSummaryDto upload(@RequestParam("file") MultipartFile file) throws IOException {
        return NovelSummaryDto.from(novelService.ingest(file.getBytes(), file.getOriginalFilename()));
    }

    @GetMapping("/{id}")
    public NovelSummaryDto get(@PathVariable String id) {
        return NovelSummaryDto.from(novelService.get(id));
    }

    @GetMapping("/{id}/chapters")
    public List<ChapterDto> chapters(@PathVariable String id) {
        return novelService.get(id).chapters().stream().map(ChapterDto::from).toList();
    }

    @GetMapping("/{id}/chapters/last")
    public LastChapterDto lastChapter(@PathVariable String id) {
        return LastChapterDto.from(novelService.get(id).lastChapter());
    }

    @GetMapping("/{id}/breakpoint")
    public BreakpointInfo breakpoint(@PathVariable String id) {
        return breakpointAnalyzer.analyze(novelService.get(id));
    }

    public record NovelSummaryDto(String id, String title, String sourceFileName, int chapterCount) {
        static NovelSummaryDto from(Novel novel) {
            return new NovelSummaryDto(novel.id(), novel.title(), novel.sourceFileName(), novel.chapterCount());
        }
    }

    public record ChapterDto(int ordinal, Integer chapterNo, String title, int charCount) {
        static ChapterDto from(Chapter chapter) {
            return new ChapterDto(chapter.ordinal(), chapter.chapterNo(), chapter.title(), chapter.charCount());
        }
    }

    public record LastChapterDto(int ordinal, Integer chapterNo, String title, String content, int charCount) {
        static LastChapterDto from(Chapter chapter) {
            return new LastChapterDto(chapter.ordinal(), chapter.chapterNo(), chapter.title(),
                    chapter.content(), chapter.charCount());
        }
    }
}
