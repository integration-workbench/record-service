package io.github.huck5.recordservice.contact.service;

import io.github.huck5.recordservice.contact.dto.CreateContactRequest;
import io.github.huck5.recordservice.contact.entity.Contact;
import io.github.huck5.recordservice.contact.exception.ContactNotFoundException;
import io.github.huck5.recordservice.contact.exception.ContactNotValidException;
import io.github.huck5.recordservice.contact.exception.ContactVersionConflictException;
import io.github.huck5.recordservice.contact.repository.ContactRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

@Service
public class ContactService {
    private final ContactRepository repository;

    public ContactService(ContactRepository repository) {
        this.repository = repository;
    }

    public Contact getContact(Long id) {
        return repository.findById(id).orElseThrow(
                () -> new ContactNotFoundException("Contact: " + id + " not found"));
    }

    public List<Contact> getContacts() {
        return repository.findAll();
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
