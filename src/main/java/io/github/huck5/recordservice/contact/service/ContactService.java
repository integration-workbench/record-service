package io.github.huck5.recordservice.contact.service;

import io.github.huck5.recordservice.CrmSource;
import io.github.huck5.recordservice.contact.dto.ContactDto;
import io.github.huck5.recordservice.contact.dto.CreateContactRequest;
import io.github.huck5.recordservice.contact.dto.ImportContactResponse;
import io.github.huck5.recordservice.contact.entity.Contact;
import io.github.huck5.recordservice.contact.exception.ContactNotFoundException;
import io.github.huck5.recordservice.contact.exception.ContactNotValidException;
import io.github.huck5.recordservice.contact.exception.ContactVersionConflictException;
import io.github.huck5.recordservice.contact.exception.ImportRequestFailedException;
import io.github.huck5.recordservice.contact.repository.ContactRepository;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Objects;

@Service
public class ContactService {
    private static final String CRM_SERVICE_URI = "http://localhost:8080/%s/contacts";
    private final ContactRepository repository;
    private final HttpClient httpClient;
    private final JsonMapper jsonMapper;

    public ContactService(ContactRepository repository, HttpClient httpClient, JsonMapper jsonMapper) {
        this.repository = repository;
        this.httpClient = httpClient;
        this.jsonMapper = jsonMapper;
    }

    public List<ContactDto> importContacts(CrmSource crmSource) {
        HttpRequest request = HttpRequest.newBuilder()
                                         .uri(URI.create(CRM_SERVICE_URI.formatted(crmSource.toString().toLowerCase())))
                                         .GET()
                                         .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new ImportRequestFailedException(
                        "Import contacts failed. Status code %s, body: %s".formatted(response.statusCode(),
                                response.body()));
            }
        } catch (IOException e) {
            throw new RuntimeException("Import contacts request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Import contacts request interrupted.", e);
        }

        List<ContactDto> contacts = jsonMapper.readValue(response.body(), new TypeReference<>() {});

        return contacts;
    }

    public Contact getContact(Long id) {
        return repository.findById(id).orElseThrow(
                () -> new ContactNotFoundException("Contact: " + id + " not found"));
    }

    public Page<Contact> getContacts(Pageable pageable) {
        return repository.findAll(pageable);
    }

    @Transactional
    public Contact insertContact(CreateContactRequest contactRequest) {
        if (isContactEmpty(contactRequest)) {
            throw new ContactNotValidException("Provide a first name, last name, or email.");
        }

        Contact contact = new Contact(contactRequest.getFirstName(), contactRequest.getLastName(),
                contactRequest.getEmail());
        return repository.save(contact);
    }

    @Transactional
    public Contact updateContact(Long id, CreateContactRequest contactRequest, Long expectedVersion) {
        if (isContactEmpty(contactRequest)) {
            throw new ContactNotValidException("Provide a first name, last name, or email.");
        }

        Contact contact = getContact(id);

        if (!Objects.equals(expectedVersion, contact.getVersion())) {
            throw new ContactVersionConflictException(
                    "Cannot update contact [%d]: expected version [%d], found [%d]"
                            .formatted(id, expectedVersion, contact.getVersion()));
        }

        contact.setFirstName(contactRequest.getFirstName());
        contact.setLastName(contactRequest.getLastName());
        contact.setEmail(contactRequest.getEmail());

        return repository.save(contact);
    }

    @Transactional
    public void deleteContact(Long id, Long expectedVersion) {
        Contact contact = getContact(id);

        if (!Objects.equals(expectedVersion, contact.getVersion())) {
            throw new ContactVersionConflictException(
                    "Cannot delete contact [%d]: expected version [%d], found [%d]"
                            .formatted(id, expectedVersion, contact.getVersion()));
        }

        repository.delete(contact);
    }

    private boolean isContactEmpty(CreateContactRequest contactRequest) {
        return !StringUtils.hasText(contactRequest.getFirstName()) && !StringUtils.hasText(
                contactRequest.getLastName()) && !StringUtils.hasText(contactRequest.getEmail());
    }
}
