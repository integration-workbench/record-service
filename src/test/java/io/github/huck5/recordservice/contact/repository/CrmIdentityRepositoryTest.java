package io.github.huck5.recordservice.contact.repository;

import io.github.huck5.recordservice.CrmSource;
import io.github.huck5.recordservice.contact.entity.Contact;
import io.github.huck5.recordservice.contact.entity.CrmConnection;
import io.github.huck5.recordservice.contact.entity.CrmContactLink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CrmIdentityRepositoryTest {

    @Container
    @ServiceConnection
    static final MySQLContainer mysql =
            new MySQLContainer("mysql:8.4");

    @Autowired
    private CrmConnectionRepository connectionRepository;

    @Autowired
    private CrmContactLinkRepository linkRepository;

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private CrmConnection salesforce;
    private CrmConnection hubspot;

    @BeforeEach
    void setUp() {
        // Only clears this class's disposable Testcontainers database.
        // Delete children before parents to respect foreign keys.
        linkRepository.deleteAllInBatch();
        contactRepository.deleteAllInBatch();
        connectionRepository.deleteAllInBatch();

        salesforce = saveConnection(
                CrmSource.SALESFORCE, "salesforce-account");

        hubspot = saveConnection(
                CrmSource.HUBSPOT, "hubspot-account");
    }

    @Test
    void saveConnection_thenReload_preservesFields() {
        CrmConnection reloaded = connectionRepository
                .findById(salesforce.getId())
                .orElseThrow();

        assertAll(
                () -> assertNotNull(reloaded.getId()),
                () -> assertNotSame(salesforce, reloaded),
                () -> assertEquals(
                        salesforce.getId(), reloaded.getId()),
                () -> assertEquals(
                        CrmSource.SALESFORCE, reloaded.getCrmSource()),
                () -> assertEquals(
                        "salesforce-account", reloaded.getCrmAccountId())
        );
    }

    @Test
    void saveLink_thenReload_preservesIdentityAndRelationships() {
        Contact contact = saveContact("reloaded@example.com");

        CrmContactLink saved = saveLink(
                salesforce, contact, "external-001");

        CrmContactLink reloaded = linkRepository
                .findById(saved.getId())
                .orElseThrow();

        assertAll(
                () -> assertNotNull(reloaded.getId()),
                () -> assertNotSame(saved, reloaded),
                () -> assertEquals(
                        saved.getId(), reloaded.getId()),
                () -> assertEquals(
                        "external-001", reloaded.getExternalId()),
                () -> assertEquals(
                        salesforce.getId(),
                        reloaded.getConnection().getId()),
                () -> assertEquals(
                        CrmSource.SALESFORCE,
                        reloaded.getConnection().getCrmSource()),
                () -> assertEquals(
                        contact.getId(),
                        reloaded.getContact().getId()),
                () -> assertEquals(
                        "reloaded@example.com",
                        reloaded.getContact().getEmail())
        );
    }

    @Test
    void findByCrmSource_returnsMatchingConnection() {
        CrmConnection foundSalesforce = connectionRepository
                .findByCrmSource(CrmSource.SALESFORCE)
                .orElseThrow();

        CrmConnection foundHubspot = connectionRepository
                .findByCrmSource(CrmSource.HUBSPOT)
                .orElseThrow();

        assertAll(
                () -> assertEquals(
                        salesforce.getId(), foundSalesforce.getId()),
                () -> assertEquals(
                        hubspot.getId(), foundHubspot.getId())
        );
    }

    @Test
    void findByCrmSource_whenMissing_returnsEmpty() {
        connectionRepository.deleteById(hubspot.getId());

        assertTrue(connectionRepository
                .findByCrmSource(CrmSource.HUBSPOT)
                .isEmpty());
    }

    @Test
    void findByCrmSource_whenMultipleAccountsExist_rejectsAmbiguousLookup() {
        saveConnection(
                CrmSource.SALESFORCE, "second-salesforce-account");

        assertThrows(
                IncorrectResultSizeDataAccessException.class,
                () -> connectionRepository.findByCrmSource(
                        CrmSource.SALESFORCE));
    }

    @Test
    void findByConnectionAndExternalIdIn_returnsOnlyRequestedIdsForConnection() {
        CrmContactLink first = saveLink(
                salesforce,
                saveContact("first@example.com"),
                "external-001");

        CrmContactLink second = saveLink(
                salesforce,
                saveContact("second@example.com"),
                "external-002");

        // Same connection, but not one of the requested external IDs.
        saveLink(
                salesforce,
                saveContact("excluded@example.com"),
                "external-003");

        // Requested external ID, but belongs to another connection.
        saveLink(
                hubspot,
                saveContact("other-provider@example.com"),
                "external-001");

        List<CrmContactLink> found =
                linkRepository.findByConnectionAndExternalIdIn(
                        salesforce,
                        List.of(
                                "external-001",
                                "external-002",
                                "missing"));

        Set<Long> foundIds = found.stream()
                                  .map(CrmContactLink::getId)
                                  .collect(Collectors.toSet());

        assertEquals(2, found.size());
        assertEquals(Set.of(first.getId(), second.getId()), foundIds);
    }

    @Test
    void findByConnectionAndExternalIdIn_whenNoMatches_returnsEmpty() {
        saveLink(
                salesforce,
                saveContact("existing@example.com"),
                "existing");

        List<CrmContactLink> found =
                linkRepository.findByConnectionAndExternalIdIn(
                        salesforce, List.of("missing"));

        assertTrue(found.isEmpty());
    }

    @Test
    void saveConnections_withSameSourceAndAccount_rejectsDuplicate() {
        CrmConnection duplicate = new CrmConnection(
                CrmSource.SALESFORCE, salesforce.getCrmAccountId());

        assertThrows(
                DataIntegrityViolationException.class,
                () -> connectionRepository.saveAndFlush(duplicate));

        assertEquals(2L, connectionRepository.count());

        CrmConnection existing = connectionRepository
                .findById(salesforce.getId())
                .orElseThrow();

        assertEquals(
                "salesforce-account", existing.getCrmAccountId());
    }

    @Test
    void saveConnections_withSameAccountIdInDifferentProviders_allowsBoth() {
        CrmConnection otherProvider = saveConnection(
                CrmSource.HUBSPOT, salesforce.getCrmAccountId());

        CrmConnection reloaded = connectionRepository
                .findById(otherProvider.getId())
                .orElseThrow();

        assertAll(
                () -> assertNotEquals(
                        salesforce.getId(), reloaded.getId()),
                () -> assertEquals(
                        CrmSource.HUBSPOT, reloaded.getCrmSource()),
                () -> assertEquals(
                        salesforce.getCrmAccountId(),
                        reloaded.getCrmAccountId())
        );
    }

    @Test
    void saveLinks_withSameExternalIdInDifferentAccounts_allowsBoth() {
        CrmConnection secondSalesforce = saveConnection(
                CrmSource.SALESFORCE, "second-salesforce-account");

        CrmContactLink first = saveLink(
                salesforce,
                saveContact("first-account@example.com"),
                "shared-external-id");

        CrmContactLink second = saveLink(
                secondSalesforce,
                saveContact("second-account@example.com"),
                "shared-external-id");

        List<CrmContactLink> firstAccountLinks =
                linkRepository.findByConnectionAndExternalIdIn(
                        salesforce, List.of("shared-external-id"));

        List<CrmContactLink> secondAccountLinks =
                linkRepository.findByConnectionAndExternalIdIn(
                        secondSalesforce, List.of("shared-external-id"));

        assertAll(
                () -> assertNotEquals(first.getId(), second.getId()),
                () -> assertEquals(1, firstAccountLinks.size()),
                () -> assertEquals(1, secondAccountLinks.size()),
                () -> assertEquals(
                        first.getId(), firstAccountLinks.getFirst().getId()),
                () -> assertEquals(
                        second.getId(), secondAccountLinks.getFirst().getId())
        );
    }

    @Test
    void saveLinks_withSameConnectionAndExternalId_rejectsDuplicate() {
        Contact firstContact = saveContact("first@example.com");
        Contact secondContact = saveContact("second@example.com");

        CrmContactLink existing = saveLink(
                salesforce, firstContact, "duplicate-external-id");

        CrmContactLink duplicate = new CrmContactLink(
                salesforce, secondContact, "duplicate-external-id");

        assertThrows(
                DataIntegrityViolationException.class,
                () -> linkRepository.saveAndFlush(duplicate));

        assertEquals(1L, linkRepository.count());

        CrmContactLink reloaded = linkRepository
                .findById(existing.getId())
                .orElseThrow();

        assertEquals(
                firstContact.getId(), reloaded.getContact().getId());
    }

    @Test
    void saveLinks_withSameContactAndConnection_rejectsSecondExternalId() {
        Contact contact = saveContact("same-contact@example.com");

        CrmContactLink existing = saveLink(
                salesforce, contact, "first-external-id");

        CrmContactLink duplicate = new CrmContactLink(
                salesforce, contact, "second-external-id");

        assertThrows(
                DataIntegrityViolationException.class,
                () -> linkRepository.saveAndFlush(duplicate));

        assertEquals(1L, linkRepository.count());

        CrmContactLink reloaded = linkRepository
                .findById(existing.getId())
                .orElseThrow();

        assertEquals("first-external-id", reloaded.getExternalId());
    }

    @Test
    void saveLinks_forSameContactInDifferentConnections_allowsBoth() {
        Contact contact = saveContact("shared-local@example.com");

        CrmContactLink salesforceLink = saveLink(
                salesforce, contact, "salesforce-external-id");

        CrmContactLink hubspotLink = saveLink(
                hubspot, contact, "hubspot-external-id");

        assertEquals(2L, linkRepository.count());

        CrmContactLink reloadedSalesforce = linkRepository
                .findById(salesforceLink.getId())
                .orElseThrow();

        CrmContactLink reloadedHubspot = linkRepository
                .findById(hubspotLink.getId())
                .orElseThrow();

        assertAll(
                () -> assertEquals(
                        contact.getId(),
                        reloadedSalesforce.getContact().getId()),
                () -> assertEquals(
                        contact.getId(),
                        reloadedHubspot.getContact().getId()),
                () -> assertNotEquals(
                        reloadedSalesforce.getConnection().getId(),
                        reloadedHubspot.getConnection().getId())
        );
    }

    @Test
    void insertLink_withMissingContact_rejectsForeignKeyViolation() {
        long missingContactId = -1L;
        assertFalse(contactRepository.existsById(missingContactId));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertLinkDirectly(
                        salesforce.getId(),
                        missingContactId,
                        "missing-contact"));

        assertEquals(0L, linkRepository.count());
    }

    @Test
    void insertLink_withMissingConnection_rejectsForeignKeyViolation() {
        Contact contact = saveContact("valid@example.com");

        long missingConnectionId = -1L;
        assertFalse(connectionRepository.existsById(missingConnectionId));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertLinkDirectly(
                        missingConnectionId,
                        contact.getId(),
                        "missing-connection"));

        assertEquals(0L, linkRepository.count());
        assertTrue(contactRepository.existsById(contact.getId()));
    }

    private CrmConnection saveConnection(
            CrmSource source, String accountId) {

        return connectionRepository.saveAndFlush(
                new CrmConnection(source, accountId));
    }

    private Contact saveContact(String email) {
        return contactRepository.saveAndFlush(
                new Contact("Test", "Contact", email));
    }

    private CrmContactLink saveLink(
            CrmConnection connection,
            Contact contact,
            String externalId) {

        return linkRepository.saveAndFlush(
                new CrmContactLink(connection, contact, externalId));
    }

    private void insertLinkDirectly(
            Long connectionId,
            Long contactId,
            String externalId) {

        jdbcTemplate.update("""
                INSERT INTO crm_contact_links
                    (connection_id, contact_id, external_id)
                VALUES (?, ?, ?)
                """,
                connectionId,
                contactId,
                externalId);
    }
}