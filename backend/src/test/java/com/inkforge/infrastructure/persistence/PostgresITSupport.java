package com.inkforge.infrastructure.persistence;

import com.inkforge.chapter.Chapter;
import com.inkforge.novel.Novel;
import com.inkforge.novel.NovelRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

/**
 * Testcontainers wiring for postgres-profile ITs.
 *
 * <p>{@code @ServiceConnection} only feeds Spring Boot JDBC auto-config. InkForge
 * excludes that auto-config and builds a Hikari {@code DataSource} from
 * {@code spring.datasource.*}, so the container JDBC URL must be registered as
 * properties or tests silently talk to {@code localhost:5432}.
 */
final class PostgresITSupport {

    private static final DockerImageName IMAGE =
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres");

    private PostgresITSupport() {
    }

    static PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>(IMAGE);
    }

    static void registerDatasource(PostgreSQLContainer<?> postgres, DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    static void saveNovel(NovelRepository novels, String id) {
        if (novels.findById(id).isEmpty()) {
            novels.save(new Novel(id, "IT 小说", "it.txt", List.of(
                    new Chapter(0, 1, "第一章", "正文。"),
                    new Chapter(1, 2, "第二章", "正文。"))));
        }
    }
}
