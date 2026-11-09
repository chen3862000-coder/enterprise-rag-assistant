package com.acme.rag.document.ingestion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Supplies a bounded executor dedicated to durable ingestion workers. */
@Configuration
public class IngestionWorkerConfiguration {
    @Bean("ingestionWorkerExecutor")
    TaskExecutor ingestionWorkerExecutor(
            @Value("${app.ingestion.worker-concurrency:2}") int concurrency) {
        if (concurrency < 1 || concurrency > 16) {
            throw new IllegalArgumentException("Ingestion worker concurrency must be between 1 and 16");
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("document-ingestion-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
