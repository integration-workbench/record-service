package io.github.huck5.recordservice.contact.repository;

import io.github.huck5.recordservice.CrmSource;
import io.github.huck5.recordservice.contact.entity.CrmConnection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CrmConnectionRepository extends JpaRepository<CrmConnection, Long> {

    Optional<CrmConnection> findByCrmSource(CrmSource crmSource);
}
