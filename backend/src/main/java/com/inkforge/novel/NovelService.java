package com.inkforge.novel;

import com.inkforge.chapter.Chapter;
import com.inkforge.chapter.TxtNovelParser;
import com.inkforge.common.NotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Novel lifecycle: ingest (resource checks + parse + store) and query. */
@Service
public class NovelService {

    private final NovelRepository novelRepository;
    private final TxtNovelParser txtNovelParser;
    private final ImportProperties importProperties;

    public NovelService(NovelRepository novelRepository, TxtNovelParser txtNovelParser,
                        ImportProperties importProperties) {
        this.novelRepository = novelRepository;
        this.txtNovelParser = txtNovelParser;
        this.importProperties = importProperties;
    }

    public Novel ingest(byte[] bytes, String fileName) {
        if (bytes.length > importProperties.maxFileSize()) {
            throw new IllegalArgumentException(
                    "文件过大：" + (bytes.length / 1024 / 1024) + "MB，最大支持 "
                            + (importProperties.maxFileSize() / 1024 / 1024) + "MB");
        }
        var parsed = txtNovelParser.parse(bytes, fileName);
        if (parsed.chapters().size() > importProperties.maxChapters()) {
            throw new IllegalArgumentException(
                    "章节数超过资源保护上限：识别到 " + parsed.chapters().size()
                            + " 章，最大支持 " + importProperties.maxChapters() + " 章");
        }
        for (Chapter chapter : parsed.chapters()) {
            if (chapter.content().length() > importProperties.maxChapterChars()) {
                throw new IllegalArgumentException(
                        "章节过长：第 " + (chapter.ordinal() + 1) + " 章共 " + chapter.content().length()
                                + " 字，超过资源保护上限 " + importProperties.maxChapterChars() + " 字");
            }
        }
        Novel novel = new Novel(UUID.randomUUID().toString(), parsed.title(), fileName, parsed.chapters());
        return novelRepository.save(novel);
    }

    public Novel get(String id) {
        return novelRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("小说不存在: " + id));
    }

    /**
     * P6：把用户确认的续写草稿保存为正式章节（成为 Canon 的一部分）。
     * 只追加章节，绝不触发 Memory Extraction——提取仍只能由用户显式发起
     * （POST /memory/extract 或 /memory/build），且只作用于已保存的正式章节。
     *
     * <p>postgres profile 下走整本重写（O(chapters)），v1 接受：保存是低频操作。
     */
    public Novel appendChapter(String novelId, String title, String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("章节内容不能为空");
        }
        if (content.length() > importProperties.maxChapterChars()) {
            throw new IllegalArgumentException(
                    "章节过长：共 " + content.length() + " 字，超过资源保护上限 "
                            + importProperties.maxChapterChars() + " 字");
        }
        Novel novel = get(novelId);
        Chapter last = novel.lastChapter();
        int ordinal = last.ordinal() + 1;
        Integer chapterNo = last.chapterNo() == null ? null : last.chapterNo() + 1;
        String finalTitle = title == null || title.isBlank()
                ? "第" + (ordinal + 1) + "章" : title.trim();
        Chapter appended = new Chapter(ordinal, chapterNo, finalTitle, content);
        List<Chapter> chapters = new ArrayList<>(novel.chapters());
        chapters.add(appended);
        return novelRepository.save(new Novel(novel.id(), novel.title(), novel.sourceFileName(), chapters));
    }

    public List<Novel> list() {
        return novelRepository.findAll();
    }

    public Chapter getChapter(String novelId, int ordinal) {
        return get(novelId).chapters().stream()
                .filter(c -> c.ordinal() == ordinal)
                .findFirst()
                .orElseThrow(() -> new NotFoundException("章节不存在: " + ordinal));
    }

    /**
     * 按请求顺序导出所选章节正文（去重）。不调用 LLM。
     */
    public String exportChapters(String novelId, List<Integer> ordinals) {
        if (ordinals == null || ordinals.isEmpty()) {
            throw new IllegalArgumentException("请选择要导出的章节");
        }
        Novel novel = get(novelId);
        Map<Integer, Chapter> byOrdinal = new java.util.HashMap<>();
        for (Chapter chapter : novel.chapters()) {
            byOrdinal.put(chapter.ordinal(), chapter);
        }
        StringBuilder text = new StringBuilder();
        java.util.LinkedHashSet<Integer> unique = new java.util.LinkedHashSet<>(ordinals);
        for (Integer ordinal : unique) {
            Chapter chapter = byOrdinal.get(ordinal);
            if (chapter == null) {
                throw new NotFoundException("章节不存在: " + ordinal);
            }
            if (!text.isEmpty()) {
                text.append("\n\n");
            }
            text.append(heading(chapter)).append("\n\n").append(chapter.content());
        }
        return text.toString();
    }

    public String exportFileName(String novelId, List<Integer> ordinals) {
        Novel novel = get(novelId);
        String title = safeFilePart(novel.title());
        if (ordinals == null || ordinals.isEmpty()) {
            return title + "-章节.txt";
        }
        java.util.LinkedHashSet<Integer> unique = new java.util.LinkedHashSet<>(ordinals);
        List<Chapter> selected = unique.stream()
                .map(o -> novel.chapters().stream().filter(c -> c.ordinal() == o).findFirst().orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
        if (selected.isEmpty()) {
            return title + "-章节.txt";
        }
        if (selected.size() == 1) {
            return title + "-" + heading(selected.getFirst()) + ".txt";
        }
        return title + "-" + heading(selected.getFirst()) + "至" + heading(selected.getLast()) + ".txt";
    }

    private static String heading(Chapter chapter) {
        if (chapter.chapterNo() != null) {
            String title = chapter.title() == null ? "" : chapter.title().strip();
            if (title.isEmpty() || title.equals("第" + chapter.chapterNo() + "章")) {
                return "第" + chapter.chapterNo() + "章";
            }
            return "第" + chapter.chapterNo() + "章 " + title;
        }
        return chapter.title() == null || chapter.title().isBlank() ? "（无标题）" : chapter.title().strip();
    }

    private static String safeFilePart(String name) {
        if (name == null || name.isBlank()) {
            return "novel";
        }
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").strip();
    }
}
