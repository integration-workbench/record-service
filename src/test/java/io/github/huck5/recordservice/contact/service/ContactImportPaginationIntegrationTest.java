package io.github.huck5.recordservice.contact.service;

import io.github.huck5.recordservice.CrmSource;
import io.github.huck5.recordservice.contact.dto.ContactCreateRequest;
import io.github.huck5.recordservice.contact.dto.ContactImportResponse;
import io.github.huck5.recordservice.contact.repository.ContactRepository;
import io.github.huck5.recordservice.contact.repository.CrmContactLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.batch.job.enabled=false",
                "spring.batch.jdbc.initialize-schema=never"
        })
class ContactImportPaginationIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer mysql =
            new MySQLContainer("mysql:8.4");

    @Autowired
    private ContactImportJobService importer;

    @Autowired
    private ContactRepository contacts;

    @Autowired
    private CrmContactLinkRepository links;

    @MockitoBean
    private HttpClient httpClient;

    @MockitoSpyBean
    private ContactCrudService contactCrudService;

    @BeforeEach
    void clearImportedData() {
        links.deleteAll();
        contacts.deleteAll();
    }

    @Test
    void importContacts_whenMultiplePages_importsEveryPage()
            throws Exception {

        stubPages(
                page(false, "page-2", "external-1"),
                page(true, null, "external-2"));

        ContactImportResponse result =
                importer.importContacts(CrmSource.SALESFORCE);

        assertAll(
                () -> assertEquals(2, result.getFetched()),
                () -> assertEquals(2, result.getImported()),
                () -> assertEquals(0, result.getSkipped()),
                () -> assertEquals(0, result.getFailed()),
                () -> assertFalse(result.isHasMore()),
                () -> assertNull(result.getContinuationToken()),
                () -> assertEquals(2, contacts.count()),
                () -> assertEquals(2, links.count())
        );

        verify(httpClient, times(2)).send(
                any(HttpRequest.class),
                any(HttpResponse.BodyHandler.class));
    }

    @Test
    void importContacts_whenTerminalPageIsEmpty_finishesNormally()
            throws Exception {

        stubPages(
                page(false, "page-2", "external-1"),
                page(true, null));

        ContactImportResponse result =
                importer.importContacts(CrmSource.SALESFORCE);

        assertAll(
                () -> assertEquals(1, result.getFetched()),
                () -> assertEquals(1, result.getImported()),
                () -> assertFalse(result.isHasMore()),
                () -> assertNull(result.getContinuationToken()),
                () -> assertEquals(1, contacts.count()),
                () -> assertEquals(1, links.count())
        );

        verify(httpClient, times(2)).send(
                any(HttpRequest.class),
                any(HttpResponse.BodyHandler.class));
    }

    @Test
    void importContacts_whenRecordRepeatsAcrossPages_doesNotDuplicateIt()
            throws Exception {

        stubPages(
                page(false, "page-2", "external-1"),
                page(true, null, "external-1", "external-2"));

        ContactImportResponse result =
                importer.importContacts(CrmSource.SALESFORCE);

        assertAll(
                () -> assertEquals(3, result.getFetched()),
                () -> assertEquals(2, result.getImported()),
                () -> assertEquals(1, result.getSkipped()),
                () -> assertEquals(0, result.getFailed()),
                () -> assertEquals(2, contacts.count()),
                () -> assertEquals(2, links.count())
        );
    }

    @Test
    void importContacts_fetchesNextPageWhileCurrentPageIsBeingPersisted()
            throws Exception {

        CountDownLatch firstWriteStarted = new CountDownLatch(1);
        CountDownLatch secondPageSupplied = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger activeWrites = new AtomicInteger();

        HttpResponse<String> firstPage =
                response(page(false, "page-2", "external-1"));

        HttpResponse<String> secondPage =
                response(page(true, null, "external-2"));

        when(httpClient.send(
                any(HttpRequest.class),
                org.mockito.ArgumentMatchers
                        .<HttpResponse.BodyHandler<String>>any()))
                .thenAnswer(invocation -> {
                    int requestNumber = requests.incrementAndGet();

                    if (requestNumber == 1) {
                        return firstPage;
                    }

                    if (requestNumber == 2) {
                        assertTrue(
                                firstWriteStarted.await(5, SECONDS),
                                "Page 1 persistence never started");

                        secondPageSupplied.countDown();
                        return secondPage;
                    }

                    throw new AssertionError(
                            "Unexpected page request: " + requestNumber);
                });

        doAnswer(invocation -> {
            assertEquals(
                    1,
                    activeWrites.incrementAndGet(),
                    "Contact persistence must remain sequential");

            try {
                ContactCreateRequest request =
                        invocation.getArgument(0);

                if ("external-1".equals(request.getFirstName())) {
                    firstWriteStarted.countDown();

                    assertTrue(
                            secondPageSupplied.await(5, SECONDS),
                            "Page 2 was not fetched while page 1 "
                                    + "persistence was blocked");
                }

                return invocation.callRealMethod();
            } finally {
                activeWrites.decrementAndGet();
            }
        }).when(contactCrudService)
                .insertContact(any(ContactCreateRequest.class));

        ContactImportResponse result =
                importer.importContacts(CrmSource.SALESFORCE);

        assertAll(
                () -> assertEquals(2, requests.get()),
                () -> assertEquals(2, result.getImported()),
                () -> assertEquals(2, contacts.count()),
                () -> assertEquals(2, links.count())
        );
    }

    private void stubPages(String... bodies) throws Exception {
        AtomicInteger nextPage = new AtomicInteger();

        when(httpClient.send(
                any(HttpRequest.class),
                org.mockito.ArgumentMatchers
                        .<HttpResponse.BodyHandler<String>>any()))
                .thenAnswer(invocation -> {
                    int index = nextPage.getAndIncrement();

                    if (index >= bodies.length) {
                        throw new AssertionError(
                                "Requested a page after the terminal page");
                    }

                    return response(bodies[index]);
                });
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(body);
        return response;
    }

    private String page(
            boolean terminal,
            String nextToken,
            String... externalIds) {

        StringBuilder records = new StringBuilder();

        for (String externalId : externalIds) {
            if (!records.isEmpty()) {
                records.append(",");
            }

            records.append("""
                    {
                      "source": "SALESFORCE",
                      "externalId": "%s",
                      "firstName": "%s",
                      "lastName": "Pagination",
                      "email": "%s@example.com"
                    }
                    """.formatted(
                    externalId, externalId, externalId));
        }

        String tokenJson =
                nextToken == null ? "null" : "\"" + nextToken + "\"";

        return """
                {
                  "contacts": [%s],
                  "totalAvailable": null,
                  "hasMore": %s,
                  "continuationToken": %s
                }
                """.formatted(records, !terminal, tokenJson);
    }
}
