package io.github.huck5.recordservice.contact.controller;

import io.github.huck5.recordservice.contact.dto.*;
import io.github.huck5.recordservice.contact.entity.Contact;
import io.github.huck5.recordservice.contact.service.ContactImportService;
import io.github.huck5.recordservice.contact.service.ContactService;
import io.github.huck5.recordservice.CrmSource;
import jakarta.validation.Valid;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/contacts")
public class ContactController {
    private final ContactService contactService;
    private final ContactImportService contactImportService;

    public ContactController(ContactService contactService, ContactImportService contactImportService) {
        this.contactService = contactService;
        this.contactImportService = contactImportService;
    }

    @PostMapping("/imports")
    public ContactImportResponse importContact(@RequestParam CrmSource source) {
        return contactImportService.importContacts(source);
    }

    @GetMapping("/{id}")
    public ContactDto getContact(@PathVariable Long id) {
        return toDto(contactService.getContact(id));
    }

    @GetMapping
    public ContactResponse getContacts(@PageableDefault(page = 0, size = 25, sort = "id") Pageable pageable) {
        Page<ContactDto> contacts = contactService.getContacts(pageable).map(this::toDto);

        return ContactResponse.from(contacts);
    }

    @PostMapping
    public ContactDto createContact(@Valid @RequestBody ContactCreateRequest contactRequest) {
        Contact outbound = contactService.insertContact(contactRequest);

        return toDto(outbound);
    }

    @PutMapping("/{id}")
    public ContactDto updateContact(@Valid @RequestBody ContactCreateRequest contactRequest,
                                    @PathVariable Long id,
                                    @RequestParam("expectedVersion") Long expectedVersion) {
        return toDto(contactService.updateContact(id, contactRequest, expectedVersion));
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

    private ContactDto toDto(Contact contact) {
        return ContactDto.builder().id(contact.getId()).firstName(contact.getFirstName()).lastName(
                contact.getLastName()).email(contact.getEmail()).version(contact.getVersion()).build();
    }
}
