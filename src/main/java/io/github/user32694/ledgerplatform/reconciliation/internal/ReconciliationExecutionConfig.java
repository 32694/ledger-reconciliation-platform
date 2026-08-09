package io.github.user32694.ledgerplatform.reconciliation.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
class ReconciliationExecutionConfig {
    @Bean("reconciliationTaskExecutor")
    ThreadPoolTaskExecutor reconciliationTaskExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("reconciliation-");
        return executor;
    }
}
