package com.akamai.miniwsa.api;

import java.util.List;

public class InvalidEventsException extends RuntimeException {
    private final List<FieldError> errors;

    public InvalidEventsException(List<FieldError> errors) {
        super("Event validation failed");
        this.errors = List.copyOf(errors);
    }

    public List<FieldError> errors() {
        return errors;
    }

    public record FieldError(String field, String message) {}
}
