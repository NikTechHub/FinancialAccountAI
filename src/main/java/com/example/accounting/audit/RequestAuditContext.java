package com.example.accounting.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Best-effort HTTP request attributes for audit records. Returns nulls
 * outside HTTP request threads (Kafka workers, tests, background jobs).
 * Only the remote address and user agent are captured — never headers
 * carrying credentials.
 */
public final class RequestAuditContext {

    private RequestAuditContext() {
    }

    public static String ipAddress() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
        }
        return request.getRemoteAddr();
    }

    public static String userAgent() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        String agent = request.getHeader("User-Agent");
        if (agent != null && agent.length() > 500) {
            return agent.substring(0, 500);
        }
        return agent;
    }

    public static String endpoint() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        return request.getMethod() + " " + request.getRequestURI();
    }

    private static HttpServletRequest currentRequest() {
        try {
            var attributes = RequestContextHolder.getRequestAttributes();
            if (attributes instanceof ServletRequestAttributes servlet) {
                return servlet.getRequest();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
