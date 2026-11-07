package io.github.huck5.recordservice.contact.controller;

import io.github.huck5.recordservice.contact.dto.CreateContactRequest;
import io.github.huck5.recordservice.contact.entity.Contact;
import io.github.huck5.recordservice.contact.exception.ContactNotFoundException;
import io.github.huck5.recordservice.contact.exception.ContactNotValidException;
import io.github.huck5.recordservice.contact.exception.ContactVersionConflictException;
import io.github.huck5.recordservice.contact.service.ContactService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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

import java.util.List;
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
    private ContactService contactService;

    @Test
    void getContacts_returnsContactPage() throws Exception {
        Pageable pageable = PageRequest.of(0, 25, Sort.by("id"));
        when(contactService.getContacts(pageable)).thenReturn(
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
        when(contactService.insertContact(any(CreateContactRequest.class)))
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

        ArgumentCaptor<CreateContactRequest> captor =
                ArgumentCaptor.forClass(CreateContactRequest.class);
        verify(contactService).insertContact(captor.capture());

        CreateContactRequest request = captor.getValue();
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
        when(contactService.updateContact(eq(42L), any(CreateContactRequest.class), eq(2L)))
                .thenReturn(updated);

        mvc.perform(putContact().queryParam("expectedVersion", "2"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.id").value(42))
           .andExpect(jsonPath("$.firstName").value("Robert"))
           .andExpect(jsonPath("$.lastName").value("Smith"))
           .andExpect(jsonPath("$.email").value("new@example.com"))
           .andExpect(jsonPath("$.version").value(3));

        ArgumentCaptor<CreateContactRequest> captor =
                ArgumentCaptor.forClass(CreateContactRequest.class);
        verify(contactService).updateContact(eq(42L), captor.capture(), eq(2L));

        CreateContactRequest request = captor.getValue();
        assertEquals("Robert", request.getFirstName());
        assertEquals("Smith", request.getLastName());
        assertEquals("new@example.com", request.getEmail());
    }

    @Test
    void updateContact_whenExpectedVersionIsMissing_returnsBadRequest() throws Exception {
        mvc.perform(putContact())
           .andExpect(status().isBadRequest());

        verifyNoInteractions(contactService);
    }

    @Test
    void updateContact_whenExpectedVersionIsNotNumeric_returnsBadRequest() throws Exception {
        mvc.perform(putContact().queryParam("expectedVersion", "abc"))
           .andExpect(status().isBadRequest());

        verifyNoInteractions(contactService);
    }

    @Test
    void updateContact_whenContactIsMissing_returnsNotFound() throws Exception {
        when(contactService.updateContact(eq(42L), any(CreateContactRequest.class), eq(2L)))
                .thenThrow(new ContactNotFoundException("Contact 42 not found"));

        mvc.perform(putContact().queryParam("expectedVersion", "2"))
           .andExpect(status().isNotFound());
    }

    @Test
    void updateContact_whenVersionConflicts_returnsConflict() throws Exception {
        when(contactService.updateContact(eq(42L), any(CreateContactRequest.class), eq(2L)))
                .thenThrow(new ContactVersionConflictException("Contact 42 version mismatch"));

        mvc.perform(putContact().queryParam("expectedVersion", "2"))
           .andExpect(status().isConflict());
    }

    @Test
    void deleteContact_whenOptimisticLockingFails_returnsConflict() throws Exception {
        doThrow(new OptimisticLockingFailureException("Contact changed during delete"))
                .when(contactService).deleteContact(42L, 2L);

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
        when(contactService.getContact(42L)).thenReturn(contact);

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
        when(contactService.getContact(99L))
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

        verifyNoInteractions(contactService);
    }

    @ParameterizedTest
    @MethodSource("invalidContactBodies")
    void updateContact_whenFieldIsInvalid_returnsBadRequest(String body) throws Exception {
        mvc.perform(put("/contacts/{id}", 42)
                   .queryParam("expectedVersion", "1")
                   .contentType(MediaType.APPLICATION_JSON)
                   .content(body))
           .andExpect(status().isBadRequest());

        verifyNoInteractions(contactService);
    }

    @Test
    void createContact_whenServiceRejectsBlankDetails_returnsBadRequest() throws Exception {
        when(contactService.insertContact(any(CreateContactRequest.class)))
                .thenThrow(new ContactNotValidException(
                        "Provide a first name, last name, or email."));

        mvc.perform(post("/contacts")
                   .contentType(MediaType.APPLICATION_JSON)
                   .content("{}"))
           .andExpect(status().isBadRequest());
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