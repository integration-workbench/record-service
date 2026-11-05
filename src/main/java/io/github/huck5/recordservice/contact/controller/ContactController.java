package io.github.huck5.recordservice.contact.controller;

import io.github.huck5.recordservice.contact.dto.ContactResponse;
import io.github.huck5.recordservice.contact.dto.CreateContactRequest;
import io.github.huck5.recordservice.contact.entity.Contact;
import io.github.huck5.recordservice.contact.service.ContactService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/contacts")
public class ContactController {
    private final ContactService contactService;

    public ContactController(ContactService contactService) {
        this.contactService = contactService;
    }

    @GetMapping
    public List<ContactResponse> getContacts() {
        List<Contact> contacts = contactService.getContacts();
        return contacts.stream().map(this::toResponse).toList();
    }

    @PostMapping
    public ContactResponse createContact(@RequestBody CreateContactRequest contactRequest) {
        Contact outbound = contactService.insertxContact(contactRequest);

        return toResponse(outbound);
    }

    @PutMapping("/{id}")
    public ContactResponse updateContact(@RequestBody CreateContactRequest contactRequest, @PathVariable Long id) {
        Contact updated = contactService.updateContact(id, contactRequest);

        return toResponse(updated);
    }

    private ContactResponse toResponse(Contact contact) {
        return ContactResponse.builder().id(contact.getId()).firstName(contact.getFirstName()).lastName(
                contact.getLastName()).email(contact.getEmail()).version(contact.getVersion()).build();
    }
}
