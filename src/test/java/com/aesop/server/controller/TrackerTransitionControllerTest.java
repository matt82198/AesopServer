package com.aesop.server.controller;

import com.aesop.server.dto.TrackerItem;
import com.aesop.server.dto.TrackerTransitionRequest;
import com.aesop.server.service.EventStreamService;
import com.aesop.server.service.InboxService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Arrays;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for tracker transition endpoints (write-path).
 * Validates status codes, auth, inbox format, and transition validation.
 */
@WebMvcTest(TrackerTransitionController.class)
@org.springframework.test.context.ActiveProfiles("test")
class TrackerTransitionControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private EventStreamService eventStreamService;

    @MockBean
    private InboxService inboxService;

    // Must match the test application properties
    private static final String TOKEN = "test-secret-token";

    @Test
    void testPostTransition_ValidTransition_Returns202Accepted() throws Exception {
        // Arrange
        String itemId = "item-1";
        TrackerItem currentItem = new TrackerItem(
            itemId, "Task", "high", "open", "lane1", "db",
            Arrays.asList(), "notes", null,
            Instant.parse("2026-07-26T12:00:00Z"), null
        );

        when(eventStreamService.getTrackerItemById(itemId))
            .thenReturn(currentItem);
        doNothing().when(inboxService)
            .appendTrackerTransition(itemId, "done", "Completed");

        // Act & Assert
        mockMvc.perform(post("/api/v1/tracker/items/{id}/transitions", itemId)
                .header("Authorization", "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new TrackerTransitionRequest("done", "Completed")
                )))
            .andDo(print())
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.source", equalTo("aesop-server")))
            .andExpect(jsonPath("$.kind", equalTo("tracker-transition")))
            .andExpect(jsonPath("$.itemId", equalTo(itemId)))
            .andExpect(jsonPath("$.targetStatus", equalTo("done")))
            .andExpect(jsonPath("$.note", equalTo("Completed")))
            .andExpect(jsonPath("$.ts", notNullValue()));

        verify(inboxService).appendTrackerTransition(itemId, "done", "Completed");
    }

    @Test
    void testPostTransition_MissingTargetStatus_Returns400() throws Exception {
        String itemId = "item-1";

        mockMvc.perform(post("/api/v1/tracker/items/{id}/transitions", itemId)
                .header("Authorization", "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new TrackerTransitionRequest(null, "note")
                )))
            .andDo(print())
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", containsString("targetStatus is required")));
    }

    @Test
    void testPostTransition_ItemNotFound_Returns400() throws Exception {
        String itemId = "nonexistent-item";

        when(eventStreamService.getTrackerItemById(itemId))
            .thenReturn(null);

        mockMvc.perform(post("/api/v1/tracker/items/{id}/transitions", itemId)
                .header("Authorization", "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new TrackerTransitionRequest("done", null)
                )))
            .andDo(print())
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", containsString("Item not found")));
    }

    @Test
    void testPostTransition_SameStatusTransition_Returns400() throws Exception {
        String itemId = "item-1";
        TrackerItem currentItem = new TrackerItem(
            itemId, "Task", "high", "open", "lane1", "db",
            Arrays.asList(), "notes", null,
            Instant.parse("2026-07-26T12:00:00Z"), null
        );

        when(eventStreamService.getTrackerItemById(itemId))
            .thenReturn(currentItem);

        mockMvc.perform(post("/api/v1/tracker/items/{id}/transitions", itemId)
                .header("Authorization", "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new TrackerTransitionRequest("open", null)
                )))
            .andDo(print())
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", containsString("Invalid transition")));
    }

    @Test
    void testPostTransition_WithoutAuthHeader_Returns401() throws Exception {
        String itemId = "item-1";

        mockMvc.perform(post("/api/v1/tracker/items/{id}/transitions", itemId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new TrackerTransitionRequest("done", null)
                )))
            .andDo(print())
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error", containsString("authentication token")));
    }

    @Test
    void testPostTransition_WithWrongToken_Returns401() throws Exception {
        String itemId = "item-1";

        mockMvc.perform(post("/api/v1/tracker/items/{id}/transitions", itemId)
                .header("Authorization", "Bearer wrong-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new TrackerTransitionRequest("done", null)
                )))
            .andDo(print())
            .andExpect(status().isUnauthorized());
    }

    @Test
    void testPostTransition_NoteFieldOptional() throws Exception {
        String itemId = "item-1";
        TrackerItem currentItem = new TrackerItem(
            itemId, "Task", "high", "open", "lane1", "db",
            Arrays.asList(), "notes", null,
            Instant.parse("2026-07-26T12:00:00Z"), null
        );

        when(eventStreamService.getTrackerItemById(itemId))
            .thenReturn(currentItem);
        doNothing().when(inboxService)
            .appendTrackerTransition(itemId, "done", null);

        mockMvc.perform(post("/api/v1/tracker/items/{id}/transitions", itemId)
                .header("Authorization", "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new TrackerTransitionRequest("done", null)
                )))
            .andDo(print())
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.note").doesNotExist());

        verify(inboxService).appendTrackerTransition(itemId, "done", null);
    }
}
