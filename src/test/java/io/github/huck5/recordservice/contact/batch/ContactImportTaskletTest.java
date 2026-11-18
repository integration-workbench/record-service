package io.github.huck5.recordservice.contact.batch;

import io.github.huck5.recordservice.CrmSource;
import io.github.huck5.recordservice.contact.client.ContactImportClient;
import io.github.huck5.recordservice.contact.dto.CrmContactDto;
import io.github.huck5.recordservice.contact.entity.CrmConnection;
import io.github.huck5.recordservice.contact.exception.ImportRequestFailedException;
import io.github.huck5.recordservice.contact.repository.CrmConnectionRepository;
import io.github.huck5.recordservice.contact.service.ContactImportPersistenceService;
import io.github.huck5.recordservice.contact.service.ImportOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.batch.test.MetaDataInstanceFactory;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static io.github.huck5.recordservice.support.ContactImportFixtures.*;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@Timeout(20)
class ContactImportTaskletTest {
    @Mock private ContactImportClient client;
    @Mock private ContactImportPersistenceService persistence;
    @Mock private CrmConnectionRepository connections;
    private final CrmConnection connection = new CrmConnection(CrmSource.SALESFORCE, "test-account");
    private ExecutorService prefetch;
    private ContactImportTasklet tasklet;
    private StepExecution step;

    @BeforeEach
    void setUp() {
        // Two available workers ensure a single-thread queue cannot hide unbounded prefetch.
        prefetch = Executors.newFixedThreadPool(2);
        tasklet = new ContactImportTasklet(client, prefetch, persistence, connections);
        step = MetaDataInstanceFactory.createStepExecution(parameters(42L));
        when(connections.findById(42L)).thenReturn(Optional.of(connection));
    }

    @AfterEach
    void stopWorkers() throws InterruptedException {
        prefetch.shutdownNow();
        assertTrue(prefetch.awaitTermination(5, SECONDS), "Prefetch workers did not stop");
    }

    @Test
    void processesEveryPageAndTotalsAllContactOutcomes() throws Exception {
        when(client.fetchPage(CrmSource.SALESFORCE, null)).thenReturn(page("page-2", "one", "two"));
        when(client.fetchPage(CrmSource.SALESFORCE, "page-2")).thenReturn(page("page-3", "three", "four"));
        when(client.fetchPage(CrmSource.SALESFORCE, "page-3")).thenReturn(page(null, "five", "six"));
        when(persistence.importContact(same(connection), any(CrmContactDto.class)))
                .thenReturn(ImportOutcome.IMPORTED, ImportOutcome.SKIPPED, ImportOutcome.INVALID_EXTERNAL_ID,
                        ImportOutcome.INVALID_CONTACT, ImportOutcome.FAILED, ImportOutcome.IMPORTED);

        assertEquals(RepeatStatus.FINISHED, execute());

        var context = step.getExecutionContext();
        assertEquals(6L, context.getLong("counts.FETCHED"));
        assertEquals(2L, context.getLong("counts.IMPORTED"));
        for (var outcome : ImportOutcome.values()) {
            if (outcome != ImportOutcome.IMPORTED) {
                assertEquals(1L, context.getLong("counts." + outcome.name()), outcome.name());
            }
        }
        var writes = inOrder(persistence);
        for (String id : new String[]{"one", "two", "three", "four", "five", "six"}) {
            writes.verify(persistence).importContact(connection, contact(id));
        }
        verify(client).fetchPage(CrmSource.SALESFORCE, null);
        verify(client).fetchPage(CrmSource.SALESFORCE, "page-2");
        verify(client).fetchPage(CrmSource.SALESFORCE, "page-3");
        verifyNoMoreInteractions(client, persistence);
    }

    @Test
    void emptyTerminalPageFinishesWithZeroCounts() throws Exception {
        when(client.fetchPage(CrmSource.SALESFORCE, null)).thenReturn(page(null));

        assertEquals(RepeatStatus.FINISHED, execute());

        assertEquals(0L, step.getExecutionContext().getLong("counts.FETCHED"));
        for (var outcome : ImportOutcome.values()) {
            assertEquals(0L, step.getExecutionContext().getLong("counts." + outcome.name()));
        }
        verifyNoInteractions(persistence);
        verify(client).fetchPage(CrmSource.SALESFORCE, null);
        verifyNoMoreInteractions(client);
    }

    @Test
    void fetchesNextPageDuringCurrentPersistenceWithoutReadingFurtherAhead() throws Exception {
        var firstWrite = new CountDownLatch(1);
        var secondFetched = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondWrite = new CountDownLatch(1);
        var thirdFetched = new CountDownLatch(1);
        var releaseSecond = new CountDownLatch(1);
        when(client.fetchPage(CrmSource.SALESFORCE, null)).thenReturn(page("page-2", "one"));
        when(client.fetchPage(CrmSource.SALESFORCE, "page-2")).thenAnswer(invocation -> {
            assertTrue(firstWrite.await(5, SECONDS), "Page one persistence never started");
            secondFetched.countDown();
            return page("page-3", "two");
        });
        when(client.fetchPage(CrmSource.SALESFORCE, "page-3")).thenAnswer(invocation -> {
            assertTrue(secondWrite.await(5, SECONDS), "Page two persistence never started");
            thirdFetched.countDown();
            return page(null, "three");
        });
        when(persistence.importContact(connection, contact("one"))).thenAnswer(invocation -> {
            firstWrite.countDown();
            assertTrue(releaseFirst.await(5, SECONDS), "Page one persistence was not released");
            return ImportOutcome.IMPORTED;
        });
        when(persistence.importContact(connection, contact("two"))).thenAnswer(invocation -> {
            secondWrite.countDown();
            assertTrue(releaseSecond.await(5, SECONDS), "Page two persistence was not released");
            return ImportOutcome.IMPORTED;
        });
        when(persistence.importContact(connection, contact("three"))).thenReturn(ImportOutcome.IMPORTED);

        var caller = Executors.newSingleThreadExecutor();
        try {
            var result = caller.submit(this::execute);
            assertTrue(secondFetched.await(5, SECONDS), "Page two was not fetched during page one persistence");
            verify(client, never()).fetchPage(CrmSource.SALESFORCE, "page-3");
            verify(persistence, never()).importContact(connection, contact("two"));

            releaseFirst.countDown();
            assertTrue(thirdFetched.await(5, SECONDS), "Page three was not fetched during page two persistence");
            verify(persistence, never()).importContact(connection, contact("three"));

            releaseSecond.countDown();
            assertEquals(RepeatStatus.FINISHED, result.get(5, SECONDS));
            assertEquals(3L, step.getExecutionContext().getLong("counts.IMPORTED"));
            var writes = inOrder(persistence);
            writes.verify(persistence).importContact(connection, contact("one"));
            writes.verify(persistence).importContact(connection, contact("two"));
            writes.verify(persistence).importContact(connection, contact("three"));
        } finally {
            releaseFirst.countDown();
            releaseSecond.countDown();
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(5, SECONDS), "Tasklet caller did not stop");
        }
    }

    @Test
    void subsequentRetrievalFailureEscapesInsteadOfReturningFinished() {
        var cause = new ImportRequestFailedException("Page two unavailable");
        when(client.fetchPage(CrmSource.SALESFORCE, null)).thenReturn(page("page-2", "one"));
        when(client.fetchPage(CrmSource.SALESFORCE, "page-2")).thenThrow(cause);
        when(persistence.importContact(connection, contact("one"))).thenReturn(ImportOutcome.IMPORTED);

        var failure = assertThrows(ExecutionException.class, this::execute);

        assertSame(cause, failure.getCause());
        verify(persistence).importContact(connection, contact("one"));
        verifyNoMoreInteractions(persistence);
    }

    @Test
    void unexpectedDatabaseFailureStopsBeforeProcessingRemainingContacts() {
        when(client.fetchPage(CrmSource.SALESFORCE, null)).thenReturn(page(null, "one", "two"));
        var cause = new DataAccessResourceFailureException("Database unavailable");
        when(persistence.importContact(connection, contact("one"))).thenThrow(cause);

        assertSame(cause, assertThrows(DataAccessResourceFailureException.class, this::execute));

        verify(persistence, never()).importContact(connection, contact("two"));
    }

    private RepeatStatus execute() throws Exception {
        return tasklet.execute(new StepContribution(step), new ChunkContext(new StepContext(step)));
    }
}
