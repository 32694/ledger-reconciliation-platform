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
/**
 * Spring Batch 作业定义：读取渠道记录和内部支付，按块匹配并写入结果。
 *
 * <p>chunk size 决定事务边界；每个已提交 chunk 会同步 Spring Batch checkpoint，
 * 失败后 restart 可以从最近一次成功提交处继续。
 */
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
        // 作业顺序很重要：先清理并初始化，再处理两个方向的差异，最后发布结果。
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
        // 准备步骤只做一次性初始化，不参与逐条匹配；总量用于页面进度展示。
        return new StepBuilder("prepareReconciliationStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    UUID runId = runId(chunkContext.getStepContext().getJobParameters().get("runId"));
                    store.clearWorkResults(runId);
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
        // 第一段 chunk 处理“渠道有记录”的方向：匹配、金额比较并写入中间表。
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
        // 第二段扫描成功支付，补齐“内部有记录但渠道无记录”的单边差异。
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
        // 只有前面所有 chunk 成功后才提升 work 结果，读者不会看到半成品批次。
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
