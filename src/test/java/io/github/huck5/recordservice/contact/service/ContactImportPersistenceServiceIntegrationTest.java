package io.github.huck5.recordservice.contact.service;

import io.github.huck5.recordservice.CrmSource;
import io.github.huck5.recordservice.contact.dto.CrmContactDto;
import io.github.huck5.recordservice.contact.entity.Contact;
import io.github.huck5.recordservice.contact.entity.CrmConnection;
import io.github.huck5.recordservice.contact.entity.CrmContactLink;
import io.github.huck5.recordservice.contact.repository.ContactRepository;
import io.github.huck5.recordservice.contact.repository.CrmConnectionRepository;
import io.github.huck5.recordservice.contact.repository.CrmContactLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.util.stream.Stream;

import static io.github.huck5.recordservice.support.ContactImportFixtures.contact;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ContactImportPersistenceServiceIntegrationTest {
    @Container @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4");

    @Autowired private ContactImportPersistenceService persistence;
    @Autowired private ContactRepository contacts;
    @Autowired private CrmContactLinkRepository links;
    @Autowired private CrmConnectionRepository connections;
    @Autowired private PlatformTransactionManager transactionManager;
    private CrmConnection connection;

    @BeforeEach
    void clearImports() {
        links.deleteAllInBatch();
        contacts.deleteAllInBatch();
        connection = connections.findByCrmSource(CrmSource.SALESFORCE).orElseThrow();
    }

    @Test
    void createsContactAndLinkThenSkipsDuplicateWithoutOverwritingLocalEdits() {
        assertEquals(ImportOutcome.IMPORTED, persistence.importContact(connection, contact("one")));
        CrmContactLink link = links.findAll().getFirst();
        assertEquals(connection.getId(), link.getConnection().getId());
        assertEquals("one", link.getExternalId());
        Contact saved = contacts.findById(link.getContact().getId()).orElseThrow();
        assertEquals("Bob", saved.getFirstName());
        assertEquals("Dole", saved.getLastName());
        assertEquals("bob@example.com", saved.getEmail());
        saved.setEmail("local-edit@example.com");
        contacts.saveAndFlush(saved);

        assertEquals(ImportOutcome.SKIPPED, persistence.importContact(connection, contact("one")));

        assertEquals(1, contacts.count());
        assertEquals(1, links.count());
        assertEquals("local-edit@example.com", contacts.findById(saved.getId()).orElseThrow().getEmail());
    }

    @ParameterizedTest
    @MethodSource("invalidContacts")
    void invalidRecordDoesNotWriteAnything(CrmContactDto incoming, ImportOutcome expected) {
        assertEquals(expected, persistence.importContact(connection, incoming));

        assertEquals(0, contacts.count());
        assertEquals(0, links.count());
    }

    @Test
    void failedLinkInsertRollsBackContactAndNextRecordCanStillCommit() {
        // Valid contact fields, but external_id exceeds the real MySQL column limit.
        assertEquals(ImportOutcome.FAILED, persistence.importContact(connection, contact("x".repeat(51))));
        assertEquals(0, contacts.count(), "Rejected link left an orphan contact");
        assertEquals(0, links.count());

        assertEquals(ImportOutcome.IMPORTED, persistence.importContact(connection, contact("healthy")));
        assertEquals(1, contacts.count());
        assertEquals(1, links.count());
    }

    @Test
    void perContactTransactionsSeeEarlierCommitsAndSurviveOuterRollback() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            // Establish an outer REPEATABLE_READ snapshot before the independent writes.
            assertEquals(0, links.count());
            assertEquals(ImportOutcome.IMPORTED, persistence.importContact(connection, contact("one")));
            assertEquals(ImportOutcome.SKIPPED, persistence.importContact(connection, contact("one")));
            status.setRollbackOnly();
        });

        assertEquals(1, contacts.count());
        assertEquals(1, links.count());
    }

    private static Stream<Arguments> invalidContacts() {
        return Stream.of(
                Arguments.of(contact(null), ImportOutcome.INVALID_EXTERNAL_ID),
                Arguments.of(contact(" "), ImportOutcome.INVALID_EXTERNAL_ID),
                Arguments.of(CrmContactDto.builder().externalId("invalid")
                        .email("not-an-email").build(), ImportOutcome.INVALID_CONTACT));
    }
}
