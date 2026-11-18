package io.github.huck5.recordservice.contact.service;

import io.github.huck5.recordservice.contact.dto.ContactCreateRequest;
import io.github.huck5.recordservice.contact.dto.CrmContactDto;
import io.github.huck5.recordservice.contact.entity.Contact;
import io.github.huck5.recordservice.contact.entity.CrmConnection;
import io.github.huck5.recordservice.contact.entity.CrmContactLink;
import io.github.huck5.recordservice.contact.exception.ContactNotValidException;
import io.github.huck5.recordservice.contact.repository.CrmContactLinkRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Service
public class ContactImportPersistenceService {
    private final CrmContactLinkRepository crmContactLinkRepository;
    private final ContactCrudService contactCrudService;
    private final TransactionTemplate perContactTransaction;

    public ContactImportPersistenceService(CrmContactLinkRepository crmContactLinkRepository,
                                           PlatformTransactionManager transactionManager,
                                           ContactCrudService contactCrudService) {
        this.crmContactLinkRepository = crmContactLinkRepository;
        this.contactCrudService = contactCrudService;

        this.perContactTransaction = new TransactionTemplate(transactionManager);

        this.perContactTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public ImportOutcome importContact(CrmConnection connection, CrmContactDto incoming) {
        String externalId = incoming.getExternalId();
        if (!StringUtils.hasText(externalId)) {
            return ImportOutcome.INVALID_EXTERNAL_ID;
        }

        try {
            return perContactTransaction.execute(status -> {
                boolean linkExists = crmContactLinkRepository.existsByConnectionAndExternalId(connection,
                        externalId);

                if (linkExists) {
                    return ImportOutcome.SKIPPED;
                }
                saveContactAndLink(connection, incoming);
                return ImportOutcome.IMPORTED;
            });
        } catch (ContactNotValidException e) {
            return ImportOutcome.INVALID_CONTACT;
        } catch (DataIntegrityViolationException e) {
            return ImportOutcome.FAILED;
        }
    }

    private void saveContactAndLink(CrmConnection connection, CrmContactDto incoming) {
        ContactCreateRequest request = new ContactCreateRequest(
                incoming.getFirstName(),
                incoming.getLastName(),
                incoming.getEmail());

        Contact savedContact = contactCrudService.insertContact(request);

        crmContactLinkRepository.save(
                new CrmContactLink(connection, savedContact, incoming.getExternalId()));
    }
}
