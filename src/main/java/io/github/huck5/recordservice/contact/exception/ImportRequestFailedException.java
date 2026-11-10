package io.github.huck5.recordservice.contact.exception;

public class ImportRequestFailedException extends RuntimeException {
    public ImportRequestFailedException(String message) {
        super(message);
    }
}
