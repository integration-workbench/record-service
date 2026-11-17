package io.github.huck5.recordservice.contact.batch;

import io.github.huck5.recordservice.contact.client.ContactImportClient;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

@Component
public class ContactImportTasklet implements Tasklet {
    private final ContactImportClient importClient;

    public ContactImportTasklet(ContactImportClient importClient) {
        this.importClient = importClient;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {


        return RepeatStatus.FINISHED;
    }
}
