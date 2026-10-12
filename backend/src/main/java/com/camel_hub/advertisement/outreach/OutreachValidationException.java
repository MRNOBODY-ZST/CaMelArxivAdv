package com.camel_hub.advertisement.outreach;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
public final class OutreachValidationException extends ResponseStatusException {
    public OutreachValidationException(String message) { super(HttpStatus.BAD_REQUEST, message); }
}
