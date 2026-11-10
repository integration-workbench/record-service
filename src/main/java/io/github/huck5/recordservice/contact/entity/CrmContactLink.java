package io.github.huck5.recordservice.contact.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "crm_contact_links")
public class CrmContactLink {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "connection_id", nullable = false)
    private CrmConnection connection;

    @ManyToOne(optional = false)
    @JoinColumn(name = "contact_id", nullable = false)
    private Contact contact;

    @Column(name = "external_id", nullable = false, length = 50)
    private String externalId;

    protected CrmContactLink() {
        // Required by JPA
    }

    public CrmContactLink(CrmConnection connection, Contact contact, String externalId) {
        this.connection = connection;
        this.contact = contact;
        this.externalId = externalId;
    }

    public CrmConnection getConnection() {
        return connection;
    }

    public Contact getContact() {
        return contact;
    }

    public String getExternalId() {
        return externalId;
    }
}
