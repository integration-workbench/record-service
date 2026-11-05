package io.github.huck5.recordservice.contact.controller;

import io.github.huck5.recordservice.contact.dto.CreateContactRequest;
import io.github.huck5.recordservice.contact.entity.Contact;
import io.github.huck5.recordservice.contact.service.ContactService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ContactController.class)
class ContactControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ContactService contactService;

    @Test
    void getContacts_returnsJsonArray() throws Exception {
        when(contactService.getContacts()).thenReturn(
                List.of(new Contact("Bob", "Dole", "bob@example.com")));

        mvc.perform(get("/contacts"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$").isArray())
           .andExpect(jsonPath("$[0].email").value("bob@example.com"));
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
}