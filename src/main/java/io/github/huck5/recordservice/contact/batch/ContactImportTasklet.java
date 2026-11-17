package io.github.huck5.recordservice.contact.batch;

import io.github.huck5.recordservice.CrmSource;
import io.github.huck5.recordservice.contact.dto.ContactRetrievalResponse;
import io.github.huck5.recordservice.contact.client.ContactImportClient;
import org.jspecify.annotations.NonNull;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

import static io.github.huck5.recordservice.contact.service.ContactImportJobService.CRM_SOURCE;

@Component
public class ContactImportTasklet implements Tasklet {
    private final ContactImportClient importClient;
    private final Executor prefetchExecutor;

    public ContactImportTasklet(ContactImportClient importClient,
                                @Qualifier("prefetchExecutor") Executor prefetchExecutor) {
        this.importClient = importClient;
        this.prefetchExecutor = prefetchExecutor;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution,
                                @NonNull ChunkContext chunkContext) throws ExecutionException, InterruptedException {
        String source = contribution.getStepExecution().getJobParameters().getString(CRM_SOURCE);

        if (source == null) {
            throw new IllegalArgumentException("CRM_SOURCE is required");
        }

        CrmSource crmSource = CrmSource.valueOf(source);
        ContactRetrievalResponse page = importClient.fetchPage(crmSource, null);

        while (true) {
            CompletableFuture<ContactRetrievalResponse> nextPage = null;

            if (page.hasMore()) {
                String nextToken = page.continuationToken();

                // Submit the next HTTP fetch to a worker thread
                nextPage = CompletableFuture.supplyAsync(() -> importClient.fetchPage(crmSource, nextToken),
                        prefetchExecutor);
            }

            // Process the CURRENT page on the tasklet's thread
            // TODO: persistPage(page);

            if (nextPage == null) {
                break; // We have processed the terminal page
            }

            page = nextPage.get();
        }

        return RepeatStatus.FINISHED;
    }
}
