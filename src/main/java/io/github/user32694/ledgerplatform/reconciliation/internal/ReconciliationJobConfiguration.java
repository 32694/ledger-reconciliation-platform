package io.github.user32694.ledgerplatform.reconciliation.internal;

import io.github.user32694.ledgerplatform.payments.PaymentsApi;
import java.util.UUID;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
class ReconciliationJobConfiguration {
    private static final int CHUNK_SIZE = 500;

    @Bean
    Job reconciliationJob(
            JobRepository jobRepository,
            Step prepareReconciliationStep,
            Step matchStatementEntriesStep,
            Step findInternalOnlyPaymentsStep,
            Step finalizeReconciliationStep,
            ReconciliationJobExecutionListener reconciliationJobExecutionListener) {
        return new JobBuilder("reconciliationJob", jobRepository)
                .start(prepareReconciliationStep)
                .next(matchStatementEntriesStep)
                .next(findInternalOnlyPaymentsStep)
                .next(finalizeReconciliationStep)
                .listener(reconciliationJobExecutionListener)
                .build();
    }

    @Bean
    Step prepareReconciliationStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            ReconciliationStore store,
            PaymentsApi paymentsApi) {
        return new StepBuilder("prepareReconciliationStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    UUID runId = runId(chunkContext.getStepContext().getJobParameters().get("runId"));
                    var batch = store.getBatchForRun(runId);
                    store.initializeRunTotal(runId, Math.toIntExact(
                            Math.min(Integer.MAX_VALUE, (long) batch.totalRows()
                                    + paymentsApi.countSucceededTopUps(batch.queryStart(), batch.queryEnd()))));
                    return org.springframework.batch.repeat.RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

    @Bean
    Step matchStatementEntriesStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            StatementMatchItemReader statementMatchItemReader,
            ItemProcessor<ReconciliationWorkItem, ReconciliationWorkResult> reconciliationWorkItemProcessor,
            ItemWriter<ReconciliationWorkResult> reconciliationWorkItemWriter,
            ReconciliationChunkProgressListener reconciliationChunkProgressListener) {
        return new StepBuilder("matchStatementEntriesStep", jobRepository)
                .<ReconciliationWorkItem, ReconciliationWorkResult>chunk(CHUNK_SIZE, transactionManager)
                .reader(statementMatchItemReader)
                .processor(reconciliationWorkItemProcessor)
                .writer(reconciliationWorkItemWriter)
                .faultTolerant()
                .retry(TransientDataAccessException.class)
                .retryLimit(3)
                .listener(reconciliationChunkProgressListener)
                .build();
    }

    @Bean
    Step findInternalOnlyPaymentsStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            InternalOnlyPaymentItemReader internalOnlyPaymentItemReader,
            ItemProcessor<ReconciliationWorkItem, ReconciliationWorkResult> reconciliationWorkItemProcessor,
            ItemWriter<ReconciliationWorkResult> reconciliationWorkItemWriter,
            ReconciliationChunkProgressListener reconciliationChunkProgressListener) {
        return new StepBuilder("findInternalOnlyPaymentsStep", jobRepository)
                .<ReconciliationWorkItem, ReconciliationWorkResult>chunk(CHUNK_SIZE, transactionManager)
                .reader(internalOnlyPaymentItemReader)
                .processor(reconciliationWorkItemProcessor)
                .writer(reconciliationWorkItemWriter)
                .faultTolerant()
                .retry(TransientDataAccessException.class)
                .retryLimit(3)
                .listener(reconciliationChunkProgressListener)
                .build();
    }

    @Bean
    Step finalizeReconciliationStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            ReconciliationStore store) {
        return new StepBuilder("finalizeReconciliationStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    store.promoteWorkResults(runId(chunkContext.getStepContext().getJobParameters().get("runId")));
                    return org.springframework.batch.repeat.RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

    @Bean
    @StepScope
    StatementMatchItemReader statementMatchItemReader(
            @Value("#{jobParameters['runId']}") String runId,
            ReconciliationStore store,
            ChannelStatementEntryRepository entryRepository,
            PaymentsApi paymentsApi) {
        return new StatementMatchItemReader(runId(runId), store, entryRepository, paymentsApi);
    }

    @Bean
    @StepScope
    InternalOnlyPaymentItemReader internalOnlyPaymentItemReader(
            @Value("#{jobParameters['runId']}") String runId,
            ReconciliationStore store,
            PaymentsApi paymentsApi) {
        return new InternalOnlyPaymentItemReader(runId(runId), store, paymentsApi);
    }

    @Bean
    @StepScope
    ReconciliationWorkItemProcessor reconciliationWorkItemProcessor(
            @Value("#{jobParameters['runId']}") String runId,
            ReconciliationStore store,
            ReconciliationRuleMatcher matcher) {
        return new ReconciliationWorkItemProcessor(
                matcher, store.getBatchForRun(runId(runId)).amountToleranceCents());
    }

    @Bean
    @StepScope
    ReconciliationWorkItemWriter reconciliationWorkItemWriter(
            @Value("#{jobParameters['runId']}") String runId,
            ReconciliationStore store) {
        UUID id = runId(runId);
        return new ReconciliationWorkItemWriter(id, store.getBatchForRun(id).id(), store);
    }

    private static UUID runId(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("runId is required");
        }
        return UUID.fromString(value.toString());
    }
}
