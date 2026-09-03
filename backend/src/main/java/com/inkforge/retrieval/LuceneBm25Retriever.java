package com.inkforge.retrieval;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.queryparser.classic.QueryParserBase;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real BM25 over Chinese text via Lucene + SmartChineseAnalyzer.
 *
 * <p>Design: "database is the source of truth, Lucene is a rebuildable cache".
 * <ul>
 *   <li>one in-memory index PER NOVEL (ByteBuffersDirectory) — cross-novel leakage impossible</li>
 *   <li>lazy build on first retrieve; rebuilt deterministically from MemoryChunkRepository</li>
 *   <li>staleness detected via the chunk repository's monotonic revision — no explicit
 *       invalidation calls needed anywhere</li>
 *   <li>restart loses indexes; they rebuild on demand — no Docker, no extra services</li>
 * </ul>
 * Default BM25 k1/b — no manual tuning without benchmark data.
 */
@Component
public class LuceneBm25Retriever implements MemoryRetriever {

    private static final String CONTENT_FIELD = "content";

    private final MemoryChunkRepository chunkRepository;
    private final Analyzer analyzer = new SmartChineseAnalyzer();
    private final ConcurrentHashMap<String, CachedIndex> cache = new ConcurrentHashMap<>();

    public LuceneBm25Retriever(MemoryChunkRepository chunkRepository) {
        this.chunkRepository = chunkRepository;
    }

    @Override
    public List<RetrievalResult> retrieve(String novelId, String query, int topK) {
        if (novelId == null || query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        IndexSearcher searcher = searcherFor(novelId);
        if (searcher == null) {
            return List.of(); // empty index
        }
        try {
            QueryParser parser = new QueryParser(CONTENT_FIELD, analyzer);
            Query parsed = parser.parse(QueryParserBase.escape(query.trim()));
            TopDocs topDocs = searcher.search(parsed, topK);
            List<RetrievalResult> results = new ArrayList<>();
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = searcher.storedFields().document(scoreDoc.doc);
                results.add(new RetrievalResult(
                        doc.get("chunkId"), novelId,
                        Integer.parseInt(doc.get("chapterOrdinal")),
                        MemoryChunkType.valueOf(doc.get("memoryType")),
                        doc.get("sourceId"), doc.get("text"), scoreDoc.score));
            }
            return results;
        } catch (Exception e) {
            // unparseable query → no results; retrieval must never blow up its caller
            return List.of();
        }
    }

    /** Returns the current index for the novel, rebuilding it when the chunk revision changed. */
    private IndexSearcher searcherFor(String novelId) {
        long currentRevision = chunkRepository.revision(novelId);
        CachedIndex cached = cache.get(novelId);
        if (cached != null && cached.revision() == currentRevision) {
            return cached.searcher();
        }
        List<MemoryChunk> chunks = chunkRepository.findByNovelId(novelId);
        if (chunks.isEmpty()) {
            cache.remove(novelId);
            return null;
        }
        IndexSearcher built = buildIndex(chunks);
        cache.put(novelId, new CachedIndex(currentRevision, built));
        return built;
    }

    private IndexSearcher buildIndex(List<MemoryChunk> chunks) {
        try {
            Directory directory = new ByteBuffersDirectory();
            try (IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
                for (MemoryChunk chunk : chunks) {
                    Document doc = new Document();
                    doc.add(new StringField("chunkId", chunk.id(), org.apache.lucene.document.Field.Store.YES));
                    doc.add(new StringField("novelId", chunk.novelId(), org.apache.lucene.document.Field.Store.YES));
                    doc.add(new StoredField("chapterOrdinal", chunk.chapterOrdinal()));
                    doc.add(new StoredField("memoryType", chunk.memoryType().name()));
                    doc.add(new StoredField("sourceId", chunk.sourceId()));
                    doc.add(new StoredField("text", chunk.text()));
                    doc.add(new TextField(CONTENT_FIELD, chunk.searchText(), org.apache.lucene.document.Field.Store.NO));
                    writer.addDocument(doc);
                }
            }
            IndexSearcher searcher = new IndexSearcher(DirectoryReader.open(directory));
            searcher.setSimilarity(new BM25Similarity()); // default k1/b — tune only with benchmark data
            return searcher;
        } catch (IOException e) {
            throw new IllegalStateException("Lucene 索引构建失败: novelId 分块数=" + chunks.size(), e);
        }
    }

    private record CachedIndex(long revision, IndexSearcher searcher) {
    }
}
