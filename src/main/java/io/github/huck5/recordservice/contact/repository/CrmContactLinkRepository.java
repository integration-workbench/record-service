package io.github.huck5.recordservice.contact.repository;

import io.github.huck5.recordservice.contact.entity.CrmConnection;
import io.github.huck5.recordservice.contact.entity.CrmContactLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface CrmContactLinkRepository extends JpaRepository<CrmContactLink, Long> {
    List<CrmContactLink> findByConnectionAndExternalIdIn(
            CrmConnection connection, Collection<String> externalId);
}
