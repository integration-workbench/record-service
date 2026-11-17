package io.github.huck5.recordservice.config;

import io.github.huck5.recordservice.contact.batch.ContactImportTasklet;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class BatchConfig {
    @Bean
    public Job contactImportJob(
            JobRepository jobRepository,
            Step contactImportStep) {

        return new JobBuilder("contactImportJob", jobRepository)
                .start(contactImportStep)
                .build();
    }

    @Bean
    public Step contactImportStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            ContactImportTasklet tasklet) {

        return new StepBuilder("contactImportStep", jobRepository)
                .tasklet(tasklet, transactionManager)
                .build();
    }
}
