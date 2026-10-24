package com.camel_hub.advertisement.outreach;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
public final class OutreachConflictException extends ResponseStatusException {
    public OutreachConflictException(String message) { super(HttpStatus.CONFLICT, message); }
}
