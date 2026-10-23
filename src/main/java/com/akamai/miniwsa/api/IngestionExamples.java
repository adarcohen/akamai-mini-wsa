package com.akamai.miniwsa.api;

/** Compile-time constants used by the OpenAPI example annotations. */
final class IngestionExamples {
    private IngestionExamples() {}

    static final String SINGLE_EVENT = """
            {
              "eventId": "evt-docs-001",
              "timestamp": "2026-05-20T14:32:10Z",
              "configId": 14227,
              "policyId": "pol_web1",
              "clientIp": "203.0.113.42",
              "hostname": "www.example.com",
              "path": "/api/v1/login",
              "method": "POST",
              "statusCode": 403,
              "userAgent": "Mozilla/5.0",
              "rule": {
                "id": "950001",
                "name": "SQL_INJECTION",
                "message": "SQL Injection Attack Detected",
                "severity": "CRITICAL",
                "category": "INJECTION"
              },
              "action": "DENY",
              "geoLocation": {"country": "CN", "city": "Beijing"},
              "requestSize": 1024,
              "responseSize": 256
            }
            """;

    static final String BATCH = "[" + SINGLE_EVENT + "]";
}
