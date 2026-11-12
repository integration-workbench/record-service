package io.github.huck5.recordservice.contact.service;

import io.github.huck5.recordservice.CrmSource;
import io.github.huck5.recordservice.contact.dto.ContactCreateRequest;
import io.github.huck5.recordservice.contact.dto.ContactImportResponse;
import io.github.huck5.recordservice.contact.dto.ContactRetrievalResponse;
import io.github.huck5.recordservice.contact.dto.CrmContactDto;
import io.github.huck5.recordservice.contact.entity.Contact;
import io.github.huck5.recordservice.contact.entity.CrmConnection;
import io.github.huck5.recordservice.contact.entity.CrmContactLink;
import io.github.huck5.recordservice.contact.exception.ContactNotValidException;
import io.github.huck5.recordservice.contact.exception.CrmConnectionNotFundException;
import io.github.huck5.recordservice.contact.exception.ImportRequestFailedException;
import io.github.huck5.recordservice.contact.repository.CrmConnectionRepository;
import io.github.huck5.recordservice.contact.repository.CrmContactLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContactImportServiceTest {

    @Mock
    private ContactService contactService;

    @Mock
    private CrmContactLinkRepository linkRepository;

    @Mock
    private CrmConnectionRepository connectionRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<String> httpResponse;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final CrmConnection connection =
            new CrmConnection(CrmSource.SALESFORCE, "test-account");

    private ContactImportService service;

    @BeforeEach
    void setUp() {
        service = new ContactImportService(
                contactService,
                linkRepository,
                connectionRepository,
                transactionManager,
                httpClient,
                jsonMapper);
    }

    @ParameterizedTest
    @EnumSource(CrmSource.class)
    void importContacts_whenContactIsNew_createsContactAndLink(
            CrmSource source) throws Exception {

        givenSuccessfulResponse(source, """
                {
                  "contacts": [
                    {
                      "source": "%s",
                      "externalId": "external-1",
                      "firstName": "Bob",
                      "lastName": "Dole",
                      "email": "bob@example.com"
                    }
                  ],
                  "totalAvailable": 1,
                  "hasMore": false,
                  "continuationToken": null
                }
                """.formatted(source.name()));

        when(linkRepository.findByConnectionAndExternalIdIn(
                same(connection), anyList()))
                .thenReturn(List.of());

        givenTransactions();

        Contact savedContact = new Contact(
                "Bob", "Dole", "bob@example.com");

        when(contactService.insertContact(any(ContactCreateRequest.class)))
                .thenReturn(savedContact);

        ContactImportResponse result = service.importContacts(source);

        assertEquals(source, result.getSource());
        assertCounts(result, 1, 1, 0, 0, 0, 0);

        ArgumentCaptor<HttpRequest> httpCaptor =
                ArgumentCaptor.forClass(HttpRequest.class);

        verify(httpClient).send(
                httpCaptor.capture(),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());

        assertEquals("GET", httpCaptor.getValue().method());
        assertEquals(
                URI.create("http://localhost:8080/%s/contacts"
                        .formatted(source.name().toLowerCase(Locale.ROOT))),
                httpCaptor.getValue().uri());

        verify(linkRepository).findByConnectionAndExternalIdIn(
                connection, List.of("external-1"));

        ArgumentCaptor<ContactCreateRequest> requestCaptor =
                ArgumentCaptor.forClass(ContactCreateRequest.class);

        verify(contactService).insertContact(requestCaptor.capture());

        ContactCreateRequest request = requestCaptor.getValue();
        assertAll(
                () -> assertEquals("Bob", request.getFirstName()),
                () -> assertEquals("Dole", request.getLastName()),
                () -> assertEquals("bob@example.com", request.getEmail())
        );

        ArgumentCaptor<CrmContactLink> linkCaptor =
                ArgumentCaptor.forClass(CrmContactLink.class);

        verify(linkRepository).save(linkCaptor.capture());

        CrmContactLink link = linkCaptor.getValue();
        assertAll(
                () -> assertSame(connection, link.getConnection()),
                () -> assertSame(savedContact, link.getContact()),
                () -> assertEquals("external-1", link.getExternalId())
        );

        verify(transactionManager).commit(any(TransactionStatus.class));
        verify(transactionManager, never())
                .rollback(any(TransactionStatus.class));
    }

    @Test
    void importContacts_whenAlreadyLinked_skipsAndPreservesExistingContact()
            throws Exception {

        Contact existing = new Contact(
                "Local", "Contact", "local@example.com");

        CrmContactLink existingLink =
                new CrmContactLink(connection, existing, "existing-1");

        givenContacts(
                List.of(existingLink),
                contact("existing-1", "remote@example.com"));

        ContactImportResponse result =
                service.importContacts(CrmSource.SALESFORCE);

        assertCounts(result, 1, 0, 1, 0, 0, 0);
        assertNoContactWrites();

        assertAll(
                () -> assertEquals("Local", existing.getFirstName()),
                () -> assertEquals("Contact", existing.getLastName()),
                () -> assertEquals("local@example.com", existing.getEmail())
        );
    }

    @Test
    void importContacts_whenExternalIdRepeats_importsOnlyOnce()
            throws Exception {

        givenContacts(
                List.of(),
                contact("duplicate-1", "first@example.com"),
                contact("duplicate-1", "second@example.com"));

        givenTransactions();
        givenContactCreation();

        ContactImportResponse result =
                service.importContacts(CrmSource.SALESFORCE);

        assertCounts(result, 2, 1, 1, 0, 0, 0);

        ArgumentCaptor<ContactCreateRequest> requestCaptor =
                ArgumentCaptor.forClass(ContactCreateRequest.class);

        verify(contactService).insertContact(requestCaptor.capture());
        assertEquals(
                "first@example.com",
                requestCaptor.getValue().getEmail());

        verify(linkRepository).save(any(CrmContactLink.class));
        verify(transactionManager).commit(any(TransactionStatus.class));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void importContacts_whenExternalIdIsBlank_countsInvalidAndDoesNotSave(
            String externalId) throws Exception {

        givenContacts(
                List.of(),
                contact(externalId, "bob@example.com"));

        ContactImportResponse result =
                service.importContacts(CrmSource.SALESFORCE);

        assertCounts(result, 1, 0, 0, 1, 0, 0);
        assertNoContactWrites();
    }

    @Test
    void importContacts_whenContactIsInvalid_countsItAndContinues()
            throws Exception {

        givenContacts(
                List.of(),
                contact("invalid-1", "not-an-email"),
                contact("valid-1", "valid@example.com"));

        givenTransactions();

        Contact validContact =
                new Contact("Bob", "Dole", "valid@example.com");

        when(contactService.insertContact(any(ContactCreateRequest.class)))
                .thenThrow(new ContactNotValidException("Invalid email"))
                .thenReturn(validContact);

        ContactImportResponse result =
                service.importContacts(CrmSource.SALESFORCE);

        assertCounts(result, 2, 1, 0, 0, 1, 0);

        verify(contactService, times(2))
                .insertContact(any(ContactCreateRequest.class));

        ArgumentCaptor<CrmContactLink> linkCaptor =
                ArgumentCaptor.forClass(CrmContactLink.class);

        verify(linkRepository).save(linkCaptor.capture());

        assertEquals("valid-1", linkCaptor.getValue().getExternalId());
        assertSame(validContact, linkCaptor.getValue().getContact());

        verify(transactionManager).rollback(any(TransactionStatus.class));
        verify(transactionManager).commit(any(TransactionStatus.class));
    }

    @Test
    void importContacts_whenLinkSaveFails_countsFailureAndContinues()
            throws Exception {

        givenContacts(
                List.of(),
                contact("failed-1", "first@example.com"),
                contact("valid-1", "second@example.com"));

        givenTransactions();
        givenContactCreation();

        when(linkRepository.save(any(CrmContactLink.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "Link constraint rejected"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ContactImportResponse result =
                service.importContacts(CrmSource.SALESFORCE);

        assertCounts(result, 2, 1, 0, 0, 0, 1);

        ArgumentCaptor<CrmContactLink> linkCaptor =
                ArgumentCaptor.forClass(CrmContactLink.class);

        verify(linkRepository, times(2)).save(linkCaptor.capture());

        assertEquals(
                List.of("failed-1", "valid-1"),
                linkCaptor.getAllValues().stream()
                        .map(CrmContactLink::getExternalId)
                        .toList());

        verify(transactionManager).rollback(any(TransactionStatus.class));
        verify(transactionManager).commit(any(TransactionStatus.class));
    }

    @Test
    void importContacts_whenCommitFails_doesNotMarkExternalIdAsImported()
            throws Exception {

        givenContacts(
                List.of(),
                contact("same-1", "first@example.com"),
                contact("same-1", "second@example.com"));

        givenTransactions();
        givenContactCreation();

        doThrow(new DataIntegrityViolationException(
                "Constraint failure during commit"))
                .doNothing()
                .when(transactionManager)
                .commit(any(TransactionStatus.class));

        ContactImportResponse result =
                service.importContacts(CrmSource.SALESFORCE);

        // The failed first attempt must not cause the second to be skipped.
        assertCounts(result, 2, 1, 0, 0, 0, 1);

        verify(contactService, times(2))
                .insertContact(any(ContactCreateRequest.class));

        verify(linkRepository, times(2)).save(any(CrmContactLink.class));
        verify(transactionManager, times(2))
                .commit(any(TransactionStatus.class));
    }

    @Test
    void importContacts_whenResponseIsEmpty_returnsZeroCounts()
            throws Exception {

        givenContacts(List.of());

        ContactImportResponse result =
                service.importContacts(CrmSource.SALESFORCE);

        assertCounts(result, 0, 0, 0, 0, 0, 0);
        assertNoContactWrites();
    }

    @Test
    void importContacts_whenOutcomesAreMixed_accountsForEveryContact()
            throws Exception {

        Contact existingContact =
                new Contact("Local", "Contact", "local@example.com");

        CrmContactLink existingLink =
                new CrmContactLink(connection, existingContact, "existing-1");

        givenContacts(
                List.of(existingLink),
                contact("new-1", "new@example.com"),
                contact("new-1", "duplicate@example.com"),
                contact("existing-1", "remote@example.com"),
                contact(" ", "blank-id@example.com"),
                contact("invalid-1", "not-an-email"),
                contact("failed-1", "failed@example.com"));

        givenTransactions();

        when(contactService.insertContact(any(ContactCreateRequest.class)))
                .thenAnswer(invocation -> {
                    ContactCreateRequest request = invocation.getArgument(0);

                    if ("not-an-email".equals(request.getEmail())) {
                        throw new ContactNotValidException("Invalid email");
                    }

                    return new Contact(
                            request.getFirstName(),
                            request.getLastName(),
                            request.getEmail());
                });

        when(linkRepository.save(any(CrmContactLink.class)))
                .thenAnswer(invocation -> {
                    CrmContactLink link = invocation.getArgument(0);

                    if ("failed-1".equals(link.getExternalId())) {
                        throw new DataIntegrityViolationException(
                                "Link constraint rejected");
                    }

                    return link;
                });

        ContactImportResponse result =
                service.importContacts(CrmSource.SALESFORCE);

        assertCounts(result, 6, 1, 2, 1, 1, 1);

        verify(contactService, times(3))
                .insertContact(any(ContactCreateRequest.class));

        verify(linkRepository, times(2)).save(any(CrmContactLink.class));
        verify(transactionManager).commit(any(TransactionStatus.class));
        verify(transactionManager, times(2))
                .rollback(any(TransactionStatus.class));
    }

    @Test
    void importContacts_whenConnectionIsMissing_throwsBeforeHttpRequest() {
        when(connectionRepository.findByCrmSource(CrmSource.SALESFORCE))
                .thenReturn(Optional.empty());

        assertThrows(
                CrmConnectionNotFundException.class,
                () -> service.importContacts(CrmSource.SALESFORCE));

        verifyNoInteractions(
                httpClient,
                linkRepository,
                contactService,
                transactionManager);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 429, 500, 503})
    void importContacts_whenHttpStatusIsNotSuccessful_throwsWithoutSaving(
            int status) throws Exception {

        givenConnection(CrmSource.SALESFORCE);

        when(httpClient.send(
                any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(httpResponse);

        when(httpResponse.statusCode()).thenReturn(status);

        ImportRequestFailedException exception = assertThrows(
                ImportRequestFailedException.class,
                () -> service.importContacts(CrmSource.SALESFORCE));

        assertTrue(exception.getMessage().contains(Integer.toString(status)));

        verifyNoInteractions(
                linkRepository,
                contactService,
                transactionManager);
    }

    @Test
    void importContacts_whenHttpCallFails_preservesCauseAndDoesNotSave()
            throws Exception {

        givenConnection(CrmSource.SALESFORCE);
        IOException cause = new IOException("Connection refused");

        when(httpClient.send(
                any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(cause);

        RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> service.importContacts(CrmSource.SALESFORCE));

        assertSame(cause, exception.getCause());

        verifyNoInteractions(
                linkRepository,
                contactService,
                transactionManager);
    }

    @Test
    void importContacts_whenInterrupted_restoresInterruptFlag()
            throws Exception {

        givenConnection(CrmSource.SALESFORCE);
        InterruptedException cause =
                new InterruptedException("Request interrupted");

        when(httpClient.send(
                any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(cause);

        try {
            RuntimeException exception = assertThrows(
                    RuntimeException.class,
                    () -> service.importContacts(CrmSource.SALESFORCE));

            assertSame(cause, exception.getCause());
            assertTrue(Thread.currentThread().isInterrupted());

            verifyNoInteractions(
                    linkRepository,
                    contactService,
                    transactionManager);
        } finally {
            // Clear the interrupt flag so subsequent tests are unaffected.
            //noinspection ResultOfMethodCallIgnored
            Thread.interrupted();
        }
    }

    @Test
    void importContacts_whenJsonIsMalformed_throwsWithoutSaving()
            throws Exception {

        givenSuccessfulResponse(
                CrmSource.SALESFORCE, "[not valid JSON");

        assertThrows(
                JacksonException.class,
                () -> service.importContacts(CrmSource.SALESFORCE));

        verifyNoInteractions(
                linkRepository,
                contactService,
                transactionManager);
    }

    @Test
    void importContacts_whenDatabaseIsUnavailable_stopsImport()
            throws Exception {

        givenContacts(
                List.of(),
                contact("first-1", "first@example.com"),
                contact("second-1", "second@example.com"));

        givenTransactions();

        DataAccessResourceFailureException cause =
                new DataAccessResourceFailureException(
                        "Database unavailable");

        when(contactService.insertContact(any(ContactCreateRequest.class)))
                .thenThrow(cause);

        DataAccessResourceFailureException exception = assertThrows(
                DataAccessResourceFailureException.class,
                () -> service.importContacts(CrmSource.SALESFORCE));

        assertSame(cause, exception);

        // The second contact must not be attempted after a database outage.
        verify(contactService).insertContact(any(ContactCreateRequest.class));
        verify(linkRepository, never()).save(any(CrmContactLink.class));

        verify(transactionManager).rollback(any(TransactionStatus.class));
        verify(transactionManager, never())
                .commit(any(TransactionStatus.class));
    }

    private void givenConnection(CrmSource source) {
        when(connectionRepository.findByCrmSource(source))
                .thenReturn(Optional.of(connection));
    }

    private void givenSuccessfulResponse(
            CrmSource source, String body) throws Exception {

        givenConnection(source);

        when(httpClient.send(
                any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(httpResponse);

        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(body);
    }

    private void givenContacts(
            List<CrmContactLink> existingLinks,
            CrmContactDto... contacts) throws Exception {

        ContactRetrievalResponse response = new ContactRetrievalResponse(
                Arrays.asList(contacts),
                (long) contacts.length,
                false,
                null);

        givenSuccessfulResponse(
                CrmSource.SALESFORCE,
                jsonMapper.writeValueAsString(response));

        when(linkRepository.findByConnectionAndExternalIdIn(
                same(connection), anyList()))
                .thenReturn(existingLinks);
    }

    private void givenTransactions() {
        when(transactionManager.getTransaction(
                any(TransactionDefinition.class)))
                .thenAnswer(invocation -> mock(TransactionStatus.class));
    }

    private void givenContactCreation() {
        when(contactService.insertContact(any(ContactCreateRequest.class)))
                .thenAnswer(invocation -> {
                    ContactCreateRequest request = invocation.getArgument(0);

                    return new Contact(
                            request.getFirstName(),
                            request.getLastName(),
                            request.getEmail());
                });
    }

    private CrmContactDto contact(String externalId, String email) {
        return CrmContactDto.builder()
                .source(CrmSource.SALESFORCE)
                .externalId(externalId)
                .firstName("Bob")
                .lastName("Dole")
                .email(email)
                .build();
    }

    private void assertNoContactWrites() {
        verifyNoInteractions(contactService, transactionManager);
        verify(linkRepository, never()).save(any(CrmContactLink.class));
    }

    private void assertCounts(
            ContactImportResponse result,
            int fetched,
            int imported,
            int skipped,
            int invalidExternalId,
            int invalidContact,
            int failed) {

        assertAll(
                () -> assertEquals(fetched, result.getFetched(), "fetched"),
                () -> assertEquals(imported, result.getImported(), "imported"),
                () -> assertEquals(skipped, result.getSkipped(), "skipped"),
                () -> assertEquals(
                        invalidExternalId,
                        result.getInvalidExternalId(),
                        "invalidExternalId"),
                () -> assertEquals(
                        invalidContact,
                        result.getInvalidContact(),
                        "invalidContact"),
                () -> assertEquals(failed, result.getFailed(), "failed"),
                () -> assertEquals(
                        result.getFetched(),
                        result.getImported()
                                + result.getSkipped()
                                + result.getInvalidExternalId()
                                + result.getInvalidContact()
                                + result.getFailed(),
                        "Every fetched contact must have exactly one outcome")
        );
    }

    @ParameterizedTest
    @MethodSource("retrievalMetadataCases")
    void importContacts_preservesMetadataForNewAndExistingContacts(
            CrmSource source,
            Long totalAvailable,
            boolean hasMore,
            String continuationToken,
            boolean alreadyLinked) throws Exception {

        CrmContactDto incoming = CrmContactDto.builder()
                .source(source)
                .externalId("external-1")
                .firstName("Maria")
                .lastName("Johnson")
                .email("maria@example.com")
                .build();

        ContactRetrievalResponse retrieval = new ContactRetrievalResponse(
                List.of(incoming),
                totalAvailable,
                hasMore,
                continuationToken);

        givenSuccessfulResponse(
                source,
                jsonMapper.writeValueAsString(retrieval));

        Contact localContact =
                new Contact("Local", "Contact", "local@example.com");

        List<CrmContactLink> existingLinks = alreadyLinked
                ? List.of(new CrmContactLink(
                connection, localContact, "external-1"))
                : List.of();

        when(linkRepository.findByConnectionAndExternalIdIn(
                same(connection), anyList()))
                .thenReturn(existingLinks);

        if (!alreadyLinked) {
            givenTransactions();
            givenContactCreation();
        }

        ContactImportResponse result = service.importContacts(source);

        assertCounts(
                result,
                1,
                alreadyLinked ? 0 : 1,
                alreadyLinked ? 1 : 0,
                0,
                0,
                0);

        assertAll(
                () -> assertEquals(source, result.getSource()),
                () -> assertEquals(
                        totalAvailable, result.getTotalAvailable()),
                () -> assertEquals(hasMore, result.isHasMore()),
                () -> assertEquals(
                        continuationToken, result.getContinuationToken())
        );

        // Fetch only the current page, even when more records exist.
        verify(httpClient).send(
                any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());

        if (alreadyLinked) {
            assertNoContactWrites();
        } else {
            verify(contactService)
                    .insertContact(any(ContactCreateRequest.class));

            verify(linkRepository).save(any(CrmContactLink.class));
        }
    }

    private static Stream<Arguments> retrievalMetadataCases() {
        return Stream.of(
                // First imports: metadata survives successful creation.
                Arguments.of(
                        CrmSource.SALESFORCE,
                        251L,
                        true,
                        "/services/data/v66.0/query/next-page",
                        false),
                Arguments.of(
                        CrmSource.HUBSPOT,
                        null,
                        true,
                        "288431635415",
                        false),

                // Repeat imports: skipping contacts must preserve metadata too.
                Arguments.of(
                        CrmSource.SALESFORCE,
                        251L,
                        true,
                        "/services/data/v66.0/query/next-page",
                        true),
                Arguments.of(
                        CrmSource.HUBSPOT,
                        null,
                        true,
                        "288431635415",
                        true),

                // Completed retrievals: no next page or continuation token.
                Arguments.of(
                        CrmSource.SALESFORCE,
                        1L,
                        false,
                        null,
                        false),
                Arguments.of(
                        CrmSource.HUBSPOT,
                        null,
                        false,
                        null,
                        false)
        );
    }

    @ParameterizedTest
    @EnumSource(CrmSource.class)
    void importContacts_whenEmpty_preservesCoverageWithoutWriting(
            CrmSource source) throws Exception {

        Long totalAvailable =
                source == CrmSource.SALESFORCE ? 0L : null;

        ContactRetrievalResponse retrieval = new ContactRetrievalResponse(
                List.of(), totalAvailable, false, null);

        givenSuccessfulResponse(
                source,
                jsonMapper.writeValueAsString(retrieval));

        when(linkRepository.findByConnectionAndExternalIdIn(
                same(connection), anyList()))
                .thenReturn(List.of());

        ContactImportResponse result = service.importContacts(source);

        assertCounts(result, 0, 0, 0, 0, 0, 0);

        assertAll(
                () -> assertEquals(
                        totalAvailable, result.getTotalAvailable()),
                () -> assertFalse(result.isHasMore()),
                () -> assertNull(result.getContinuationToken())
        );

        assertNoContactWrites();
    }
}