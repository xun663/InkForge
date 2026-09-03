package com.inkforge.memory.web;

import com.inkforge.memory.build.MemoryBuildJob;
import com.inkforge.memory.build.MemoryBuildService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * P5-A Full Memory Build API：启动/查询/暂停/恢复/取消/重试失败。
 * 进度轮询用 GET（jobId + status + counts + failedOrdinals），不加复杂推送。
 */
@RestController
@RequestMapping("/api/novels/{novelId}/memory/build")
public class MemoryBuildController {

    private final MemoryBuildService buildService;

    public MemoryBuildController(MemoryBuildService buildService) {
        this.buildService = buildService;
    }

    /** 启动（或重启）全量记忆构建。返回 RUNNING Job。 */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MemoryBuildJobDto start(@PathVariable String novelId) {
        return MemoryBuildJobDto.from(buildService.start(novelId));
    }

    /** 查询当前（最近一次）Job——前端轮询用。 */
    @GetMapping
    public MemoryBuildJobDto current(@PathVariable String novelId) {
        return buildService.current(novelId)
                .map(MemoryBuildJobDto::from)
                .orElse(null);
    }

    @GetMapping("/{jobId}")
    public MemoryBuildJobDto get(@PathVariable String novelId, @PathVariable String jobId) {
        return MemoryBuildJobDto.from(buildService.get(jobId));
    }

    @PostMapping("/{jobId}/pause")
    public MemoryBuildJobDto pause(@PathVariable String novelId, @PathVariable String jobId) {
        return MemoryBuildJobDto.from(buildService.pause(jobId));
    }

    @PostMapping("/{jobId}/resume")
    public MemoryBuildJobDto resume(@PathVariable String novelId, @PathVariable String jobId) {
        return MemoryBuildJobDto.from(buildService.resume(jobId));
    }

    @PostMapping("/{jobId}/cancel")
    public MemoryBuildJobDto cancel(@PathVariable String novelId, @PathVariable String jobId) {
        return MemoryBuildJobDto.from(buildService.cancel(jobId));
    }

    @PostMapping("/{jobId}/retry-failed")
    public MemoryBuildJobDto retryFailed(@PathVariable String novelId, @PathVariable String jobId) {
        return MemoryBuildJobDto.from(buildService.retryFailed(jobId));
    }

    /** 安全视图：不暴露内部细节，失败序数单独列出。 */
    public record MemoryBuildJobDto(String jobId, String novelId, String status,
                                    int totalChapters, int successChapters, int failedChapters,
                                    int currentOrdinal, double progress, List<Integer> failedOrdinals) {
        static MemoryBuildJobDto from(MemoryBuildJob job) {
            return new MemoryBuildJobDto(job.jobId(), job.novelId(), job.status().name(),
                    job.totalChapters(), job.successChapters(), job.failedChapters(),
                    job.currentOrdinal(), job.progress(), job.failedOrdinals());
        }
    }
}
