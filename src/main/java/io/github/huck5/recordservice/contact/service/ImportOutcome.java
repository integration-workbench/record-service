package io.github.huck5.recordservice.contact.service;

public enum ImportOutcome {
    IMPORTED,
    SKIPPED,
    INVALID_EXTERNAL_ID,
    INVALID_CONTACT,
    FAILED
}