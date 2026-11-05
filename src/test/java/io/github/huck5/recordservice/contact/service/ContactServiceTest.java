package io.github.huck5.recordservice.contact.service;

import io.github.huck5.recordservice.contact.dto.CreateContactRequest;
import io.github.huck5.recordservice.contact.entity.Contact;
import io.github.huck5.recordservice.contact.exception.ContactVersionConflictException;
import io.github.huck5.recordservice.contact.repository.ContactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import io.github.huck5.recordservice.contact.exception.ContactNotFoundException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContactServiceTest {
    private static final Long EXISTING_CONTACT_VERSION = 1L;

    @Mock
    private ContactRepository repository;

    private ContactService service;

    @BeforeEach
    void setUp() {
        service = new ContactService(repository);
    }

    @Test
    void insertContact_mapsRequestAndReturnsSavedContact() {
        CreateContactRequest request =
                new CreateContactRequest("Bob", "Dole", "test@test.com");
        Contact savedContact = mock(Contact.class);
        when(repository.save(any(Contact.class))).thenReturn(savedContact);

        Contact result = service.insertContact(request);

        ArgumentCaptor<Contact> captor = ArgumentCaptor.forClass(Contact.class);
        verify(repository).save(captor.capture());

        Contact submitted = captor.getValue();
        assertEquals("Bob", submitted.getFirstName());
        assertEquals("Dole", submitted.getLastName());
        assertEquals("test@test.com", submitted.getEmail());
        assertSame(savedContact, result);
    }

    @Test
    void updateContact_whenContactExists_updatesFieldsAndReturnsSavedContact() {
        long id = 42L;
        Contact existing = new Contact("Bob", "Dole", "old@example.com", EXISTING_CONTACT_VERSION);
        CreateContactRequest request =
                new CreateContactRequest("Robert", "Smith", "new@example.com");

        Contact savedContact = mock(Contact.class);
        when(repository.findById(id)).thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(savedContact);

        Contact result = service.updateContact(id, request, EXISTING_CONTACT_VERSION);

        assertEquals("Robert", existing.getFirstName());
        assertEquals("Smith", existing.getLastName());
        assertEquals("new@example.com", existing.getEmail());
        assertSame(savedContact, result);
    }

    @Test
    void updateContact_whenContactIsMissing_throwsAndDoesNotSave() {
        long id = 42L;
        CreateContactRequest request =
                new CreateContactRequest("Robert", "Smith", "new@example.com");
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThrows(ContactNotFoundException.class,
                () -> service.updateContact(id, request, EXISTING_CONTACT_VERSION));

        verify(repository, never()).save(any(Contact.class));
    }

    @Test
    void updateContact_whenExpectedVersionDoesNotMatch_throwsAndDoesNotSave() {
        long id = 42L;
        long incorrectContactVersion = EXISTING_CONTACT_VERSION + 1;
        Contact existing = new Contact("Bob", "Dole", "old@example.com", EXISTING_CONTACT_VERSION);
        CreateContactRequest request =
                new CreateContactRequest("Robert", "Smith", "new@example.com");

        when(repository.findById(id)).thenReturn(Optional.of(existing));

        assertThrows(ContactVersionConflictException.class,
                () -> service.updateContact(id, request, incorrectContactVersion));

        assertEquals("Bob", existing.getFirstName());
        assertEquals("Dole", existing.getLastName());
        assertEquals("old@example.com", existing.getEmail());
        verify(repository, never()).save(any());
    }
}