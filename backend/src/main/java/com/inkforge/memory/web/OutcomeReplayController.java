package com.inkforge.memory.web;

import com.inkforge.memory.GzrImportProperties;
import com.inkforge.memory.OutcomeReplayService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.List;

/**
 * 本地实验入口：把蛊真人 outcomes 重放到运行中 App。零 LLM 成本（嵌入除外）。
 * 无鉴权，仅适合单用户本机。
 */
@RestController
@RequestMapping("/api/import")
public class OutcomeReplayController {

    private final OutcomeReplayService replayService;
    private final GzrImportProperties properties;

    public OutcomeReplayController(OutcomeReplayService replayService, GzrImportProperties properties) {
        this.replayService = replayService;
        this.properties = properties;
    }

    @PostMapping("/gzr-outcomes")
    @ResponseStatus(HttpStatus.CREATED)
    public ReplayResponse importGzr(@RequestBody(required = false) ImportRequest request) {
        ImportRequest body = request == null ? new ImportRequest(null, null, null, null) : request;
        String source = firstNonBlank(body.sourceTxt(), properties.sourceTxt());
        String outcomes = firstNonBlank(body.outcomesDir(), properties.outcomesDir());
        if (source == null || outcomes == null) {
            throw new IllegalArgumentException(
                    "需要 sourceTxt 与 outcomesDir（请求体或 inkforge.gzr.* / INKFORGE_GZR_SOURCE / INKFORGE_GZR_DIR）");
        }
        boolean embed = body.embed() == null || body.embed();
        var result = replayService.replay(Path.of(source), Path.of(outcomes), body.title(), embed);
        return ReplayResponse.from(result);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.strip();
        }
        if (b != null && !b.isBlank()) {
            return b.strip();
        }
        return null;
    }

    public record ImportRequest(String sourceTxt, String outcomesDir, String title, Boolean embed) {
    }

    public record ReplayResponse(String id, String title, String sourceFileName, int chapterCount,
                                 int replayed, int skipped, int embeddedChunks, String embedError,
                                 List<Integer> applyFailedOrdinals) {
        static ReplayResponse from(OutcomeReplayService.ReplayResult result) {
            return new ReplayResponse(result.novelId(), result.title(), result.sourceFileName(),
                    result.chapterCount(), result.replayed(), result.skipped(),
                    result.embeddedChunks(), result.embedError(), result.applyFailedOrdinals());
        }
    }
}
