package io.github.huck5.recordservice.contact.batch;

import io.github.huck5.recordservice.CrmSource;
import io.github.huck5.recordservice.contact.client.ContactImportClient;
import io.github.huck5.recordservice.contact.entity.Contact;
import io.github.huck5.recordservice.contact.exception.ImportRequestFailedException;
import io.github.huck5.recordservice.contact.repository.ContactRepository;
import io.github.huck5.recordservice.contact.repository.CrmConnectionRepository;
import io.github.huck5.recordservice.contact.repository.CrmContactLinkRepository;
import io.github.huck5.recordservice.support.ContactImportFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ContactImportJobIntegrationTest {
    @Container @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4");

    @Autowired private JobOperator operator;
    @Autowired @Qualifier("contactImportJob") private Job job;
    @Autowired private JobRepository jobs;
    @Autowired private ContactRepository contacts;
    @Autowired private CrmContactLinkRepository links;
    @Autowired private CrmConnectionRepository connections;
    @MockitoBean private ContactImportClient client;
    private JobParameters parameters;

    @BeforeEach
    void clearImports() {
        links.deleteAllInBatch();
        contacts.deleteAllInBatch();
        long connectionId = connections.findByCrmSource(CrmSource.SALESFORCE)
                .orElseThrow()
                .getId();
        parameters = ContactImportFixtures.parameters(connectionId);
    }

    @Test
    void actualJobImportsAllPagesAndPersistsMetadataWithoutDuplicatingRecords() throws Exception {
        when(client.fetchPage(CrmSource.SALESFORCE, null)).thenReturn(ContactImportFixtures.page("page-2", "one"));
        when(client.fetchPage(CrmSource.SALESFORCE, "page-2"))
                .thenReturn(ContactImportFixtures.page("page-3", "one", "two"));
        when(client.fetchPage(CrmSource.SALESFORCE, "page-3")).thenReturn(ContactImportFixtures.page(null));

        JobExecution execution = operator.start(job, parameters);

        assertSavedStatus(execution, BatchStatus.COMPLETED);
        ExecutionContext counts = savedCounts(execution);
        assertEquals(3, counts.getLong("counts.FETCHED"));
        assertEquals(2, counts.getLong("counts.IMPORTED"));
        assertEquals(1, counts.getLong("counts.SKIPPED"));
        assertEquals(0, counts.getLong("counts.FAILED"));
        assertEquals(2, contacts.count());
        assertEquals(2, links.count());
        verify(client).fetchPage(CrmSource.SALESFORCE, null);
        verify(client).fetchPage(CrmSource.SALESFORCE, "page-2");
        verify(client).fetchPage(CrmSource.SALESFORCE, "page-3");
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retrievalFailureMarksActualJobFailedAndPreservesEarlierCommits(boolean failOnSecondPage)
            throws Exception {
        ImportRequestFailedException failure = new ImportRequestFailedException("CRM unavailable");
        if (failOnSecondPage) {
            when(client.fetchPage(CrmSource.SALESFORCE, null)).thenReturn(ContactImportFixtures.page("page-2", "one"));
            when(client.fetchPage(CrmSource.SALESFORCE, "page-2")).thenThrow(failure);
        } else {
            when(client.fetchPage(CrmSource.SALESFORCE, null)).thenThrow(failure);
        }

        JobExecution execution = operator.start(job, parameters);

        assertSavedStatus(execution, BatchStatus.FAILED);
        assertTrue(execution.getAllFailureExceptions().stream().anyMatch(t -> causedBy(t, failure)));
        assertEquals(failOnSecondPage ? 1 : 0, contacts.count());
        assertEquals(failOnSecondPage ? 1 : 0, links.count());
    }

    @Test
    void restartAfterPageTwoFailurePreservesImportedTotalsAndLocalEdits() throws Exception {
        when(client.fetchPage(CrmSource.SALESFORCE, null)).thenReturn(ContactImportFixtures.page("page-2", "one"));
        when(client.fetchPage(CrmSource.SALESFORCE, "page-2"))
                .thenThrow(new ImportRequestFailedException("Page two unavailable"));
        JobExecution failed = operator.start(job, parameters);
        assertSavedStatus(failed, BatchStatus.FAILED);
        Contact first = contacts.findAll().getFirst();
        first.setEmail("local-edit@example.com");
        contacts.saveAndFlush(first);

        doReturn(ContactImportFixtures.page(null, "two")).when(client).fetchPage(CrmSource.SALESFORCE, "page-2");
        JobExecution restarted = operator.start(job, parameters);

        assertSavedStatus(restarted, BatchStatus.COMPLETED);
        assertSavedStatus(failed, BatchStatus.FAILED);
        assertEquals(failed.getJobInstanceId(), restarted.getJobInstanceId());
        assertNotEquals(failed.getId(), restarted.getId());
        assertEquals(2, contacts.count());
        assertEquals(2, links.count());
        assertEquals("local-edit@example.com", contacts.findById(first.getId()).orElseThrow().getEmail());
        // Same logical import: an earlier successful import must not turn into a skip on replay.
        ExecutionContext counts = savedCounts(restarted);
        assertAll(
                () -> assertEquals(2, counts.getLong("counts.FETCHED")),
                () -> assertEquals(2, counts.getLong("counts.IMPORTED")),
                () -> assertEquals(0, counts.getLong("counts.SKIPPED")));
    }

    private void assertSavedStatus(JobExecution execution, BatchStatus expected) {
        assertEquals(expected, execution.getStatus());
        assertEquals(expected, jobs.getJobExecution(execution.getId()).getStatus());
        StepExecution step = execution.getStepExecutions().iterator().next();
        assertEquals(expected, jobs.getStepExecution(step.getId()).getStatus());
    }

    private ExecutionContext savedCounts(JobExecution execution) {
        StepExecution step = execution.getStepExecutions().iterator().next();
        return jobs.getStepExecution(step.getId()).getExecutionContext();
    }

    private boolean causedBy(Throwable current, Throwable expected) {
        for (; current != null; current = current.getCause()) {
            if (current == expected) return true;
        }
        return false;
    }
}
