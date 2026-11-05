package io.github.huck5.recordservice.contact.service;

import io.github.huck5.recordservice.contact.dto.CreateContactRequest;
import io.github.huck5.recordservice.contact.entity.Contact;
import io.github.huck5.recordservice.contact.exception.ContactNotFoundException;
import io.github.huck5.recordservice.contact.repository.ContactRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ContactService {
    private final ContactRepository repository;

    public ContactService(ContactRepository repository) {
        this.repository = repository;
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
    public Contact updateContact(Long id, CreateContactRequest contactRequest) {
        Contact contact = repository.findById(id).orElseThrow(() ->
                new ContactNotFoundException("Contact: " + id + " not found"));

        contact.setFirstName(contactRequest.getFirstName());
        contact.setLastName(contactRequest.getLastName());
        contact.setEmail(contactRequest.getEmail());

        return repository.save(contact);
    }
}
