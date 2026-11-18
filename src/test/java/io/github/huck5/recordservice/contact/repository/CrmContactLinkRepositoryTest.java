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
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CrmContactLinkRepositoryTest {
    @Container @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4");

    @Autowired private CrmConnectionRepository connections;
    @Autowired private CrmContactLinkRepository links;
    @Autowired private ContactRepository contacts;
    private CrmConnection salesforce;
    private CrmConnection hubspot;

    @BeforeEach
    void clearImports() {
        links.deleteAllInBatch();
        contacts.deleteAllInBatch();
        salesforce = connections.findByCrmSource(CrmSource.SALESFORCE).orElseThrow();
        hubspot = connections.findByCrmSource(CrmSource.HUBSPOT).orElseThrow();
    }

    @Test
    void externalIdLookupIsScopedToConnection() {
        links.saveAndFlush(new CrmContactLink(salesforce, savedContact(), "shared-id"));

        assertTrue(links.existsByConnectionAndExternalId(salesforce, "shared-id"));
        assertFalse(links.existsByConnectionAndExternalId(salesforce, "missing"));
        assertFalse(links.existsByConnectionAndExternalId(hubspot, "shared-id"));

        links.saveAndFlush(new CrmContactLink(hubspot, savedContact(), "shared-id"));
        assertTrue(links.existsByConnectionAndExternalId(hubspot, "shared-id"));
        assertEquals(2, links.count());
    }

    @Test
    void databaseRejectsDuplicateExternalIdentity() {
        links.saveAndFlush(new CrmContactLink(salesforce, savedContact(), "same-id"));
        CrmContactLink duplicate = new CrmContactLink(salesforce, savedContact(), "same-id");

        assertThrows(DataIntegrityViolationException.class, () -> links.saveAndFlush(duplicate));
        assertEquals(1, links.count());
    }

    @Test
    void localContactCanHaveOnlyOneLinkPerConnection() {
        Contact contact = savedContact();
        links.saveAndFlush(new CrmContactLink(salesforce, contact, "first-id"));
        CrmContactLink duplicate = new CrmContactLink(salesforce, contact, "second-id");

        assertThrows(DataIntegrityViolationException.class, () -> links.saveAndFlush(duplicate));
        assertEquals(1, links.count());
    }

    private Contact savedContact() {
        return contacts.saveAndFlush(new Contact("Bob", "Dole", "bob@example.com"));
    }
}
