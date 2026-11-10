CREATE TABLE crm_connections
(
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    crm_source     VARCHAR(50)  NOT NULL,
    crm_account_id VARCHAR(100) NOT NULL,

    CONSTRAINT uq_crm_account
        UNIQUE (crm_source, crm_account_id)
);

CREATE TABLE crm_mapping_table
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    connection_id BIGINT      NOT NULL,
    contact_id    BIGINT      NOT NULL,
    external_id   VARCHAR(50) NOT NULL,

    CONSTRAINT fk_mapping_connection
        FOREIGN KEY (connection_id) REFERENCES crm_connections (id),

    CONSTRAINT fk_contact
        FOREIGN KEY (contact_id) REFERENCES contacts (id),

    CONSTRAINT uq_external_contact
        UNIQUE (connection_id, external_id),

    CONSTRAINT uq_local_contact_connection
    UNIQUE (contact_id, connection_id)
);
