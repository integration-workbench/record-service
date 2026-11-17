package io.github.huck5.recordservice.contact.batch;

import io.github.huck5.recordservice.CrmSource;
import io.github.huck5.recordservice.contact.client.ContactImportClient;
import io.github.huck5.recordservice.contact.dto.ContactRetrievalResponse;
import io.github.huck5.recordservice.contact.dto.CrmContactDto;
import io.github.huck5.recordservice.contact.entity.CrmConnection;
import io.github.huck5.recordservice.contact.exception.CrmConnectionNotFundException;
import io.github.huck5.recordservice.contact.repository.CrmConnectionRepository;
import io.github.huck5.recordservice.contact.service.ContactImportJobService;
import io.github.huck5.recordservice.contact.service.ContactImportPersistenceService;
import io.github.huck5.recordservice.contact.service.ImportOutcome;
import org.jspecify.annotations.NonNull;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

import static io.github.huck5.recordservice.contact.service.ContactImportJobService.CRM_SOURCE;

@Component
public class ContactImportTasklet implements Tasklet {
    private final ContactImportClient importClient;
    private final Executor prefetchExecutor;
    private final ContactImportPersistenceService contactImportPersistenceService;
    private final CrmConnectionRepository crmConnectionRepository;

    public ContactImportTasklet(ContactImportClient importClient,
                                @Qualifier("prefetchExecutor") Executor prefetchExecutor,
                                ContactImportPersistenceService contactImportPersistenceService,
                                CrmConnectionRepository crmConnectionRepository) {
        this.importClient = importClient;
        this.prefetchExecutor = prefetchExecutor;
        this.contactImportPersistenceService = contactImportPersistenceService;
        this.crmConnectionRepository = crmConnectionRepository;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution,
                                @NonNull ChunkContext chunkContext) throws ExecutionException, InterruptedException {
        Long connectionId = contribution.getStepExecution().getJobParameters().getLong(ContactImportJobService.CONNECTION_ID);

        if (connectionId == null) {
            throw new IllegalArgumentException("CONNECTION_ID is required");
        }

        CrmConnection crmConnection = crmConnectionRepository.findById(connectionId).orElseThrow(() -> new CrmConnectionNotFundException(
                "CrmConnection not found for id: " + connectionId));

        String source = contribution.getStepExecution().getJobParameters().getString(CRM_SOURCE);

        if (source == null) {
            throw new IllegalArgumentException("CRM_SOURCE is required");
        }

        Map<ImportOutcome, Long> totals = new EnumMap<>(ImportOutcome.class);

        CrmSource crmSource = CrmSource.valueOf(source);
        ContactRetrievalResponse page = importClient.fetchPage(crmSource, null);
        long fetched = 0L;

        while (true) {
            CompletableFuture<ContactRetrievalResponse> nextPage = null;

            if (page.hasMore()) {
                String nextToken = page.continuationToken();

                // Submit the next HTTP fetch to a worker thread
                nextPage = CompletableFuture.supplyAsync(() -> importClient.fetchPage(crmSource, nextToken),
                        prefetchExecutor);
            }

            fetched += page.contacts().size();

            // Process the CURRENT page on the tasklet's thread
            Map<ImportOutcome, Long> pageCounts = persistPage(crmConnection, page);
            pageCounts.forEach((outcome, count) -> totals.merge(outcome, count, Long::sum));

            if (nextPage == null) {
                break; // We have processed the terminal page
            }

            page = nextPage.get();
        }

        // Add totals to execution context
        ExecutionContext context = contribution.getStepExecution().getExecutionContext();
        context.putLong("counts.FETCHED", fetched);

        for (ImportOutcome outcome : ImportOutcome.values()) {
            context.putLong("counts." + outcome.name(),
                    totals.getOrDefault(outcome, 0L));
        }

        return RepeatStatus.FINISHED;
    }

    // What should the return type be? List<ImportOutcome>?
    private Map<ImportOutcome, Long> persistPage(CrmConnection crmConnection, ContactRetrievalResponse page) {
        Map<ImportOutcome, Long> counts = new EnumMap<>(ImportOutcome.class);

        for (CrmContactDto contactDto : page.contacts()) {
            ImportOutcome outcome = contactImportPersistenceService.importContact(crmConnection, contactDto);
            counts.merge(outcome, 1L, Long::sum);
        }

        return counts;
    }
}
