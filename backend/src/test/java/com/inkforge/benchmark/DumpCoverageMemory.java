package com.inkforge.benchmark;

import com.inkforge.chapter.Chapter;
import com.inkforge.chapter.ChapterSplitter;
import com.inkforge.chapter.CharsetDetector;
import com.inkforge.chapter.ParsedNovel;
import com.inkforge.chapter.TxtNovelParser;
import com.inkforge.common.JtokkitTokenCounter;
import com.inkforge.common.TokenCounter;
import com.inkforge.common.prompt.ClasspathPromptCatalog;
import com.inkforge.common.prompt.PromptCatalog;
import com.inkforge.memory.Character;
import com.inkforge.memory.CharacterFact;
import com.inkforge.memory.ChapterSummary;
import com.inkforge.memory.FactStatus;
import com.inkforge.memory.InMemoryStoryMemoryRepository;
import com.inkforge.memory.MemoryUpdateService;
import com.inkforge.memory.StoryEvent;
import com.inkforge.memory.StoryMemoryRepository;
import com.inkforge.memory.extraction.ExtractionValidator;
import com.inkforge.memory.extraction.MemoryExtractionProperties;
import com.inkforge.memory.extraction.MemoryExtractor;
import com.inkforge.novel.Novel;
import com.inkforge.provider.LlmProperties;
import com.inkforge.provider.LlmProvider;
import com.inkforge.provider.OpenAiCompatibleLlmProvider;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * P5-0.5 前置：dump coverage=48（全 48 章）的实际记忆内容（人物/事实/事件/摘要 + sourceChapter），
 * 用于设计带真实 Gold 的体系定向 Query。只读生产类，不修改任何代码。
 */
class DumpCoverageMemory {

    @Test
    void dump() throws Exception {
        String apiKey = System.getenv("INKFORGE_LLM_API_KEY");
        if (apiKey == null || apiKey.isBlank()) apiKey = System.getenv("LLM_API_KEY");
        String model = System.getenv().getOrDefault("INKFORGE_LLM_MODEL", "deepseek-v4-flash");
        String baseUrl = System.getenv().getOrDefault("INKFORGE_LLM_BASE_URL", "https://api.deepseek.com");

        ObjectMapper mapper = new ObjectMapper();
        TokenCounter tokenCounter = new JtokkitTokenCounter();
        PromptCatalog catalog = new ClasspathPromptCatalog();
        MemoryExtractionProperties props =
                new MemoryExtractionProperties(3, 12000, 2048, 4096, 0.2, 2, 0.7, 300, 200);

        LlmProvider deepseek = new OpenAiCompatibleLlmProvider("deepseek",
                WebClient.builder().baseUrl(baseUrl).build(),
                new LlmProperties("deepseek", baseUrl, apiKey, model, 300, new LlmProperties.Mock(0)), mapper);

        ParsedNovel parsed = new TxtNovelParser(new CharsetDetector(), new ChapterSplitter()).parse(
                Files.readAllBytes(Path.of("C:/Users/xun/AppData/Local/Temp/inkforge-e2e/zetian_ch1-48.txt")),
                "zetian_ch1-48.txt");
        List<Chapter> chapters = parsed.chapters();
        if (!chapters.isEmpty() && chapters.get(0).chapterNo() == null) chapters = chapters.subList(1, chapters.size());
        Novel novel = new Novel("zetian-48", parsed.title(), "zetian_ch1-48.txt", chapters);

        StoryMemoryRepository memRepo = new InMemoryStoryMemoryRepository();
        MemoryExtractor extractor = new MemoryExtractor(deepseek, catalog, tokenCounter,
                new ExtractionValidator(), props, mapper);
        MemoryUpdateService update = new MemoryUpdateService(memRepo, props);

        int ok = 0;
        for (Chapter ch : chapters) {
            var outcome = extractor.extract(ch, ch.chapterNo() != null ? "第" + ch.chapterNo() + "章" : ch.title());
            if (outcome.result() != null) { update.apply(novel.id(), ch, outcome.result()); ok++; }
            else System.out.println("[dump FAILED] " + ch.title());
        }
        System.out.println("coverage=48 记忆构建完成: " + ok + "/" + chapters.size());

        StringBuilder sb = new StringBuilder();
        sb.append("# 遮天 1-48 章记忆 dump（coverage=48）\n\n");

        sb.append("## 人物\n");
        List<Character> chars = new ArrayList<>(memRepo.findCharacters(novel.id()));
        chars.sort(Comparator.comparingInt(c -> c.firstChapter()));
        for (Character c : chars) {
            sb.append("### ").append(c.name()).append(" (首见第").append(c.firstChapter() + 1).append("章, 状态")
                    .append(c.status()).append(")\n");
            for (CharacterFact f : memRepo.findFacts(c.id())) {
                String life = f.status() == FactStatus.CURRENT ? "当前" : f.status() == FactStatus.SUPERSEDED ? "历史" : "传闻";
                sb.append("- [").append(life).append(" 第").append(f.sourceChapter() + 1).append("章] ")
                        .append(f.category()).append(" ").append(f.attribute()).append("=")
                        .append(f.value())
                        .append(f.targetCharacter() != null ? " →" + f.targetCharacter() : "")
                        .append("\n");
            }
        }

        sb.append("\n## 事件\n");
        List<StoryEvent> events = new ArrayList<>(memRepo.findEvents(novel.id(), Integer.MAX_VALUE, false));
        events.sort(Comparator.comparingInt(StoryEvent::chapterOrdinal));
        for (StoryEvent e : events) {
            sb.append("- 第").append(e.chapterOrdinal() + 1).append("章 「").append(e.title()).append("」 ")
                    .append(e.description()).append("\n");
        }

        sb.append("\n## 摘要\n");
        for (int i = 0; i < chapters.size(); i++) {
            memRepo.findSummary(novel.id(), i).ifPresent(s ->
                    sb.append("- 第").append(s.chapterOrdinal() + 1).append("章: ").append(s.summary()).append("\n"));
        }

        Path out = Path.of("target/e2e/memory-coverage-directed");
        Files.createDirectories(out);
        Files.writeString(out.resolve("memory-48-dump.md"), sb.toString());
        System.out.println("dump 已写: " + out.resolve("memory-48-dump.md"));
    }
}
