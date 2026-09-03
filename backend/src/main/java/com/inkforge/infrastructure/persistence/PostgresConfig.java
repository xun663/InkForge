package com.inkforge.infrastructure.persistence;

import com.inkforge.provider.EmbeddingProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.support.PersistenceAnnotationBeanPostProcessor;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Explicit PostgreSQL/JPA/Flyway wiring for the "postgres" profile.
 * Deliberately does NOT rely on Spring Boot auto-configuration — SB4 modularized
 * its JDBC/JPA auto-config, and the default profile excludes it entirely so the
 * database-free (InMemory + Mock) path stays untouched.
 */
@Configuration
@Profile("postgres")
public class PostgresConfig {

    @Bean
    DataSource dataSource(@Value("${spring.datasource.url}") String url,
                          @Value("${spring.datasource.username}") String username,
                          @Value("${spring.datasource.password}") String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(5);
        return new HikariDataSource(config);
    }

    @Bean
    LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
        LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
        emf.setDataSource(dataSource);
        emf.setPackagesToScan("com.inkforge.infrastructure.persistence.entity");
        emf.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        return emf;
    }

    @Bean
    PlatformTransactionManager transactionManager(jakarta.persistence.EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }

    @Bean
    org.springframework.jdbc.core.JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new org.springframework.jdbc.core.JdbcTemplate(dataSource);
    }

    /** Registers @PersistenceContext support (normally provided by the excluded JPA auto-config). */
    @Bean
    static PersistenceAnnotationBeanPostProcessor persistenceAnnotationBeanPostProcessor() {
        return new PersistenceAnnotationBeanPostProcessor();
    }

    @Bean(initMethod = "migrate")
    Flyway flyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();
    }

    /**
     * Fails fast when the pgvector column dimension and inkforge.embedding.dimension diverge —
     * better a clear startup error than a broken VectorRetriever later (P3-C).
     */
    @Bean
    ApplicationRunner embeddingDimensionGuard(DataSource dataSource, EmbeddingProperties properties) {
        return args -> {
            String sql = """
                    SELECT a.atttypmod - 4 FROM pg_attribute a
                    JOIN pg_class c ON a.attrelid = c.oid
                    JOIN pg_namespace n ON c.relnamespace = n.oid
                    WHERE n.nspname = 'public' AND c.relname = 'memory_chunk' AND a.attname = 'embedding'
                    """;
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement ps = connection.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException(
                            "memory_chunk.embedding 列不存在：Flyway V2 迁移未执行？");
                }
                int dbDimension = rs.getInt(1);
                if (dbDimension != properties.dimension()) {
                    throw new IllegalStateException(
                            "配置维度与数据库不一致：inkforge.embedding.dimension=" + properties.dimension()
                                    + "，数据库 memory_chunk.embedding 维度=" + dbDimension
                                    + "。请同步配置或重建迁移。");
                }
            }
        };
    }
}
