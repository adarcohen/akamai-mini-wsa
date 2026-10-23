package com.akamai.miniwsa.domain;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** Parses literals only: never resolves attacker-controlled hostnames through DNS. */
public final class ClientIp {
    private ClientIp() {}

    public static boolean isValid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            canonical(value);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public static String canonical(String value) {
        if (value == null || value.length() > 45 || !value.matches("[0-9a-fA-F:.]+")) {
            throw new IllegalArgumentException("clientIp must be an IPv4 or IPv6 literal");
        }
        if (!value.contains(":")) {
            String[] octets = value.split("\\.", -1);
            if (octets.length != 4) {
                throw new IllegalArgumentException("IPv4 requires four octets");
            }
            for (String octet : octets) {
                if (!octet.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(octet) > 255) {
                    throw new IllegalArgumentException("Invalid IPv4 octet");
                }
            }
        }
        try {
            return InetAddress.getByName(value).getHostAddress();
        } catch (UnknownHostException ex) {
            throw new IllegalArgumentException("Invalid IP literal", ex);
        }
    }
}
