package io.github.huck5.recordservice.contact.entity;

import io.github.huck5.recordservice.CrmSource;
import jakarta.persistence.*;

@Entity
@Table(name = "crm_connections")
public class CrmConnection {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Enumerated(EnumType.STRING)
    @Column(name = "crm_source", nullable = false)
    private CrmSource crmSource;
    @Column(name = "crm_account_id", length = 100)
    private String crmAccountId;

    public String getCrmAccountId() {
        return crmAccountId;
    }

}
