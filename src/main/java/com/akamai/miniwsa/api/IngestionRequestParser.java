package com.akamai.miniwsa.api;

import com.akamai.miniwsa.api.InvalidEventsException.FieldError;
import com.akamai.miniwsa.domain.SecurityEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class IngestionRequestParser {
    private final ObjectMapper mapper;
    private final Validator validator;
    private final int maxBatchSize;

    public IngestionRequestParser(ObjectMapper mapper, Validator validator,
                                  @Value("${miniwsa.max-batch-size:1000}") int maxBatchSize) {
        this.mapper = mapper;
        this.validator = validator;
        this.maxBatchSize = maxBatchSize;
    }

    public List<SecurityEvent> parse(JsonNode body) {
        if (body == null || (!body.isObject() && !body.isArray())) {
            throw new IllegalArgumentException("Request must be an event object or an array of events");
        }
        List<JsonNode> nodes = new ArrayList<>();
        if (body.isArray()) {
            if (body.isEmpty() || body.size() > maxBatchSize) {
                throw new IllegalArgumentException("Batch must contain between 1 and " + maxBatchSize + " events");
            }
            body.forEach(nodes::add);
        } else {
            nodes.add(body);
        }
        List<SecurityEvent> events = new ArrayList<>();
        List<FieldError> errors = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < nodes.size(); index++) {
            JsonNode node = nodes.get(index);
            String prefix = "events[" + index + "]";
            if (!node.isObject()) {
                errors.add(new FieldError(prefix, "must be an object"));
                continue;
            }
            if (!validateText(node, prefix, errors)) {
                continue;
            }
            try {
                SecurityEvent event = mapper.treeToValue(node, SecurityEvent.class);
                validator.validate(event).forEach(violation -> errors.add(new FieldError(
                        prefix + "." + violation.getPropertyPath().toString()
                                .replace("clientIpValid", "clientIp").replace("timestampSupported", "timestamp"),
                        violation.getMessage())));
                if (!ids.add(event.eventId())) {
                    errors.add(new FieldError(prefix + ".eventId", "duplicate eventId in batch"));
                }
                events.add(event);
            } catch (JsonProcessingException ex) {
                String field = prefix;
                if (ex instanceof JsonMappingException mapping) {
                    field += mapping.getPath().stream().map(ref -> ref.getFieldName() == null
                            ? "[" + ref.getIndex() + "]" : "." + ref.getFieldName()).reduce("", String::concat);
                }
                errors.add(new FieldError(field, "invalid field, type, enum value or ISO-8601 timestamp"));
            }
        }
        if (!errors.isEmpty()) {
            errors.sort(Comparator.comparing(FieldError::field).thenComparing(FieldError::message));
            throw new InvalidEventsException(errors);
        }
        return List.copyOf(events);
    }

    private boolean validateText(JsonNode node, String field, List<FieldError> errors) {
        if (node.isTextual() && containsInvalidUnicode(node.textValue())) {
            errors.add(new FieldError(field, "must contain valid Unicode without null characters"));
            return false;
        } else if (node.isContainerNode()) {
            boolean valid = true;
            if (node.isObject()) {
                var fields = node.fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    valid &= validateText(entry.getValue(), field + "." + entry.getKey(), errors);
                }
            } else {
                for (int index = 0; index < node.size(); index++) {
                    valid &= validateText(node.get(index), field + "[" + index + "]", errors);
                }
            }
            return valid;
        }
        return true;
    }

    private boolean containsInvalidUnicode(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == 0 || Character.isLowSurrogate(character)) {
                return true;
            }
            if (Character.isHighSurrogate(character)) {
                if (index + 1 == value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return true;
                }
                index++;
            }
        }
        return false;
    }
}
