package io.github.huck5.recordservice.contact.service;

import io.github.huck5.recordservice.contact.dto.ContactCreateRequest;
import io.github.huck5.recordservice.contact.entity.Contact;
import io.github.huck5.recordservice.contact.exception.ContactNotFoundException;
import io.github.huck5.recordservice.contact.exception.ContactNotValidException;
import io.github.huck5.recordservice.contact.exception.ContactVersionConflictException;
import io.github.huck5.recordservice.contact.repository.ContactRepository;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.http.HttpClient;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContactServiceTest {
    private static final Long EXISTING_CONTACT_VERSION = 1L;

    @Mock
    private ContactRepository repository;
    private ValidatorFactory validatorFactory;
    private ContactService service;

    @BeforeEach
    void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        service = new ContactService(repository, validatorFactory.getValidator());
    }

    @AfterEach
    void tearDown() {
        validatorFactory.close();
    }

    @Test
    void insertContact_mapsRequestAndReturnsSavedContact() {
        ContactCreateRequest request =
                new ContactCreateRequest("Bob", "Dole", "test@test.com");
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
        ContactCreateRequest request =
                new ContactCreateRequest("Robert", "Smith", "new@example.com");

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
        ContactCreateRequest request =
                new ContactCreateRequest("Robert", "Smith", "new@example.com");
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
        ContactCreateRequest request =
                new ContactCreateRequest("Robert", "Smith", "new@example.com");

        when(repository.findById(id)).thenReturn(Optional.of(existing));

        assertThrows(ContactVersionConflictException.class,
                () -> service.updateContact(id, request, incorrectContactVersion));

        assertEquals("Bob", existing.getFirstName());
        assertEquals("Dole", existing.getLastName());
        assertEquals("old@example.com", existing.getEmail());
        verify(repository, never()).save(any());
    }

    @Test
    void getContact_whenRepositoryFindsNothing_throwsContactNotFound() {
        long id = 99L;
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThrows(ContactNotFoundException.class, () -> service.getContact(id));
    }

    @ParameterizedTest
    @MethodSource("requestsWithInvalidFields")
    void insertContact_whenAnnotatedConstraintFails_throwsWithoutSaving(
            ContactCreateRequest request) {

        assertThrows(
                ContactNotValidException.class,
                () -> service.insertContact(request));

        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @MethodSource("requestsWithInvalidFields")
    void updateContact_whenAnnotatedConstraintFails_throwsWithoutAccessingRepository(
            ContactCreateRequest request) {

        assertThrows(
                ContactNotValidException.class,
                () -> service.updateContact(42L, request, EXISTING_CONTACT_VERSION));

        verifyNoInteractions(repository);
    }

    @Test
    void insertContact_whenNamesAreAtMaximumLength_savesContact() {
        String firstName = "a".repeat(50);
        String lastName = "b".repeat(80);

        ContactCreateRequest request =
                new ContactCreateRequest(firstName, lastName, "bob@example.com");

        Contact savedContact =
                new Contact(firstName, lastName, "bob@example.com");

        when(repository.save(any(Contact.class))).thenReturn(savedContact);

        Contact result = service.insertContact(request);

        assertSame(savedContact, result);
        verify(repository).save(any(Contact.class));
    }

    private static Stream<ContactCreateRequest> requestsWithInvalidFields() {
        return Stream.of(
                new ContactCreateRequest(
                        "a".repeat(51), "Dole", "bob@example.com"),
                new ContactCreateRequest(
                        "Bob", "a".repeat(81), "bob@example.com"),
                new ContactCreateRequest(
                        "Bob", "Dole", "not-an-email")
        );
    }
}