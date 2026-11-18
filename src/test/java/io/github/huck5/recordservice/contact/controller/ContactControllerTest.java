package io.github.huck5.recordservice.contact.controller;

import io.github.huck5.recordservice.CrmSource;
import io.github.huck5.recordservice.contact.dto.ContactCreateRequest;
import io.github.huck5.recordservice.contact.dto.ContactImportResponse;
import io.github.huck5.recordservice.contact.entity.Contact;
import io.github.huck5.recordservice.contact.exception.ContactNotFoundException;
import io.github.huck5.recordservice.contact.exception.ContactNotValidException;
import io.github.huck5.recordservice.contact.exception.ContactVersionConflictException;
import io.github.huck5.recordservice.contact.exception.ImportJobFailedException;
import io.github.huck5.recordservice.contact.exception.ImportRequestFailedException;
import io.github.huck5.recordservice.contact.service.ContactImportJobService;
import io.github.huck5.recordservice.contact.service.ContactCrudService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.dao.OptimisticLockingFailureException;

import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

@WebMvcTest(ContactController.class)
class ContactControllerTest {
    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private ContactImportJobService contactImportJobService;
    @MockitoBean
    private ContactCrudService contactCrudService;

    @Test
    void getContacts_returnsContactPage() throws Exception {
        Pageable pageable = PageRequest.of(0, 25, Sort.by("id"));
        when(contactCrudService.getContacts(pageable)).thenReturn(
                new PageImpl<>(List.of(new Contact("Bob", "Dole", "bob@example.com")), pageable, 1));

        mvc.perform(get("/contacts"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.contacts").isArray())
           .andExpect(jsonPath("$.contacts[0].email").value("bob@example.com"));
    }

    @Test
    void createContact_bindsRequestAndReturnsContactResponse() throws Exception {
        Contact savedContact = mock(Contact.class);
        when(savedContact.getId()).thenReturn(42L);
        when(savedContact.getFirstName()).thenReturn("Bob");
        when(savedContact.getLastName()).thenReturn("Dole");
        when(savedContact.getEmail()).thenReturn("bob@example.com");
        when(savedContact.getVersion()).thenReturn(0L);
        when(contactCrudService.insertContact(any(ContactCreateRequest.class)))
                .thenReturn(savedContact);

        mvc.perform(post("/contacts")
                   .contentType(MediaType.APPLICATION_JSON)
                   .content("""
                           {
                             "firstName": "Bob",
                             "lastName": "Dole",
                             "email": "bob@example.com"
                           }
                           """))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.id").value(42))
           .andExpect(jsonPath("$.firstName").value("Bob"))
           .andExpect(jsonPath("$.lastName").value("Dole"))
           .andExpect(jsonPath("$.email").value("bob@example.com"))
           .andExpect(jsonPath("$.version").value(0));

        ArgumentCaptor<ContactCreateRequest> captor =
                ArgumentCaptor.forClass(ContactCreateRequest.class);
        verify(contactCrudService).insertContact(captor.capture());

        ContactCreateRequest request = captor.getValue();
        assertEquals("Bob", request.getFirstName());
        assertEquals("Dole", request.getLastName());
        assertEquals("bob@example.com", request.getEmail());
    }

    @Test
    void updateContact_bindsRequestAndReturnsUpdatedContact() throws Exception {
        Contact updated = mock(Contact.class);
        when(updated.getId()).thenReturn(42L);
        when(updated.getFirstName()).thenReturn("Robert");
        when(updated.getLastName()).thenReturn("Smith");
        when(updated.getEmail()).thenReturn("new@example.com");
        when(updated.getVersion()).thenReturn(3L);
        when(contactCrudService.updateContact(eq(42L), any(ContactCreateRequest.class), eq(2L)))
                .thenReturn(updated);

        mvc.perform(putContact().queryParam("expectedVersion", "2"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.id").value(42))
           .andExpect(jsonPath("$.firstName").value("Robert"))
           .andExpect(jsonPath("$.lastName").value("Smith"))
           .andExpect(jsonPath("$.email").value("new@example.com"))
           .andExpect(jsonPath("$.version").value(3));

        ArgumentCaptor<ContactCreateRequest> captor =
                ArgumentCaptor.forClass(ContactCreateRequest.class);
        verify(contactCrudService).updateContact(eq(42L), captor.capture(), eq(2L));

        ContactCreateRequest request = captor.getValue();
        assertEquals("Robert", request.getFirstName());
        assertEquals("Smith", request.getLastName());
        assertEquals("new@example.com", request.getEmail());
    }

    @Test
    void updateContact_whenExpectedVersionIsMissing_returnsBadRequest() throws Exception {
        mvc.perform(putContact())
           .andExpect(status().isBadRequest());

        verifyNoInteractions(contactCrudService);
    }

    @Test
    void updateContact_whenExpectedVersionIsNotNumeric_returnsBadRequest() throws Exception {
        mvc.perform(putContact().queryParam("expectedVersion", "abc"))
           .andExpect(status().isBadRequest());

        verifyNoInteractions(contactCrudService);
    }

    @Test
    void updateContact_whenContactIsMissing_returnsNotFound() throws Exception {
        when(contactCrudService.updateContact(eq(42L), any(ContactCreateRequest.class), eq(2L)))
                .thenThrow(new ContactNotFoundException("Contact 42 not found"));

        mvc.perform(putContact().queryParam("expectedVersion", "2"))
           .andExpect(status().isNotFound());
    }

    @Test
    void updateContact_whenVersionConflicts_returnsConflict() throws Exception {
        when(contactCrudService.updateContact(eq(42L), any(ContactCreateRequest.class), eq(2L)))
                .thenThrow(new ContactVersionConflictException("Contact 42 version mismatch"));

        mvc.perform(putContact().queryParam("expectedVersion", "2"))
           .andExpect(status().isConflict());
    }

    @Test
    void deleteContact_whenOptimisticLockingFails_returnsConflict() throws Exception {
        doThrow(new OptimisticLockingFailureException("Contact changed during delete"))
                .when(contactCrudService).deleteContact(42L, 2L);

        mvc.perform(delete("/contacts/{id}", 42)
                   .queryParam("expectedVersion", "2"))
           .andExpect(status().isConflict())
           .andExpect(content().string(""));
    }

    @Test
    void getContact_whenContactExists_returnsFieldsAndVersion() throws Exception {
        Contact contact = mock(Contact.class);
        when(contact.getId()).thenReturn(42L);
        when(contact.getFirstName()).thenReturn("Bob");
        when(contact.getLastName()).thenReturn("Dole");
        when(contact.getEmail()).thenReturn("bob@example.com");
        when(contact.getVersion()).thenReturn(3L);
        when(contactCrudService.getContact(42L)).thenReturn(contact);

        mvc.perform(get("/contacts/{id}", 42))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.id").value(42))
           .andExpect(jsonPath("$.firstName").value("Bob"))
           .andExpect(jsonPath("$.lastName").value("Dole"))
           .andExpect(jsonPath("$.email").value("bob@example.com"))
           .andExpect(jsonPath("$.version").value(3));
    }

    @Test
    void getContact_whenContactIsMissing_returnsNotFound() throws Exception {
        when(contactCrudService.getContact(99L))
                .thenThrow(new ContactNotFoundException("Could not find Contact 99"));

        mvc.perform(get("/contacts/{id}", 99))
           .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @MethodSource("invalidContactBodies")
    void createContact_whenFieldIsInvalid_returnsBadRequest(String body) throws Exception {
        mvc.perform(post("/contacts")
                   .contentType(MediaType.APPLICATION_JSON)
                   .content(body))
           .andExpect(status().isBadRequest());

        verifyNoInteractions(contactCrudService);
    }

    @ParameterizedTest
    @MethodSource("invalidContactBodies")
    void updateContact_whenFieldIsInvalid_returnsBadRequest(String body) throws Exception {
        mvc.perform(put("/contacts/{id}", 42)
                   .queryParam("expectedVersion", "1")
                   .contentType(MediaType.APPLICATION_JSON)
                   .content(body))
           .andExpect(status().isBadRequest());

        verifyNoInteractions(contactCrudService);
    }

    @Test
    void createContact_whenServiceRejectsBlankDetails_returnsBadRequest() throws Exception {
        when(contactCrudService.insertContact(any(ContactCreateRequest.class)))
                .thenThrow(new ContactNotValidException(
                        "Provide a first name, last name, or email."));

        mvc.perform(post("/contacts")
                   .contentType(MediaType.APPLICATION_JSON)
                   .content("{}"))
           .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @EnumSource(CrmSource.class)
    void importContacts_returnsImportSummary(CrmSource source) throws Exception {
        ContactImportResponse response = ContactImportResponse.builder()
                                                              .source(source)
                                                              .fetched(15)
                                                              .imported(5)
                                                              .skipped(4)
                                                              .invalidExternalId(3)
                                                              .invalidContact(2)
                                                              .failed(1)
                                                              .build();

        when(contactImportJobService.importContacts(source)).thenReturn(response);

        mvc.perform(post("/contacts/imports")
                   .queryParam("source", source.name()))
           .andExpect(status().isOk())
           .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
           .andExpect(jsonPath("$.source").value(source.name()))
           .andExpect(jsonPath("$.fetched").value(15))
           .andExpect(jsonPath("$.imported").value(5))
           .andExpect(jsonPath("$.skipped").value(4))
           .andExpect(jsonPath("$.invalidExternalId").value(3))
           .andExpect(jsonPath("$.invalidContact").value(2))
           .andExpect(jsonPath("$.failed").value(1))
           .andExpect(jsonPath("$.hasMore").doesNotExist())
           .andExpect(jsonPath("$.continuationToken").doesNotExist())
           .andExpect(jsonPath("$.totalAvailable").doesNotExist());

        verify(contactImportJobService).importContacts(source);
        verifyNoInteractions(contactCrudService);
    }

    @Test
    void importContacts_whenSourceIsMissing_returnsBadRequest() throws Exception {
        mvc.perform(post("/contacts/imports"))
           .andExpect(status().isBadRequest());

        verifyNoInteractions(contactImportJobService, contactCrudService);
    }

    @Test
    void importContacts_whenSourceIsUnsupported_returnsBadRequest() throws Exception {
        mvc.perform(post("/contacts/imports")
                   .queryParam("source", "UNSUPPORTED_CRM"))
           .andExpect(status().isBadRequest());

        verifyNoInteractions(contactImportJobService, contactCrudService);
    }

    @ParameterizedTest
    @MethodSource("importFailures")
    void importContacts_whenJobFails_returnsProblemDetail(
            Throwable cause, int expectedStatus, String expectedDetail) throws Exception {
        var failure = new ImportJobFailedException("Internal diagnostic message", cause);
        // Additional failures are diagnostics; they must not override the primary failure.
        failure.addSuppressed(new HttpTimeoutException("Suppressed timeout"));
        when(contactImportJobService.importContacts(CrmSource.SALESFORCE)).thenThrow(failure);

        mvc.perform(post("/contacts/imports").queryParam("source", "SALESFORCE"))
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andExpect(jsonPath("$.title").value("Contact import failed"))
                .andExpect(jsonPath("$.detail").value(expectedDetail))
                .andExpect(jsonPath("$.instance").value("/contacts/imports"))
                .andExpect(jsonPath("$.fetched").doesNotExist());
    }

    private static Stream<Arguments> importFailures() {
        return Stream.of(
                Arguments.of(new ExecutionException(new ImportRequestFailedException("Upstream failed",
                                new HttpTimeoutException("Timeout"))), 504,
                        "Contact retrieval timed out; the import did not complete."),
                Arguments.of(new ExecutionException(new ImportRequestFailedException("Upstream failed",
                                new IOException("Connection refused"))), 502,
                        "Contact retrieval failed; the import did not complete."),
                Arguments.of(new ImportRequestFailedException("Interrupted",
                                new InterruptedException("Cancelled")), 500,
                        "The contact import was interrupted."),
                Arguments.of(new IllegalStateException("Internal failure"), 500,
                        "The contact import did not complete."),
                Arguments.of(null, 500, "The contact import did not complete."));
    }

    private static Stream<String> invalidContactBodies() {
        return Stream.of(
                "{\"firstName\":\"%s\"}".formatted("a".repeat(51)),
                "{\"lastName\":\"%s\"}".formatted("a".repeat(81)),
                "{\"email\":\"not-an-email\"}"
        );
    }

    private MockHttpServletRequestBuilder putContact() {
        return put("/contacts/{id}", 42)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "firstName": "Robert",
                          "lastName": "Smith",
                          "email": "new@example.com"
                        }
                        """);
    }
}
