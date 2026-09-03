package com.inkforge.benchmark;

import com.inkforge.chapter.Chapter;
import com.inkforge.chapter.ChapterSplitter;
import com.inkforge.chapter.CharsetDetector;
import com.inkforge.chapter.TxtNovelParser;
import com.inkforge.memory.Character;
import com.inkforge.memory.CharacterFact;
import com.inkforge.memory.CharacterStatus;
import com.inkforge.memory.ChapterSummary;
import com.inkforge.memory.FactCategory;
import com.inkforge.memory.FactStatus;
import com.inkforge.memory.StoryEvent;
import com.inkforge.memory.StoryMemoryRepository;
import com.inkforge.memory.SummaryCharacter;
import com.inkforge.novel.Novel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/**
 * Benchmark 数据装配：从 fixtures/benchmark_novel.txt 解析 12 章小说，并 seed 与章节
 * 一一对应的结构化 Story Memory（摘要/事件/历史事实）。
 *
 * <p>为什么手工 seed 而不是走 Mock 提取：MockLlmProvider 的提取内容对所有章节相同
 * （固定"林默与血魔后山对峙"模板），检索将无区分度。Benchmark 评估的是<b>检索与排序</b>
 * 而非提取，故 memory 由标注作者构造（内容与小说真实对应），检索链路全部使用生产代码。
 */
public final class BenchmarkNovelData {

    public static final String NOVEL_ID = "benchmark-novel";
    public static final int CHAPTER_COUNT = 12;

    private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");

    private BenchmarkNovelData() {
    }

    public static Novel loadNovel() {
        byte[] bytes;
        try (InputStream in = BenchmarkNovelData.class
                .getResourceAsStream("/fixtures/benchmark_novel.txt")) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("benchmark fixture 加载失败", e);
        }
        String text = new CharsetDetector().decode(bytes);
        List<Chapter> chapters = new ChapterSplitter().split(text);
        if (chapters.size() != CHAPTER_COUNT) {
            throw new IllegalStateException("benchmark fixture 章节数异常: " + chapters.size());
        }
        return new Novel(NOVEL_ID, "蛊真人生测试集", "benchmark_novel.txt", chapters);
    }

    /** Seed 与 12 章一一对应的结构化记忆（每章 1 summary + 1 event + 境界/关系历史事实）。 */
    public static void seedMemory(StoryMemoryRepository memory) {
        seed(memory, 0, "方源初至青茅山，与白凝冰相遇。", List.of("青茅山", "白凝冰"),
                List.of("白凝冰来意未明"));
        seed(memory, 1, "方源在青茅山深处以精血炼制青壳蛊虫，认主成功，白凝冰旁观。",
                List.of("青茅山", "蛊虫"), List.of());
        seed(memory, 2, "方源持玉牌进入狐仙福地，取走九叶灵芝并拓下残缺功法。",
                List.of("狐仙福地"), List.of("残缺功法来源不明"));
        seed(memory, 3, "方源与白凝冰在小镇结盟，约定五五分成共同进退。",
                List.of("白凝冰"), List.of());
        seed(memory, 4, "血手魔尊夜袭小镇，方源与白凝冰借矿洞水道逃脱。",
                List.of("血手魔尊", "白凝冰"), List.of("血手魔尊是否还会追杀"));
        seed(memory, 5, "方源独自北上，在北原冰原苦修半年，修为达四转。",
                List.of("北原"), List.of());
        seed(memory, 6, "方源于北原极夜冰窟突破五转。", List.of("北原"), List.of());
        seed(memory, 7, "方源潜入天机阁购得血手魔尊动向情报，其目标直指青茅山。",
                List.of("天机阁", "血手魔尊"), List.of("血手魔尊集结人马"));
        seed(memory, 8, "方源与白凝冰在玄冰洞对峙，白凝冰欲退出结盟。",
                List.of("玄冰洞", "白凝冰"), List.of("结盟是否破裂"));
        seed(memory, 9, "血手魔尊围困青茅山，方源以蛊虫大阵击退，重伤取胜。",
                List.of("青茅山", "血手魔尊"), List.of());
        seed(memory, 10, "养伤半年后方源于北原冰窟闭关四十九日，突破六转。",
                List.of("北原"), List.of());
        seed(memory, 11, "方源与白凝冰大婚，拜堂时白凝冰刺杀方源，伏杀败露。",
                List.of("白凝冰", "血手魔尊"), List.of("白凝冰背后的交易"));
    }

    private static void seed(StoryMemoryRepository memory, int ordinal, String summaryText,
                             List<String> characters, List<String> threads) {
        List<SummaryCharacter> summaryCharacters = characters.stream()
                .map(name -> new SummaryCharacter(name, "配角"))
                .toList();
        memory.saveSummary(new ChapterSummary(NOVEL_ID, ordinal, summaryText,
                List.of("关键事件-" + ordinal), summaryCharacters, List.of(), List.of(),
                threads, NOW));

        String eventTitle = switch (ordinal) {
            case 0 -> "青茅山初遇";
            case 1 -> "蛊虫认主";
            case 2 -> "狐仙福地机缘";
            case 3 -> "结盟";
            case 4 -> "矿洞逃生";
            case 5 -> "北原苦修";
            case 6 -> "五转突破";
            case 7 -> "天机阁情报";
            case 8 -> "玄冰洞对峙";
            case 9 -> "青茅山大战";
            case 10 -> "六转突破";
            default -> "大婚伏杀";
        };
        memory.saveEvent(new StoryEvent("benchmark-event-" + ordinal, NOVEL_ID, ordinal,
                eventTitle, summaryText, characters, characters.isEmpty() ? null : characters.getFirst(),
                List.of(), 4, "引用-" + ordinal, NOW));
    }

    /** 境界历史与关系事实（SUPERSEDED/UNCERTAIN → 投影为 FACT chunk）。 */
    public static void seedFacts(StoryMemoryRepository memory) {
        memory.saveCharacter(new Character("benchmark-fangyuan", NOVEL_ID, "方源",
                List.of(), 0, 11, CharacterStatus.ACTIVE, NOW, NOW));
        memory.saveCharacter(new Character("benchmark-bai", NOVEL_ID, "白凝冰",
                List.of(), 0, 11, CharacterStatus.ACTIVE, NOW, NOW));
        memory.saveCharacter(new Character("benchmark-xue", NOVEL_ID, "血手魔尊",
                List.of(), 4, 11, CharacterStatus.ACTIVE, NOW, NOW));

        fact(memory, "f-realm-3", "benchmark-fangyuan", "境界", "三转", 0, 5);
        fact(memory, "f-realm-4", "benchmark-fangyuan", "境界", "四转", 5, 6);
        fact(memory, "f-realm-5", "benchmark-fangyuan", "境界", "五转", 6, 10);
        fact(memory, "f-realm-6", "benchmark-fangyuan", "境界", "六转", 10, null);
        // 关系历史：合作（SUPERSEDED）→ 敌对（UNCERTAIN 传闻）
        memory.saveFact(new CharacterFact("f-rel-coop", "benchmark-fangyuan", FactCategory.RELATIONSHIP,
                "关系", "合作", "白凝冰", FactStatus.SUPERSEDED, 3, 8, 0.9,
                3, "我们结盟。", NOW, NOW));
        memory.saveFact(new CharacterFact("f-rel-hostile", "benchmark-fangyuan", FactCategory.RELATIONSHIP,
                "关系", "敌对", "白凝冰", FactStatus.UNCERTAIN, 8, null, 0.5,
                8, "她不想被拖下水。", NOW, NOW));
    }

    private static void fact(StoryMemoryRepository memory, String id, String characterId,
                             String attribute, String value, int from, Integer until) {
        memory.saveFact(new CharacterFact(id, characterId, FactCategory.ABILITY, attribute, value,
                null, FactStatus.SUPERSEDED, from, until, 0.9, from, "引用-" + from, NOW, NOW));
    }
}
