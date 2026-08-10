package io.github.user32694.ledgerplatform.reconciliation.internal;

import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
class ReconciliationBatchLaunchConfiguration {
    @Bean
    ThreadPoolTaskExecutor reconciliationBatchTaskExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("reconciliation-batch-");
        return executor;
    }

    @Bean("reconciliationBatchJobLauncher")
    JobLauncher reconciliationBatchJobLauncher(
            JobRepository jobRepository, ThreadPoolTaskExecutor reconciliationBatchTaskExecutor)
            throws Exception {
        var launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(jobRepository);
        launcher.setTaskExecutor(reconciliationBatchTaskExecutor);
        launcher.afterPropertiesSet();
        return launcher;
    }
}
