package com.inkforge.novel;

import com.inkforge.chapter.Chapter;
import com.inkforge.chapter.TxtNovelParser;
import com.inkforge.common.NotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;
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

    public List<Novel> list() {
        return novelRepository.findAll();
    }
}
