package io.github.huck5.recordservice.contact.exception;

public class ContactNotFoundException extends RuntimeException {
    public ContactNotFoundException() {
    }
    public ContactNotFoundException(String message) {
        super(message);
    }
}
