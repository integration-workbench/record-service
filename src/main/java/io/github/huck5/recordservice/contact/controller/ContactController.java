package io.github.huck5.recordservice.contact.controller;

import io.github.huck5.recordservice.CrmSource;
import io.github.huck5.recordservice.contact.dto.ContactCreateRequest;
import io.github.huck5.recordservice.contact.dto.ContactDto;
import io.github.huck5.recordservice.contact.dto.ContactImportResponse;
import io.github.huck5.recordservice.contact.dto.ContactResponse;
import io.github.huck5.recordservice.contact.entity.Contact;
import io.github.huck5.recordservice.contact.exception.ImportJobFailedException;
import io.github.huck5.recordservice.contact.exception.ImportRequestFailedException;
import io.github.huck5.recordservice.contact.service.ContactCrudService;
import io.github.huck5.recordservice.contact.service.ContactImportJobService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobRestartException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.http.HttpTimeoutException;

@RestController
@RequestMapping("/contacts")
public class ContactController {
    private static final Logger log = LoggerFactory.getLogger(ContactController.class);
    private final ContactCrudService contactCrudService;
    private final ContactImportJobService contactImportJobService;

    public ContactController(ContactCrudService contactCrudService, ContactImportJobService contactImportJobService) {
        this.contactCrudService = contactCrudService;
        this.contactImportJobService = contactImportJobService;
    }

    @PostMapping("/imports")
    public ContactImportResponse importContact(@RequestParam CrmSource source) throws JobInstanceAlreadyCompleteException, InvalidJobParametersException, JobExecutionAlreadyRunningException, JobRestartException {
        return contactImportJobService.importContacts(source);
    }

    @GetMapping("/{id}")
    public ContactDto getContact(@PathVariable Long id) {
        return toDto(contactCrudService.getContact(id));
    }

    @GetMapping
    public ContactResponse getContacts(@PageableDefault(page = 0, size = 25, sort = "id") Pageable pageable) {
        Page<ContactDto> contacts = contactCrudService.getContacts(pageable).map(this::toDto);

        return ContactResponse.from(contacts);
    }

    @PostMapping
    public ContactDto createContact(@Valid @RequestBody ContactCreateRequest contactRequest) {
        Contact outbound = contactCrudService.insertContact(contactRequest);

        return toDto(outbound);
    }

    @PutMapping("/{id}")
    public ContactDto updateContact(@Valid @RequestBody ContactCreateRequest contactRequest,
                                    @PathVariable Long id,
                                    @RequestParam("expectedVersion") Long expectedVersion) {
        return toDto(contactCrudService.updateContact(id, contactRequest, expectedVersion));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteContact(@PathVariable Long id, @RequestParam("expectedVersion") Long expectedVersion) {
        contactCrudService.deleteContact(id, expectedVersion);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Void> handleOptimisticLockConflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    @ExceptionHandler(ImportJobFailedException.class)
    public ProblemDetail handleImportFailure(ImportJobFailedException exception) {
        Throwable mainFailure = exception.getCause();

        HttpStatus failedStatus;
        String failedDetail;

        if (hasCause(mainFailure, HttpTimeoutException.class)) {
            failedStatus = HttpStatus.GATEWAY_TIMEOUT;
            failedDetail = "Contact retrieval timed out; the import did not complete.";
        } else if (hasCause(mainFailure, InterruptedException.class)) {
            failedStatus = HttpStatus.INTERNAL_SERVER_ERROR;
            failedDetail = "The contact import was interrupted.";
        } else if (hasCause(mainFailure, ImportRequestFailedException.class)) {
            failedStatus = HttpStatus.BAD_GATEWAY;
            failedDetail = "Contact retrieval failed; the import did not complete.";
        } else {
            failedStatus = HttpStatus.INTERNAL_SERVER_ERROR;
            failedDetail = "The contact import did not complete.";
        }
        log.error("Contact import failed", exception);

        ProblemDetail detail =
                ProblemDetail.forStatusAndDetail(failedStatus, failedDetail);

        detail.setTitle("Contact import failed");

        return detail;
    }

    private ContactDto toDto(Contact contact) {
        return ContactDto.builder().id(contact.getId()).firstName(contact.getFirstName()).lastName(
                contact.getLastName()).email(contact.getEmail()).version(contact.getVersion()).build();
    }

    private boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }
}
