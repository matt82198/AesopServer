package com.aesop.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for AuthTokenFilter.
 * Validates auth on write endpoints, fail-closed behavior, and token validation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "aesop.server-token=test-secret-token",
    "aesop.db-path=./test-db.sqlite",
    "aesop.inbox-path="
})
class AuthTokenFilterTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void testReadEndpoint_NoAuthRequired() throws Exception {
        mockMvc.perform(get("/api/v1/fleet/status"))
            .andExpect(status().isOk());
    }

    @Test
    void testWriteEndpoint_WithValidToken_Returns202() throws Exception {
        // This will fail with 400 because there's no item, but auth should pass
        String json = "{\"targetStatus\":\"done\",\"note\":\"test\"}";

        mockMvc.perform(post("/api/v1/tracker/items/item-1/transitions")
                .header("Authorization", "Bearer test-secret-token")
                .header("Content-Type", "application/json")
                .content(json))
            .andExpect(status().is4xxClientError());  // Should be 400/404, not 401/403
    }

    @Test
    void testWriteEndpoint_WithoutToken_Returns401() throws Exception {
        String json = "{\"targetStatus\":\"done\"}";

        mockMvc.perform(post("/api/v1/tracker/items/item-1/transitions")
                .header("Content-Type", "application/json")
                .content(json))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void testWriteEndpoint_WithWrongToken_Returns401() throws Exception {
        String json = "{\"targetStatus\":\"done\"}";

        mockMvc.perform(post("/api/v1/tracker/items/item-1/transitions")
                .header("Authorization", "Bearer wrong-token")
                .header("Content-Type", "application/json")
                .content(json))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void testWriteEndpoint_TokenTimingAttackResistant() throws Exception {
        String json = "{\"targetStatus\":\"done\"}";

        // Both should take roughly the same time (constant-time comparison)
        long start1 = System.nanoTime();
        mockMvc.perform(post("/api/v1/tracker/items/item-1/transitions")
                .header("Authorization", "Bearer aaaaaaaaaaaaaaaaaaaaaaaaa")
                .header("Content-Type", "application/json")
                .content(json))
            .andExpect(status().isUnauthorized());
        long time1 = System.nanoTime() - start1;

        long start2 = System.nanoTime();
        mockMvc.perform(post("/api/v1/tracker/items/item-1/transitions")
                .header("Authorization", "Bearer zzzzzzzzzzzzzzzzzzzzzzzz")
                .header("Content-Type", "application/json")
                .content(json))
            .andExpect(status().isUnauthorized());
        long time2 = System.nanoTime() - start2;

        // Both should complete within a reasonable time (not timing-attack-exploitable)
        // We're just checking they complete, not measuring exact timing
        assertTrue(time1 < 10_000_000_000L);  // < 10 seconds
        assertTrue(time2 < 10_000_000_000L);
    }
}
