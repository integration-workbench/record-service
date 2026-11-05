package io.github.huck5.recordservice.contact.repository;

import io.github.huck5.recordservice.contact.entity.Contact;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContactRepository extends JpaRepository<Contact, Long> {
}
