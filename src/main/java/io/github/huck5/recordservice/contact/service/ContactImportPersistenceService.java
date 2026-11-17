package io.github.huck5.recordservice.contact.service;

import io.github.huck5.recordservice.contact.dto.ContactCreateRequest;
import io.github.huck5.recordservice.contact.dto.CrmContactDto;
import io.github.huck5.recordservice.contact.entity.Contact;
import io.github.huck5.recordservice.contact.entity.CrmConnection;
import io.github.huck5.recordservice.contact.entity.CrmContactLink;
import io.github.huck5.recordservice.contact.repository.CrmContactLinkRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ContactImportPersistenceService {
    private final CrmContactLinkRepository crmContactLinkRepository;
    private final TransactionTemplate transactionTemplate;
    private final ContactCrudService contactCrudService;

    public ContactImportPersistenceService(CrmContactLinkRepository crmContactLinkRepository,
                                           TransactionTemplate transactionTemplate,
                                           ContactCrudService contactCrudService) {
        this.crmContactLinkRepository = crmContactLinkRepository;
        this.transactionTemplate = transactionTemplate;
        this.contactCrudService = contactCrudService;
    }

    public void saveContactAndLink(CrmConnection connection, CrmContactDto incoming) {
        transactionTemplate.executeWithoutResult(status -> {
            ContactCreateRequest request = new ContactCreateRequest(
                    incoming.getFirstName(),
                    incoming.getLastName(),
                    incoming.getEmail());

            Contact savedContact = contactCrudService.insertContact(request);

            crmContactLinkRepository.save(new CrmContactLink(connection, savedContact, incoming.getExternalId()));
        });
    }
}
