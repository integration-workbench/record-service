package io.github.huck5.recordservice.contact.service;

import io.github.huck5.recordservice.contact.dto.ContactCreateRequest;
import io.github.huck5.recordservice.contact.entity.Contact;
import io.github.huck5.recordservice.contact.exception.*;
import io.github.huck5.recordservice.contact.repository.ContactRepository;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import jakarta.validation.Validator;

import java.util.Objects;

@Service
public class ContactService {
    private final ContactRepository contactRepository;
    private final Validator validator;

    public ContactService(ContactRepository contactRepository, Validator validator) {
        this.contactRepository = contactRepository;
        this.validator = validator;
    }

    public Contact getContact(Long id) {
        return contactRepository.findById(id).orElseThrow(
                () -> new ContactNotFoundException("Contact: " + id + " not found"));
    }

    public Page<Contact> getContacts(Pageable pageable) {
        return contactRepository.findAll(pageable);
    }

    @Transactional
    public Contact insertContact(ContactCreateRequest contactRequest) {
        validateContact(contactRequest);

        Contact contact = new Contact(contactRequest.getFirstName(), contactRequest.getLastName(),
                contactRequest.getEmail());
        return contactRepository.save(contact);
    }

    @Transactional
    public Contact updateContact(Long id, ContactCreateRequest contactRequest, Long expectedVersion) {
        validateContact(contactRequest);

        Contact contact = getContact(id);

        if (!Objects.equals(expectedVersion, contact.getVersion())) {
            throw new ContactVersionConflictException(
                    "Cannot update contact [%d]: expected version [%d], found [%d]"
                            .formatted(id, expectedVersion, contact.getVersion()));
        }

        contact.setFirstName(contactRequest.getFirstName());
        contact.setLastName(contactRequest.getLastName());
        contact.setEmail(contactRequest.getEmail());

        return contactRepository.save(contact);
    }

    @Transactional
    public void deleteContact(Long id, Long expectedVersion) {
        Contact contact = getContact(id);

        if (!Objects.equals(expectedVersion, contact.getVersion())) {
            throw new ContactVersionConflictException(
                    "Cannot delete contact [%d]: expected version [%d], found [%d]"
                            .formatted(id, expectedVersion, contact.getVersion()));
        }

        contactRepository.delete(contact);
    }

    private void validateContact(ContactCreateRequest request) {
        if (isContactEmpty(request)) {
            throw new ContactNotValidException(
                    "Provide a first name, last name, or email.");
        }

        if (!validator.validate(request).isEmpty()) {
            throw new ContactNotValidException(
                    "Contact fields do not meet the validation requirements.");
        }
    }

    private boolean isContactEmpty(ContactCreateRequest contactRequest) {
        return !StringUtils.hasText(contactRequest.getFirstName()) && !StringUtils.hasText(
                contactRequest.getLastName()) && !StringUtils.hasText(contactRequest.getEmail());
    }
}
