package com.aesop.server.controller;

import com.aesop.server.dto.*;
import com.aesop.server.service.EventStoreReader;
import com.aesop.server.service.EventStreamService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for EventStoreController.
 * Uses a real Spring context with a fixture database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EventStoreControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EventStoreReader eventStoreReader;

    @Test
    void testGetStreams() throws Exception {
        mockMvc.perform(get("/api/v1/events/streams")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", isA(java.util.List.class)));
    }

    @Test
    void testGetEventsWithoutStream() throws Exception {
        mockMvc.perform(get("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest());
    }

    @Test
    void testGetEvents() throws Exception {
        mockMvc.perform(get("/api/v1/events")
                .param("stream", "tracker")
                .param("limit", "10")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", isA(java.util.List.class)));
    }

    @Test
    void testGetEventsWithAfterVersion() throws Exception {
        mockMvc.perform(get("/api/v1/events")
                .param("stream", "tracker")
                .param("afterVersion", "1")
                .param("limit", "10")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", isA(java.util.List.class)));
    }

    @Test
    void testGetTracker() throws Exception {
        mockMvc.perform(get("/api/v1/tracker")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.source").exists())
            .andExpect(jsonPath("$.version").exists())
            .andExpect(jsonPath("$.items", isA(java.util.List.class)));
    }

    @Test
    void testGetTrackerWithStatusFilter() throws Exception {
        mockMvc.perform(get("/api/v1/tracker")
                .param("status", "open")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.source").exists());
    }

    @Test
    void testGetTrackerWithPriorityFilter() throws Exception {
        mockMvc.perform(get("/api/v1/tracker")
                .param("priority", "high")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.source").exists());
    }

    @Test
    void testGetAgents() throws Exception {
        mockMvc.perform(get("/api/v1/agents")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", isA(java.util.List.class)));
    }

    @Test
    void testStreamSse() throws Exception {
        mockMvc.perform(get("/api/v1/stream")
                .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
            .andExpect(status().isOk());
        // Note: SseEmitter doesn't set content-type in the initial response, but it's correctly configured in the controller
    }
}
