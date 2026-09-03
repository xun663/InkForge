package com.inkforge.planning;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * PlotThread 确定性 upsert（"LLM 建议，代码决定"，对应 Memory 层的 MemoryUpdateService 角色）。
 *
 * <p>合并规则（确定性，v1）：
 * <ul>
 *   <li>标题空白 → 跳过；按 {@link PlotThread#normalized(String)} 匹配已有线索</li>
 *   <li>新建：status=OPEN，firstSeen=分析给出的章节（钳制到 0..lastOrdinal，缺失则取 lastOrdinal），lastSeen=lastOrdinal</li>
 *   <li>已存在：summary 仅在本次非空白时更新；relatedCharacters 取并集；firstSeen 保留（旧的为 null 才采纳新的）；
 *       lastSeen 取 max；status 永不被规划结果降级或升级（RESOLVED/ABANDONED 的写入方是未来的显式收束能力）</li>
 * </ul>
 * 本类只写 PlotThread（规划层数据），绝不触碰 Story Memory。
 */
@Component
public class PlotThreadMerger {

    private final PlotThreadRepository repository;

    public PlotThreadMerger(PlotThreadRepository repository) {
        this.repository = repository;
    }

    /**
     * 将一次 ENDING 分析提炼的线索合并进仓储，返回本次落库的线索（按输入顺序）。
     *
     * @param lastOrdinal 当前小说最后一章的 ordinal（0 基），用于章节字段的确定性钳制
     */
    public List<PlotThread> merge(String novelId, List<EndingAnalysis.EndingThread> proposed, int lastOrdinal) {
        List<PlotThread> saved = new ArrayList<>();
        if (proposed == null) {
            return saved;
        }
        for (EndingAnalysis.EndingThread thread : proposed) {
            if (thread.title() == null || thread.title().isBlank()) {
                continue; // 单条无效：丢弃，不失败
            }
            saved.add(mergeOne(novelId, thread, lastOrdinal));
        }
        return saved;
    }

    private PlotThread mergeOne(String novelId, EndingAnalysis.EndingThread proposed, int lastOrdinal) {
        Instant now = Instant.now();
        String normalized = PlotThread.normalized(proposed.title());
        Optional<PlotThread> existing = repository.findByTitle(novelId, normalized);
        if (existing.isEmpty()) {
            PlotThread thread = new PlotThread(
                    UUID.randomUUID().toString(),
                    novelId,
                    proposed.title().trim(),
                    blankToNull(proposed.summary()),
                    PlotThreadStatus.OPEN,
                    clampChapter(proposed.firstSeenChapter(), lastOrdinal),
                    lastOrdinal,
                    proposed.relatedCharacters(),
                    now,
                    now);
            return repository.save(thread);
        }
        PlotThread old = existing.get();
        String summary = proposed.summary() != null && !proposed.summary().isBlank()
                ? proposed.summary().trim() : old.summary();
        Set<String> related = new LinkedHashSet<>(old.relatedCharacters());
        related.addAll(proposed.relatedCharacters());
        Integer firstSeen = old.firstSeenChapter() != null ? old.firstSeenChapter()
                : clampChapter(proposed.firstSeenChapter(), lastOrdinal);
        Integer lastSeen = old.lastSeenChapter() != null ? Math.max(old.lastSeenChapter(), lastOrdinal)
                : lastOrdinal;
        PlotThread merged = new PlotThread(
                old.id(), old.novelId(), old.title(), summary, old.status(),
                firstSeen, lastSeen, List.copyOf(related), old.createdAt(), now);
        return repository.save(merged);
    }

    /** 章节钳制：null 保持 null（分析未给出），否则钳到 0..lastOrdinal。 */
    private static Integer clampChapter(Integer chapter, int lastOrdinal) {
        if (chapter == null) {
            return lastOrdinal;
        }
        return Math.max(0, Math.min(chapter, lastOrdinal));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
