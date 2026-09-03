package com.inkforge.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * P5-A 异步调度：单线程 executor 保证同一时刻只跑一个 Memory Build Job，
 * 章节严格串行（MemoryUpdateService 的 CURRENT/SUPERSEDED 时序语义要求顺序）。
 */
@Configuration
@EnableAsync
public class MemoryBuildAsyncConfig {

    @Bean(name = "memoryBuildExecutor")
    public Executor memoryBuildExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(4);
        executor.setThreadNamePrefix("memory-build-");
        executor.initialize();
        return executor;
    }
}
