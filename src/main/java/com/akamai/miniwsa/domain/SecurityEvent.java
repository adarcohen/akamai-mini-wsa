package com.akamai.miniwsa.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record SecurityEvent(
        @NotBlank @Size(max = 128) String eventId,
        @NotNull Instant timestamp,
        @NotNull @Positive Long configId,
        @NotBlank @Size(max = 128) String policyId,
        @NotBlank @Size(max = 45) String clientIp,
        @NotBlank @Size(max = 253) String hostname,
        @NotBlank @Size(max = 4096) String path,
        @NotBlank @Size(max = 16) String method,
        @NotNull @Min(100) @Max(599) Integer statusCode,
        @NotBlank @Size(max = 2048) String userAgent,
        @NotNull @Valid Rule rule,
        @NotNull Action action,
        @NotNull @Valid GeoLocation geoLocation,
        @NotNull @PositiveOrZero Long requestSize,
        @NotNull @PositiveOrZero Long responseSize) {

    @JsonIgnore
    @AssertTrue(message = "must be a valid IPv4 or IPv6 literal")
    public boolean isClientIpValid() {
        return clientIp == null || ClientIp.isValid(clientIp);
    }

    public record Rule(
            @NotBlank @Size(max = 128) String id,
            @NotBlank @Size(max = 128) String name,
            @NotBlank @Size(max = 2048) String message,
            @NotNull Severity severity,
            @NotNull Category category) {}

    public record GeoLocation(
            @NotBlank @Pattern(regexp = "[A-Z]{2}") String country,
            @NotBlank @Size(max = 128) String city) {}
}
