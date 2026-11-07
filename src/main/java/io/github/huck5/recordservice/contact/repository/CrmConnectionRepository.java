package io.github.huck5.recordservice.contact.repository;

import io.github.huck5.recordservice.contact.entity.CrmConnection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrmConnectionRepository extends JpaRepository<CrmConnection, Long> {
}
