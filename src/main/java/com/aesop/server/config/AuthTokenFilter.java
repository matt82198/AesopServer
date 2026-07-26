package com.aesop.server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;

/**
 * Authentication filter for write-path endpoints (/api/v1/tracker/... POST).
 *
 * Read endpoints are open. Write endpoints require a static bearer token from
 * the AESOP_SERVER_TOKEN environment variable. Token comparison uses
 * constant-time comparison to prevent timing attacks.
 *
 * If AESOP_SERVER_TOKEN is not set, write endpoints return 503 with a
 * "write-path disabled" message (fail-closed).
 */
@Component
public class AuthTokenFilter extends OncePerRequestFilter {
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String TOKEN_HEADER = HttpHeaders.AUTHORIZATION;

    private final String serverToken;

    public AuthTokenFilter(@Value("${aesop.server-token:}") String serverToken) {
        this.serverToken = serverToken;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        // Only validate auth on write endpoints (POST to /api/v1/tracker/...)
        if (isWriteEndpoint(request)) {
            if (!isConfigured()) {
                // Write-path disabled; fail-closed
                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                response.setContentType("application/json");
                response.getWriter().write(
                    "{\"error\":\"write-path disabled: set AESOP_SERVER_TOKEN\"}"
                );
                return;
            }

            if (!validateToken(request)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Invalid or missing authentication token\"}");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Check if this is a write endpoint that requires auth.
     */
    private boolean isWriteEndpoint(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        return "POST".equals(method) && path.startsWith("/api/v1/tracker/");
    }

    /**
     * Check if the server token is configured.
     */
    private boolean isConfigured() {
        return serverToken != null && !serverToken.isEmpty();
    }

    /**
     * Validate the bearer token using constant-time comparison.
     */
    private boolean validateToken(HttpServletRequest request) {
        String authHeader = request.getHeader(TOKEN_HEADER);
        if (authHeader == null) {
            return false;
        }

        if (!authHeader.startsWith(BEARER_PREFIX)) {
            return false;
        }

        String providedToken = authHeader.substring(BEARER_PREFIX.length());
        return constantTimeEquals(providedToken, serverToken);
    }

    /**
     * Constant-time string comparison to prevent timing attacks.
     */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return a == b;
        }

        byte[] aBytes = a.getBytes();
        byte[] bBytes = b.getBytes();

        int result = 0;
        result |= aBytes.length ^ bBytes.length;

        int minLen = Math.min(aBytes.length, bBytes.length);
        for (int i = 0; i < minLen; i++) {
            result |= aBytes[i] ^ bBytes[i];
        }

        return result == 0;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Filter all requests; selectiveness is in doFilterInternal
        return false;
    }
}
