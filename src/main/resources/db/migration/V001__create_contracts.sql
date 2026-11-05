CREATE TABLE contacts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    first_name VARCHAR(50),
    last_name VARCHAR(50),
    email VARCHAR(255),
    version BIGINT NOT NULL DEFAULT 0
);