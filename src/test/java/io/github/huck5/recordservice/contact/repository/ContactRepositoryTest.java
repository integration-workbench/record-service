package io.github.huck5.recordservice.contact.repository;

import io.github.huck5.recordservice.contact.entity.Contact;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.OptimisticLockingFailureException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ContactRepositoryTest {

    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4");

    @Autowired
    private ContactRepository repository;

    @Test
    void saveThenFindById_returnsPersistedContact() {
        Contact saved = repository.save(
                new Contact("Bob", "Dole", "bob@example.com"));

        Long id = saved.getId();
        assertNotNull(id);

        Contact reloaded = repository.findById(id).orElseThrow();

        assertEquals("Bob", reloaded.getFirstName());
        assertEquals("Dole", reloaded.getLastName());
        assertEquals("bob@example.com", reloaded.getEmail());
        assertEquals(0L, reloaded.getVersion());
    }

    @Test
    void save_whenVersionIsStale_rejectsUpdateAndPreservesNewerValues() {
        Long id = repository.save(
                new Contact("Bob", "Dole", "bob@example.com")).getId();

        Contact firstCopy = repository.findById(id).orElseThrow();
        Contact staleCopy = repository.findById(id).orElseThrow();
        long originalVersion = firstCopy.getVersion();

        firstCopy.setEmail("new@example.com");
        repository.saveAndFlush(firstCopy);

        staleCopy.setEmail("stale@example.com");
        assertThrows(OptimisticLockingFailureException.class,
                () -> repository.saveAndFlush(staleCopy));

        Contact reloaded = repository.findById(id).orElseThrow();
        assertEquals("new@example.com", reloaded.getEmail());
        assertEquals(originalVersion + 1, reloaded.getVersion());
    }
}