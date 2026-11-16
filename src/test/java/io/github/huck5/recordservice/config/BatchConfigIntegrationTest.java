package io.github.huck5.recordservice.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.core.step.tasklet.TaskletStep;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.batch.test.MetaDataInstanceFactory;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBatchTest
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.batch.job.enabled=false",
                "spring.batch.jdbc.initialize-schema=never"
        })
class BatchConfigIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer mysql =
            new MySQLContainer("mysql:8.4");

    @Autowired
    private JobOperatorTestUtils jobOperatorTestUtils;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    @Qualifier("batchCheckStep")
    private Step batchCheckStep;

    private final List<Integer> processedContacts = new ArrayList<>();

    // -1 disables the deliberately injected failure.
    private int failAfter = -1;

    private TaskletStep taskletStep;
    private Tasklet originalTasklet;

    @BeforeEach
    void installFailureControl() {
        taskletStep = assertInstanceOf(TaskletStep.class, batchCheckStep);
        originalTasklet = taskletStep.getTasklet();
        assertNotNull(originalTasklet);

        taskletStep.setTasklet((contribution, chunkContext) -> {
            ExecutionContext context =
                    contribution.getStepExecution().getExecutionContext();

            int processedCount = context.getInt("processedCount", 0);

            // Fail before the next contact changes the checkpoint.
            if (processedCount == failAfter) {
                throw new IllegalStateException(
                        "Deliberate failure after "
                                + processedCount + " contacts");
            }

            RepeatStatus result =
                    originalTasklet.execute(contribution, chunkContext);

            processedContacts.add(context.getInt("processedCount"));

            return result;
        });
    }

    @AfterEach
    void restoreOriginalTasklet() {
        if (taskletStep != null && originalTasklet != null) {
            taskletStep.setTasklet(originalTasklet);
        }
    }

    @Test
    void batchCheckJob_whenSuccessful_completesAllContacts()
            throws Exception {

        JobExecution execution =
                jobOperatorTestUtils.startJob(newImportParameters());

        assertSavedJobStatus(execution, BatchStatus.COMPLETED);

        StepExecution savedStep = loadStep(execution);

        assertEquals(BatchStatus.COMPLETED, savedStep.getStatus());
        assertEquals(5, savedStep.getExecutionContext()
                .getInt("processedCount"));
        assertEquals(List.of(1, 2, 3, 4, 5), processedContacts);
    }

    @Test
    void batchCheckJob_whenTaskletFails_recordsFailureWithoutProgress()
            throws Exception {

        failAfter = 0;

        JobExecution execution =
                jobOperatorTestUtils.startJob(newImportParameters());

        assertSavedJobStatus(execution, BatchStatus.FAILED);

        StepExecution savedStep = loadStep(execution);

        assertEquals(BatchStatus.FAILED, savedStep.getStatus());
        assertEquals(0, savedStep.getExecutionContext()
                .getInt("processedCount", 0));
        assertTrue(processedContacts.isEmpty());

        assertTrue(execution.getAllFailureExceptions().stream()
                .anyMatch(exception ->
                        exception instanceof IllegalStateException
                                && "Deliberate failure after 0 contacts"
                                .equals(exception.getMessage())));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void batchCheckJob_whenRestarted_resumesFromSavedCheckpoint(
            int failureAfter) throws Exception {

        JobParameters parameters = newImportParameters();
        failAfter = failureAfter;

        // First attempt fails at the configured position.
        JobExecution failedExecution =
                jobOperatorTestUtils.startJob(parameters);

        assertSavedJobStatus(failedExecution, BatchStatus.FAILED);

        StepExecution failedStep = loadStep(failedExecution);

        assertEquals(BatchStatus.FAILED, failedStep.getStatus());
        assertEquals(failureAfter, failedStep.getExecutionContext()
                .getInt("processedCount", 0));

        List<Integer> expectedBeforeFailure =
                failureAfter == 0 ? List.of() : List.of(1, 2);

        assertEquals(expectedBeforeFailure, processedContacts);

        // Remove the failure and observe only the restart's work.
        failAfter = -1;
        processedContacts.clear();

        // Reuse exactly the same identifying parameters.
        JobExecution restartedExecution =
                jobOperatorTestUtils.startJob(parameters);

        assertSavedJobStatus(restartedExecution, BatchStatus.COMPLETED);

        StepExecution restartedStep = loadStep(restartedExecution);

        assertEquals(
                failedExecution.getJobInstanceId(),
                restartedExecution.getJobInstanceId());

        assertNotEquals(
                failedExecution.getId(),
                restartedExecution.getId());

        assertNotEquals(failedStep.getId(), restartedStep.getId());

        assertEquals(BatchStatus.COMPLETED, restartedStep.getStatus());
        assertEquals(5, restartedStep.getExecutionContext()
                .getInt("processedCount"));

        List<Integer> expectedAfterRestart =
                failureAfter == 0
                        ? List.of(1, 2, 3, 4, 5)
                        : List.of(3, 4, 5);

        assertEquals(expectedAfterRestart, processedContacts);

        // The original attempt remains recorded as failed.
        assertSavedJobStatus(failedExecution, BatchStatus.FAILED);
        assertEquals(failureAfter, loadStep(failedExecution)
                .getExecutionContext()
                .getInt("processedCount", 0));

        assertEquals(2, jobRepository
                .getJobExecutions(restartedExecution.getJobInstance())
                .size());
    }

    @Test
    void batchCheckJob_whenAlreadyCompleted_rejectsSameParameters()
            throws Exception {

        JobParameters parameters = newImportParameters();

        JobExecution execution =
                jobOperatorTestUtils.startJob(parameters);

        assertSavedJobStatus(execution, BatchStatus.COMPLETED);
        processedContacts.clear();

        assertThrows(
                JobInstanceAlreadyCompleteException.class,
                () -> jobOperatorTestUtils.startJob(parameters));

        assertTrue(processedContacts.isEmpty());

        assertEquals(1, jobRepository
                .getJobExecutions(execution.getJobInstance())
                .size());

        assertEquals(5, loadStep(execution)
                .getExecutionContext()
                .getInt("processedCount"));
    }

    @Test
    void batchCheckJob_whenImportIdChanges_startsFromZero()
            throws Exception {

        JobExecution firstExecution =
                jobOperatorTestUtils.startJob(newImportParameters());

        assertSavedJobStatus(firstExecution, BatchStatus.COMPLETED);
        assertEquals(List.of(1, 2, 3, 4, 5), processedContacts);

        processedContacts.clear();

        JobExecution secondExecution =
                jobOperatorTestUtils.startJob(newImportParameters());

        assertSavedJobStatus(secondExecution, BatchStatus.COMPLETED);

        assertNotEquals(
                firstExecution.getJobInstanceId(),
                secondExecution.getJobInstanceId());

        // A new import processes all five contacts again.
        assertEquals(List.of(1, 2, 3, 4, 5), processedContacts);

        assertEquals(5, loadStep(secondExecution)
                .getExecutionContext()
                .getInt("processedCount"));
    }

    private JobParameters newImportParameters() {
        return new JobParametersBuilder()
                .addString("importId", UUID.randomUUID().toString())
                .toJobParameters();
    }

    private StepExecution loadStep(JobExecution execution) {
        assertEquals(1, execution.getStepExecutions().size());

        StepExecution step =
                execution.getStepExecutions().iterator().next();

        StepExecution savedStep =
                jobRepository.getStepExecution(step.getId());

        assertNotNull(savedStep);
        return savedStep;
    }

    public StepExecution getStepExecution() {
        return MetaDataInstanceFactory.createStepExecution();
    }

    private void assertSavedJobStatus(
            JobExecution execution,
            BatchStatus expectedStatus) {

        JobExecution savedExecution =
                jobRepository.getJobExecution(execution.getId());

        assertNotNull(savedExecution);
        assertEquals(expectedStatus, savedExecution.getStatus());
    }
}