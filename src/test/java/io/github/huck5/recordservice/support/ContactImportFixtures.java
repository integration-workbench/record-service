package io.github.huck5.recordservice.support;

import io.github.huck5.recordservice.CrmSource;
import io.github.huck5.recordservice.contact.dto.ContactRetrievalResponse;
import io.github.huck5.recordservice.contact.dto.CrmContactDto;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;

import java.util.Arrays;
import java.util.UUID;

import static io.github.huck5.recordservice.contact.service.ContactImportJobService.*;

public final class ContactImportFixtures {
    private ContactImportFixtures() { }

    public static CrmContactDto contact(String externalId) {
        return CrmContactDto.builder().source(CrmSource.SALESFORCE)
                .externalId(externalId).firstName("Bob").lastName("Dole")
                .email("bob@example.com").build();
    }

    public static ContactRetrievalResponse page(String nextToken, String... externalIds) {
        return new ContactRetrievalResponse(
                Arrays.stream(externalIds).map(ContactImportFixtures::contact).toList(),
                null, nextToken != null, nextToken);
    }

    public static JobParameters parameters(long connectionId) {
        return new JobParametersBuilder()
                .addLong(CONNECTION_ID, connectionId)
                .addString(IMPORT_ID, UUID.randomUUID().toString())
                .addString(CRM_SOURCE, CrmSource.SALESFORCE.name())
                .toJobParameters();
    }
}
