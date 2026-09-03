package com.inkforge.retrieval;

import com.inkforge.infrastructure.persistence.PostgresVectorRetriever;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Named retrieval beans so HybridRetrievalService never depends on a concrete Vector
 * implementation:
 * <ul>
 *   <li>{@code bm25Retriever} → Lucene BM25 (always)</li>
 *   <li>{@code vectorRetriever} → InMemory (default profile) or pgvector (postgres profile)</li>
 * </ul>
 * Profile switching is invisible above this configuration.
 */
@Configuration
public class RetrievalBeansConfig {

    @Bean
    MemoryRetriever bm25Retriever(LuceneBm25Retriever luceneBm25Retriever) {
        return luceneBm25Retriever;
    }

    @Bean("vectorRetriever")
    @Profile("!postgres")
    MemoryRetriever inMemoryVectorRetrieverBean(InMemoryVectorRetriever inMemoryVectorRetriever) {
        return inMemoryVectorRetriever;
    }

    @Bean("vectorRetriever")
    @Profile("postgres")
    MemoryRetriever postgresVectorRetrieverBean(PostgresVectorRetriever postgresVectorRetriever) {
        return postgresVectorRetriever;
    }
}
