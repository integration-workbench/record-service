package io.github.huck5.recordservice.contact.service;

import io.github.huck5.recordservice.contact.dto.CreateContactRequest;
import io.github.huck5.recordservice.contact.entity.Contact;
import io.github.huck5.recordservice.contact.exception.ContactVersionConflictException;
import io.github.huck5.recordservice.contact.exception.ContactNotFoundException;
import io.github.huck5.recordservice.contact.repository.ContactRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class ContactService {
    public static final String CONTACT = "Contact: ";
    public static final String NOT_FOUND = " not found";
    private final ContactRepository repository;

    public ContactService(ContactRepository repository) {
        this.repository = repository;
    }

    public Contact getContact(Long id) {
        return repository.findById(id).orElseThrow(
                () -> new ContactNotFoundException(CONTACT + id + NOT_FOUND));
    }

    public List<Contact> getContacts() {
        return repository.findAll();
    }

    @Transactional
    public Contact insertContact(CreateContactRequest contactRequest) {
        Contact contact = new Contact(contactRequest.getFirstName(), contactRequest.getLastName(),
                contactRequest.getEmail());
        return repository.save(contact);
    }

    @Transactional
    public Contact updateContact(Long id, CreateContactRequest contactRequest, Long expectedVersion) {
        Contact contact = repository.findById(id).orElseThrow(() ->
                new ContactNotFoundException(CONTACT + id + NOT_FOUND));

        if (!Objects.equals(expectedVersion, contact.getVersion())) {
            throw new ContactVersionConflictException(
                    "Attempted to update [%d] and retrieved [%d]".formatted(contact.getVersion(), expectedVersion));
        }

        contact.setFirstName(contactRequest.getFirstName());
        contact.setLastName(contactRequest.getLastName());
        contact.setEmail(contactRequest.getEmail());

        return repository.save(contact);
    }

    @Transactional
    public void deleteContact(Long id, Long expectedVersion) {
        Contact contact = repository.findById(id).orElseThrow(() ->
                new ContactNotFoundException(CONTACT + id + NOT_FOUND));

        if (!Objects.equals(expectedVersion, contact.getVersion())) {
            throw new ContactVersionConflictException(
                    "Cannot delete contact %d: expected version %d, found %d"
                            .formatted(id, expectedVersion, contact.getVersion()));
        }

        repository.delete(contact);
    }
}
