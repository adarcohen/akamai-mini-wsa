package com.akamai.miniwsa.domain;

public enum Category {
    INJECTION("SQL/Command Injection"),
    XSS("Cross-Site Scripting"),
    PROTOCOL_VIOLATION("Protocol Anomaly"),
    DATA_LEAKAGE("Data Exfiltration"),
    BOT("Bot Activity"),
    DOS("Denial of Service"),
    RATE_LIMIT("Rate Limiting");

    private final String attackType;

    Category(String attackType) {
        this.attackType = attackType;
    }

    public String attackType() {
        return attackType;
    }
}
