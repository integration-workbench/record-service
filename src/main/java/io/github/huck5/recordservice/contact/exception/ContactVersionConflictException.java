package io.github.huck5.recordservice.contact.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class ContactVersionConflictException extends RuntimeException {
    public ContactVersionConflictException(String message) {super(message);}
}
