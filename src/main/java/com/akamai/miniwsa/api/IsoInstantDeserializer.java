package com.akamai.miniwsa.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.springframework.boot.jackson.JsonComponent;

/** Requires a timestamp string with an offset, instead of accepting numeric epochs. */
@JsonComponent
public class IsoInstantDeserializer extends StdDeserializer<Instant> {
    public IsoInstantDeserializer() {
        super(Instant.class);
    }

    @Override
    public Instant deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (!parser.hasToken(JsonToken.VALUE_STRING)) {
            return context.reportInputMismatch(Instant.class, "timestamp must be an ISO-8601 string");
        }
        try {
            return Instant.parse(parser.getText());
        } catch (DateTimeParseException ex) {
            return context.reportInputMismatch(Instant.class, "timestamp must include a valid ISO-8601 date, time and offset");
        }
    }
}
