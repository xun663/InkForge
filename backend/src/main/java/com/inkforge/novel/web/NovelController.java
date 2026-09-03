package com.inkforge.novel.web;

import com.inkforge.chapter.Chapter;
import com.inkforge.context.BreakpointAnalyzer;
import com.inkforge.context.BreakpointInfo;
import com.inkforge.novel.Novel;
import com.inkforge.novel.NovelService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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

    @GetMapping
    public List<NovelSummaryDto> list() {
        return novelService.list().stream().map(NovelSummaryDto::from).toList();
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

    @GetMapping("/{id}/chapters/{ordinal}")
    public LastChapterDto chapter(@PathVariable String id, @PathVariable int ordinal) {
        return LastChapterDto.from(novelService.getChapter(id, ordinal));
    }

    /**
     * 导出所选章节为 TXT（UTF-8）。body：{@code {"ordinals":[0,1,2]}}。
     */
    @PostMapping(value = "/{id}/chapters/export", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> exportChapters(@PathVariable String id,
                                                 @RequestBody(required = false) ExportChaptersRequest request) {
        if (request == null || request.ordinals() == null) {
            throw new IllegalArgumentException("请选择要导出的章节");
        }
        String body = novelService.exportChapters(id, request.ordinals());
        String filename = novelService.exportFileName(id, request.ordinals());
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"chapters.txt\"; filename*=UTF-8''" + encoded)
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .body(body);
    }

    @GetMapping("/{id}/breakpoint")
    public BreakpointInfo breakpoint(@PathVariable String id) {
        return breakpointAnalyzer.analyze(novelService.get(id));
    }

    /**
     * P6：保存续写草稿为正式章节。只入 Canon，不触发 Memory 提取。
     */
    @PostMapping("/{id}/chapters")
    @ResponseStatus(HttpStatus.CREATED)
    public ChapterCreatedDto appendChapter(@PathVariable String id,
                                           @RequestBody(required = false) AppendChapterRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        Chapter appended = novelService.appendChapter(id, request.title(), request.content()).lastChapter();
        return ChapterCreatedDto.from(appended);
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

    public record AppendChapterRequest(String title, String content) {
    }

    public record ExportChaptersRequest(List<Integer> ordinals) {
    }

    public record ChapterCreatedDto(int ordinal, Integer chapterNo, String title, int charCount) {
        static ChapterCreatedDto from(Chapter chapter) {
            return new ChapterCreatedDto(chapter.ordinal(), chapter.chapterNo(), chapter.title(),
                    chapter.charCount());
        }
    }
}
