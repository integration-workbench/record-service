package io.github.huck5.recordservice.contact.service;

import io.github.huck5.recordservice.CrmSource;
import io.github.huck5.recordservice.contact.dto.ContactImportResponse;
import io.github.huck5.recordservice.contact.dto.CrmContactDto;
import io.github.huck5.recordservice.contact.entity.CrmConnection;
import io.github.huck5.recordservice.contact.entity.CrmContactLink;
import io.github.huck5.recordservice.contact.exception.ContactNotValidException;
import io.github.huck5.recordservice.contact.exception.CrmConnectionNotFundException;
import io.github.huck5.recordservice.contact.repository.CrmConnectionRepository;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.JobRestartException;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ContactImportJobService {
    public static final String CONNECTION_ID = "connectionId";
    public static final String IMPORT_ID = "importId";
    public static final String CRM_SOURCE = "crmSource";

    private final CrmConnectionRepository crmConnectionRepository;
    private final JobOperator jobOperator;
    private final Job contactImportJob;
    private final ContactImportPersistenceService contactImportPersistenceService;

    public ContactImportJobService(ContactImportPersistenceService contactImportPersistenceService,
                                   CrmConnectionRepository crmConnectionRepository,
                                   JobOperator jobOperator, @Qualifier("contactImportJob") Job contactImportJob) {
        this.contactImportPersistenceService = contactImportPersistenceService;
        this.crmConnectionRepository = crmConnectionRepository;
        this.jobOperator = jobOperator;
        this.contactImportJob = contactImportJob;
    }

    public ContactImportResponse importContacts(CrmSource crmSource) throws JobInstanceAlreadyCompleteException, InvalidJobParametersException, JobExecutionAlreadyRunningException, JobRestartException {
        CrmConnection crmConnection = crmConnectionRepository.findByCrmSource(crmSource).orElseThrow(
                () -> new CrmConnectionNotFundException(
                        "CrmConnection not found for source: %s" .formatted(crmSource.name())));

        JobParameters parameters = new JobParametersBuilder()
                .addLong(CONNECTION_ID, crmConnection.getId())
                .addString(IMPORT_ID, UUID.randomUUID().toString())
                .addString(CRM_SOURCE, crmSource.name())
                .toJobParameters();

        JobExecution execution = jobOperator.start(contactImportJob, parameters);

        if (execution.getStatus() != BatchStatus.COMPLETED) {
            throw new IllegalStateException(
                    "Import did not complete: " + execution.getStatus());
        }

        StepExecution step = execution.getStepExecutions().stream()
                                      .filter(s -> "contactImportStep".equals(s.getStepName()))
                                      .findFirst()
                                      .orElseThrow(() ->
                                              new IllegalStateException("Import step execution missing"));

        ExecutionContext context = step.getExecutionContext();

        long imported = context.getLong("counts.IMPORTED");
        long skipped = context.getLong("counts.SKIPPED");
        long invalidExternalId = context.getLong("counts.INVALID_EXTERNAL_ID");
        long invalidContact = context.getLong("counts.INVALID_CONTACT");
        long failed = context.getLong("counts.FAILED");
        long fetched = context.getLong("counts.FETCHED");
/*
        List<CrmContactDto> contacts = retrievalResponse.contacts();

        List<String> externalIds = contacts.stream().map(CrmContactDto::getExternalId).toList();
        List<CrmContactLink> contactLinks = crmContactLinkRepository.findByConnectionAndExternalIdIn(crmConnection,
                externalIds);
        Set<String> linkedExternalIds = contactLinks.stream().map(CrmContactLink::getExternalId).collect(
                Collectors.toSet());

        int fetched = contacts.size();
        boolean hasMore = retrievalResponse.hasMore();
        Long totalAvailable = retrievalResponse.totalAvailable();
        String continuationToken = retrievalResponse.continuationToken();

        for (CrmContactDto incoming : contacts) {
            String externalId = incoming.getExternalId();

            if (!StringUtils.hasText(externalId)) {
                invalidExternalId++;
            } else if (linkedExternalIds.contains(externalId)) {
                skipped++;
            } else {
                try {
                    contactImportPersistenceService.saveContactAndLink(crmConnection, incoming);
                    imported++;
                    linkedExternalIds.add(externalId);
                } catch (ContactNotValidException e) {
                    invalidContact++;
                } catch (DataIntegrityViolationException e) {
                    failed++;
                }
            }
        }
        */

        return ContactImportResponse.builder()
                                    .source(crmSource)
                                    .fetched(fetched)
                                    .imported(imported)
                                    .skipped(skipped)
                                    .invalidExternalId(invalidExternalId)
                                    .invalidContact(invalidContact)
                                    .failed(failed)
                                    .build();
    }

}
