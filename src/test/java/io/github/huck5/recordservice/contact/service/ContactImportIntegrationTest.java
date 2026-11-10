package io.github.huck5.recordservice.contact.service;

import io.github.huck5.recordservice.CrmSource;
import io.github.huck5.recordservice.contact.dto.ContactImportResponse;
import io.github.huck5.recordservice.contact.repository.ContactRepository;
import io.github.huck5.recordservice.contact.repository.CrmContactLinkRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ContactImportIntegrationTest {
    @Container
    @ServiceConnection
    static final MySQLContainer mysql =
            new MySQLContainer("mysql:8.4");

    @Autowired
    private ContactImportService contactImportService;

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private CrmContactLinkRepository crmContactLinkRepository;

    @MockitoBean
    private HttpClient httpClient;

    @Test
    void importContacts_whenLinkInsertFails_rollsBackContact() throws InterruptedException, IOException {
        long contactsBefore = contactRepository.count();
        long linksBefore = crmContactLinkRepository.count();

        // Mock the upstream HTTP response with valid contact details,
        // but an external ID exceeding the database column's limit.
        stubCrmResponse("""
        {
          "contacts": [
            {
              "source": "SALESFORCE",
              "externalId": "%s",
              "firstName": "Rollback",
              "lastName": "Test",
              "email": "rollback@example.com"
            }
          ],
          "totalAvailable": 1,
          "hasMore": false,
          "continuationToken": null
        }
        """.formatted("x".repeat(51)));

        ContactImportResponse result =
                contactImportService.importContacts(CrmSource.SALESFORCE);

        assertEquals(1, result.getFetched());
        assertEquals(0, result.getImported());
        assertEquals(1, result.getFailed());

        assertEquals(contactsBefore, contactRepository.count());
        assertEquals(linksBefore, crmContactLinkRepository.count());
    }

    private void stubCrmResponse(String body)
            throws IOException, InterruptedException {

        HttpResponse<String> response = mock(HttpResponse.class);

        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(body);

        when(httpClient.send(
                any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(response);
    }
}