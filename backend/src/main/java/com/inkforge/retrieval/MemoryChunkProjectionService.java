package com.inkforge.retrieval;

import com.inkforge.memory.ChapterSummary;
import com.inkforge.memory.Character;
import com.inkforge.memory.CharacterFact;
import com.inkforge.memory.FactStatus;
import com.inkforge.memory.StoryEvent;
import com.inkforge.memory.StoryMemoryRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Deterministic, idempotent projection of P2 Story Memory into retrievable chunks.
 *
 * <p>Rules:
 * <ul>
 *   <li>ChapterSummary → SUMMARY chunk</li>
 *   <li>SUPERSEDED / UNCERTAIN CharacterFact → FACT chunk</li>
 *   <li>CURRENT CharacterFact → NEVER projected (current state is queried directly,
 *       never inferred through retrieval)</li>
 *   <li>StoryEvent → EVENT chunk</li>
 * </ul>
 * Chunk ids are deterministic ({@code TYPE:sourceId}), and the repository replaces
 * per-chapter projections atomically, so re-projection never duplicates chunks.
 * This service only READS Story Memory — projection can never corrupt it.
 */
@Service
public class MemoryChunkProjectionService {

    private final StoryMemoryRepository memoryRepository;
    private final MemoryChunkRepository chunkRepository;

    public MemoryChunkProjectionService(StoryMemoryRepository memoryRepository,
                                        MemoryChunkRepository chunkRepository) {
        this.memoryRepository = memoryRepository;
        this.chunkRepository = chunkRepository;
    }

    /** Re-projects one chapter's memory. Idempotent: replaces the chapter's previous chunks. */
    public void projectChapter(String novelId, int chapterOrdinal) {
        Instant now = Instant.now();
        List<MemoryChunk> chunks = new ArrayList<>();

        memoryRepository.findSummary(novelId, chapterOrdinal)
                .ifPresent(summary -> chunks.add(summaryChunk(summary, now)));

        for (Character character : memoryRepository.findCharacters(novelId)) {
            for (CharacterFact fact : memoryRepository.findFacts(character.id())) {
                if (fact.sourceChapter() == chapterOrdinal && fact.status() != FactStatus.CURRENT) {
                    chunks.add(factChunk(character, fact, now));
                }
            }
        }

        for (StoryEvent event : memoryRepository.findEvents(novelId, Integer.MAX_VALUE, false)) {
            if (event.chapterOrdinal() == chapterOrdinal) {
                chunks.add(eventChunk(event, now));
            }
        }

        chunkRepository.replaceForChapter(novelId, chapterOrdinal, chunks);
    }

    private static MemoryChunk summaryChunk(ChapterSummary summary, Instant now) {
        StringJoiner joiner = new StringJoiner("\n");
        joiner.add("第" + (summary.chapterOrdinal() + 1) + "章摘要：" + summary.summary());
        if (!summary.keyEvents().isEmpty()) {
            joiner.add("关键事件：" + String.join("；", summary.keyEvents()));
        }
        if (!summary.unresolvedThreads().isEmpty()) {
            joiner.add("未解决线索：" + String.join("；", summary.unresolvedThreads()));
        }
        String text = joiner.toString();

        StringJoiner search = new StringJoiner(" ");
        search.add(text);
        summary.characters().forEach(c -> search.add(c.name()));
        search.add(String.join(" ", summary.locations()));
        search.add(String.join(" ", summary.importantItems()));
        return new MemoryChunk(
                "SUMMARY:" + summary.novelId() + ":" + summary.chapterOrdinal(),
                summary.novelId(), MemoryChunkType.SUMMARY,
                summary.novelId() + ":" + summary.chapterOrdinal(),
                summary.chapterOrdinal(), text, search.toString(), now);
    }

    private static MemoryChunk factChunk(Character character, CharacterFact fact, Instant now) {
        String stateWord = fact.status() == FactStatus.SUPERSEDED ? "历史状态" : "传闻";
        String valueText = fact.targetCharacter() != null
                ? "与" + fact.targetCharacter() + "的关系=" + fact.value()
                : fact.attribute() + "=" + fact.value();
        String text = "「" + character.name() + "」" + stateWord + "："
                + valueText + "（第" + (fact.sourceChapter() + 1) + "章）";
        return new MemoryChunk(
                "FACT:" + fact.id(),
                character.novelId(), MemoryChunkType.FACT, fact.id(),
                fact.sourceChapter(), text, text, now);
    }

    private static MemoryChunk eventChunk(StoryEvent event, Instant now) {
        StringJoiner joiner = new StringJoiner("；");
        joiner.add("第" + (event.chapterOrdinal() + 1) + "章事件「" + event.title() + "」：" + event.description());
        if (!event.participants().isEmpty()) {
            joiner.add("参与者：" + String.join("、", event.participants()));
        }
        if (event.location() != null && !event.location().isBlank()) {
            joiner.add("地点：" + event.location());
        }
        if (!event.consequences().isEmpty()) {
            joiner.add("结果：" + String.join("、", event.consequences()));
        }
        String text = joiner.toString();
        return new MemoryChunk(
                "EVENT:" + event.id(),
                event.novelId(), MemoryChunkType.EVENT, event.id(),
                event.chapterOrdinal(), text, text, now);
    }
}
