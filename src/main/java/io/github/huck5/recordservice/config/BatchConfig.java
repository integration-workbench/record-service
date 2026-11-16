package io.github.huck5.recordservice.config;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class BatchConfig {

    @Bean
    public Job job(JobRepository jobRepository, Step batchCheckStep) {
        return new JobBuilder("batchCheckJob", jobRepository).start(batchCheckStep).build();
    }

    @Bean
    public Step batchCheckStep(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
        return new StepBuilder("batchCheckStep", jobRepository).tasklet((contribution, chunkContext) -> {
            ExecutionContext context =
                    contribution.getStepExecution().getExecutionContext();

            int processedCount = context.getInt("processedCount", 0);
            processedCount++;

            System.out.println("Processed contact " + processedCount);
            context.putInt("processedCount", processedCount);

            return processedCount < 5
                    ? RepeatStatus.CONTINUABLE
                    : RepeatStatus.FINISHED;
        }, transactionManager).build();
    }
}
