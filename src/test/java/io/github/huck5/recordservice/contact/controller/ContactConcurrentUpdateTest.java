package io.github.huck5.recordservice.contact.controller;

import io.github.huck5.recordservice.contact.entity.Contact;
import io.github.huck5.recordservice.contact.repository.ContactRepository;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.util.Locale;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(ContactConcurrentUpdateTest.UpdateBarrierConfiguration.class)
class ContactConcurrentUpdateTest {

    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ContactRepository repository;

    @Test
    void updateContact_whenTwoEditsOverlap_onlyOneSucceeds() throws Exception {
        Contact original = repository.saveAndFlush(
                new Contact("Bob", "Dole", "original@example.com"));
        long id = original.getId();
        long version = original.getVersion();
        String firstEmail = "first@example.com";
        String secondEmail = "second@example.com";

        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> update(id, version, firstEmail));
            var second = executor.submit(() -> update(id, version, secondEmail));

            int firstStatus = first.get(30, SECONDS);
            int secondStatus = second.get(30, SECONDS);

            assertThat(new int[]{firstStatus, secondStatus})
                    .containsExactlyInAnyOrder(200, 409);

            String winningEmail = firstStatus == 200 ? firstEmail : secondEmail;
            Contact stored = repository.findById(id).orElseThrow();
            assertEquals(winningEmail, stored.getEmail());
            assertEquals(version + 1, stored.getVersion());
        } finally {
            executor.shutdownNow();
        }
    }

    private int update(long id, long version, String email) throws Exception {
        return mvc.perform(put("/contacts/{id}", id)
                          .queryParam("expectedVersion", Long.toString(version))
                          .contentType(MediaType.APPLICATION_JSON)
                          .content("""
                                {"firstName":"Bob","lastName":"Dole","email":"%s"}
                                """.formatted(email)))
                  .andReturn()
                  .getResponse()
                  .getStatus();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class UpdateBarrierConfiguration {

        @Bean
        HibernatePropertiesCustomizer coordinateContactUpdates() {
            var barrier = new CyclicBarrier(2);

            StatementInspector inspector = sql -> {
                if (sql.stripLeading().toLowerCase(Locale.ROOT)
                       .startsWith("update contacts ")) {
                    try {
                        barrier.await(15, SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("Contact update was interrupted", e);
                    } catch (BrokenBarrierException | TimeoutException e) {
                        throw new AssertionError("Both updates must reach SQL execution", e);
                    }
                }
                return sql;
            };

            return properties ->
                    properties.put("hibernate.session_factory.statement_inspector", inspector);
        }
    }
}