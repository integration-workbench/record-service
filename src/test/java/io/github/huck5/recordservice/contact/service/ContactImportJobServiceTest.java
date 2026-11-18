package io.github.huck5.recordservice.contact.service;

import io.github.huck5.recordservice.CrmSource;
import io.github.huck5.recordservice.contact.entity.CrmConnection;
import io.github.huck5.recordservice.contact.exception.CrmConnectionNotFundException;
import io.github.huck5.recordservice.contact.exception.ImportJobFailedException;
import io.github.huck5.recordservice.contact.exception.ImportRequestFailedException;
import io.github.huck5.recordservice.contact.repository.CrmConnectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.test.MetaDataInstanceFactory;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContactImportJobServiceTest {
    @Mock private CrmConnectionRepository connections;
    @Mock private JobOperator operator;
    @Mock private Job job;
    @Mock private CrmConnection connection;
    private ContactImportJobService service;

    @BeforeEach
    void setUp() {
        service = new ContactImportJobService(connections, operator, job);
    }

    @ParameterizedTest
    @EnumSource(CrmSource.class)
    void launchesSelectedConnectionAndReturnsCompletedTotals(CrmSource source) throws Exception {
        when(connections.findByCrmSource(source)).thenReturn(Optional.of(connection));
        when(connection.getId()).thenReturn(42L);
        JobExecution execution = MetaDataInstanceFactory.createJobExecution();
        execution.setStatus(BatchStatus.COMPLETED);
        var step = MetaDataInstanceFactory.createStepExecution(execution, "contactImportStep", 1L);
        execution.addStepExecution(step);
        var context = step.getExecutionContext();
        context.putLong("counts.FETCHED", 15L);
        context.putLong("counts.IMPORTED", 5L);
        context.putLong("counts.SKIPPED", 4L);
        context.putLong("counts.INVALID_EXTERNAL_ID", 3L);
        context.putLong("counts.INVALID_CONTACT", 2L);
        context.putLong("counts.FAILED", 1L);
        when(operator.start(same(job), any(JobParameters.class))).thenReturn(execution);

        var response = service.importContacts(source);

        var parameters = ArgumentCaptor.forClass(JobParameters.class);
        verify(operator).start(same(job), parameters.capture());
        assertEquals(42L, parameters.getValue().getLong(ContactImportJobService.CONNECTION_ID));
        assertEquals(source.name(), parameters.getValue().getString(ContactImportJobService.CRM_SOURCE));
        assertNotNull(UUID.fromString(parameters.getValue().getString(ContactImportJobService.IMPORT_ID)));
        assertEquals(source, response.getSource());
        assertAll(
                () -> assertEquals(15, response.getFetched()),
                () -> assertEquals(5, response.getImported()),
                () -> assertEquals(4, response.getSkipped()),
                () -> assertEquals(3, response.getInvalidExternalId()),
                () -> assertEquals(2, response.getInvalidContact()),
                () -> assertEquals(1, response.getFailed()));
    }

    @Test
    void missingConnectionDoesNotLaunchJob() {
        when(connections.findByCrmSource(CrmSource.SALESFORCE)).thenReturn(Optional.empty());

        assertThrows(CrmConnectionNotFundException.class,
                () -> service.importContacts(CrmSource.SALESFORCE));

        verifyNoInteractions(operator);
    }

    @Test
    void failedJobPreservesAllFailures() throws Exception {
        when(connections.findByCrmSource(CrmSource.SALESFORCE)).thenReturn(Optional.of(connection));
        when(connection.getId()).thenReturn(42L);
        JobExecution execution = MetaDataInstanceFactory.createJobExecution();
        execution.setStatus(BatchStatus.FAILED);
        var primary = new ExecutionException(new ImportRequestFailedException("Page two failed"));
        var additional = new IllegalStateException("Additional failure");
        execution.addFailureException(primary);
        execution.addFailureException(additional);
        when(operator.start(same(job), any(JobParameters.class))).thenReturn(execution);

        var failure = assertThrows(ImportJobFailedException.class,
                () -> service.importContacts(CrmSource.SALESFORCE));

        // Batch aggregates failures through a Set; its returned order is not guaranteed.
        assertNotNull(failure.getCause());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals(Set.of(primary, additional),
                Set.of(failure.getCause(), failure.getSuppressed()[0]));
    }

    @ParameterizedTest
    @EnumSource(value = BatchStatus.class, names = {"FAILED", "STOPPED"})
    void incompleteJobWithoutExceptionsStillDoesNotReturnSuccess(BatchStatus status) throws Exception {
        when(connections.findByCrmSource(CrmSource.SALESFORCE)).thenReturn(Optional.of(connection));
        when(connection.getId()).thenReturn(42L);
        JobExecution execution = MetaDataInstanceFactory.createJobExecution();
        execution.setStatus(status);
        when(operator.start(same(job), any(JobParameters.class))).thenReturn(execution);

        var failure = assertThrows(ImportJobFailedException.class,
                () -> service.importContacts(CrmSource.SALESFORCE));

        assertNull(failure.getCause());
    }
}
