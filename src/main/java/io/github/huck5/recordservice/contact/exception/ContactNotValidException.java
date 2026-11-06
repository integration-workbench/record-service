package io.github.huck5.recordservice.contact.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class ContactNotValidException extends RuntimeException {
    public ContactNotValidException(String message) {
        super(message);
    }
}
