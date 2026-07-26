package com.aesop.server.controller;

import com.aesop.server.dto.*;
import com.aesop.server.service.EventStoreReader;
import com.aesop.server.service.EventStreamService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * J2 event-store read API:
 * - GET /api/v1/events/streams — list streams
 * - GET /api/v1/events — read events from a stream
 * - GET /api/v1/tracker — read tracker projection
 * - GET /api/v1/agents — read agent lifecycle projection
 * - GET /api/v1/stream — SSE tail (virtual threads)
 */
@RestController
@RequestMapping("/api/v1")
public class EventStoreController {
    private final EventStoreReader eventStoreReader;
    private final EventStreamService eventStreamService;

    public EventStoreController(EventStoreReader eventStoreReader, EventStreamService eventStreamService) {
        this.eventStoreReader = eventStoreReader;
        this.eventStreamService = eventStreamService;
    }

    /**
     * List all event streams with metadata.
     */
    @GetMapping("/events/streams")
    public ResponseEntity<List<StreamInfo>> getStreams() {
        return ResponseEntity.ok(eventStoreReader.getStreams());
    }

    /**
     * Read events from a stream.
     * Query params:
     *   stream: stream name (required)
     *   afterVersion: start after this version (optional, null = from start)
     *   limit: max events (optional, default 100, capped at 500)
     */
    @GetMapping("/events")
    public ResponseEntity<List<Event>> getEvents(
        @RequestParam(required = false) String stream,
        @RequestParam(required = false) Integer afterVersion,
        @RequestParam(required = false, defaultValue = "100") Integer limit
    ) {
        if (stream == null || stream.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(eventStoreReader.getEvents(stream, afterVersion, limit));
    }

    /**
     * Read tracker projection from event store.
     * Falls back gracefully if DB unavailable.
     */
    @GetMapping("/tracker")
    public ResponseEntity<TrackerSnapshot> getTracker(
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String priority
    ) {
        TrackerSnapshot snapshot = eventStoreReader.projectTracker();

        // Simple filtering if requested
        if (status != null && !status.isBlank()) {
            List<TrackerItem> filtered = snapshot.items().stream()
                .filter(item -> status.equals(item.status()))
                .toList();
            snapshot = new TrackerSnapshot(snapshot.source(), snapshot.version(), filtered);
        }

        if (priority != null && !priority.isBlank()) {
            List<TrackerItem> filtered = snapshot.items().stream()
                .filter(item -> priority.equals(item.priority()))
                .toList();
            snapshot = new TrackerSnapshot(snapshot.source(), snapshot.version(), filtered);
        }

        return ResponseEntity.ok(snapshot);
    }

    /**
     * Read agent lifecycle projections from event store.
     */
    @GetMapping("/agents")
    public ResponseEntity<List<AgentLifecycle>> getAgents() {
        return ResponseEntity.ok(eventStoreReader.projectAgents());
    }

    /**
     * SSE endpoint: subscribe to event stream.
     * Emits sections: fleet, tracker, events, agents.
     * Heartbeat every 15s.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return eventStreamService.subscribe();
    }
}
