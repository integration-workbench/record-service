package io.github.huck5.recordservice.contact.exception;

public class ImportJobFailedException extends RuntimeException {
    public ImportJobFailedException(String message, Throwable cause) {
        super(message,cause);
    }
}
