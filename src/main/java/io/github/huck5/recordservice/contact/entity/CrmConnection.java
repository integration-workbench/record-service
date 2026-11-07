package io.github.huck5.recordservice.contact.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "crm_connections")
public class CrmConnection {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "crm_source", length = 50)
    private String crmSource;
    @Column(name = "crm_account_id", length = 100)
    private String crmAccountId;


}
