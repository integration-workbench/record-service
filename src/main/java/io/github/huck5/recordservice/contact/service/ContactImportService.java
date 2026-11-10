package io.github.huck5.recordservice.contact.service;

import io.github.huck5.recordservice.CrmSource;
import io.github.huck5.recordservice.contact.dto.ContactCreateRequest;
import io.github.huck5.recordservice.contact.dto.ContactImportResponse;
import io.github.huck5.recordservice.contact.dto.ContactRetrievalResponse;
import io.github.huck5.recordservice.contact.dto.CrmContactDto;
import io.github.huck5.recordservice.contact.entity.Contact;
import io.github.huck5.recordservice.contact.entity.CrmConnection;
import io.github.huck5.recordservice.contact.entity.CrmContactLink;
import io.github.huck5.recordservice.contact.exception.ContactNotValidException;
import io.github.huck5.recordservice.contact.exception.CrmConnectionNotFundException;
import io.github.huck5.recordservice.contact.exception.ImportRequestFailedException;
import io.github.huck5.recordservice.contact.repository.CrmConnectionRepository;
import io.github.huck5.recordservice.contact.repository.CrmContactLinkRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ContactImportService {
    private static final String CRM_SERVICE_URI = "http://localhost:8080/%s/contacts";

    private final CrmContactLinkRepository crmContactLinkRepository;
    private final CrmConnectionRepository crmConnectionRepository;
    private final TransactionTemplate transactionTemplate;
    private final ContactService contactService;
    private final HttpClient httpClient;
    private final JsonMapper jsonMapper;

    public ContactImportService(ContactService contactService,
                                CrmContactLinkRepository crmContactLinkRepository,
                                CrmConnectionRepository crmConnectionRepository,
                                PlatformTransactionManager transactionManager,
                                HttpClient httpClient,
                                JsonMapper jsonMapper) {
        this.contactService = contactService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.crmContactLinkRepository = crmContactLinkRepository;
        this.crmConnectionRepository = crmConnectionRepository;
        this.httpClient = httpClient;
        this.jsonMapper = jsonMapper;
    }

    public ContactImportResponse importContacts(CrmSource crmSource) {
        CrmConnection crmConnection = crmConnectionRepository.findByCrmSource(crmSource).orElseThrow(
                () -> new CrmConnectionNotFundException(
                        "CrmConnection not found for source: %s".formatted(crmSource.name())));

        HttpRequest request = HttpRequest.newBuilder()
                                         .uri(URI.create(CRM_SERVICE_URI.formatted(crmSource.toString().toLowerCase())))
                                         .GET()
                                         .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new ImportRequestFailedException(
                        "Import contacts failed. Status code %s".formatted(response.statusCode()));
            }
        } catch (IOException e) {
            throw new RuntimeException("Import contacts request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Import contacts request interrupted.", e);
        }

        // Successfully retrieved contacts for specified CRM
        ContactRetrievalResponse retrievalResponse = jsonMapper.readValue(response.body(), new TypeReference<>() {
        });
        List<CrmContactDto> contacts = retrievalResponse.contacts();

        List<String> externalIds = contacts.stream().map(CrmContactDto::getExternalId).toList();
        List<CrmContactLink> contactLinks = crmContactLinkRepository.findByConnectionAndExternalIdIn(crmConnection,
                externalIds);
        Set<String> linkedExternalIds = contactLinks.stream().map(CrmContactLink::getExternalId).collect(
                Collectors.toSet());

        int imported = 0;
        int skipped = 0;
        int invalidExternalId = 0;
        int invalidContact = 0;
        int failed = 0;
        int fetched = contacts.size();

        for (CrmContactDto incoming : contacts) {
            String externalId = incoming.getExternalId();

            if (!StringUtils.hasText(externalId)) {
                invalidExternalId++;
            } else if (linkedExternalIds.contains(externalId)) {
                skipped++;
            } else {
                try {
                    saveContactAndLink(crmConnection, incoming);
                    imported++;
                    linkedExternalIds.add(externalId);
                } catch (ContactNotValidException e) {
                    invalidContact++;
                } catch (DataIntegrityViolationException e) {
                    failed++;
                }
            }
        }

        return ContactImportResponse.builder().source(crmSource).fetched(fetched).imported(imported).skipped(
                skipped).invalidExternalId(invalidExternalId).invalidContact(invalidContact).failed(failed).build();
    }

    private void saveContactAndLink(CrmConnection connection, CrmContactDto incoming) {
        transactionTemplate.executeWithoutResult(status -> {
            ContactCreateRequest request = new ContactCreateRequest(
                    incoming.getFirstName(),
                    incoming.getLastName(),
                    incoming.getEmail());

            Contact savedContact = contactService.insertContact(request);

            crmContactLinkRepository.save(new CrmContactLink(connection, savedContact, incoming.getExternalId()));
        });
    }
}
