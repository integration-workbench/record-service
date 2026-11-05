package io.github.huck5.recordservice.contact.controller;

import io.github.huck5.recordservice.contact.dto.ContactResponse;
import io.github.huck5.recordservice.contact.dto.CreateContactRequest;
import io.github.huck5.recordservice.contact.entity.Contact;
import io.github.huck5.recordservice.contact.service.ContactService;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
        Contact outbound = contactService.insertContact(contactRequest);

        return toResponse(outbound);
    }

    @PutMapping("/{id}")
    public ContactResponse updateContact(@RequestBody CreateContactRequest contactRequest,
                                         @PathVariable Long id,
                                         @RequestParam("expectedVersion") Long expectedVersion) {
        Contact updated = contactService.updateContact(id, contactRequest, expectedVersion);

        return toResponse(updated);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteContact(@PathVariable Long id, @RequestParam("expectedVersion") Long expectedVersion) {
        contactService.deleteContact(id, expectedVersion);
    }
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Void> handleOptimisticLockConflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    private ContactResponse toResponse(Contact contact) {
        return ContactResponse.builder().id(contact.getId()).firstName(contact.getFirstName()).lastName(
                contact.getLastName()).email(contact.getEmail()).version(contact.getVersion()).build();
    }
}
