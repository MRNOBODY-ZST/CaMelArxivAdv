package com.camel_hub.advertisement.identity.service;

public record AuthenticationRequestContext(String ipAddress, String userAgentSummary, String traceId) {
}
