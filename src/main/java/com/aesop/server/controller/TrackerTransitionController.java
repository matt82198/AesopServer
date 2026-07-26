package com.aesop.server.controller;

import com.aesop.server.dto.TrackerItem;
import com.aesop.server.dto.TrackerTransitionRequest;
import com.aesop.server.service.EventStreamService;
import com.aesop.server.service.InboxService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API for posting tracker mutations (write path).
 * Validates transitions against current projected state, then appends
 * the request to the orchestrator inbox for asynchronous application.
 *
 * All write operations return 202 Accepted (not 200) because the Python
 * orchestrator applies them asynchronously on the next turn.
 */
@RestController
@RequestMapping("/api/v1/tracker")
public class TrackerTransitionController {
    private final EventStreamService eventStreamService;
    private final InboxService inboxService;

    public TrackerTransitionController(
        EventStreamService eventStreamService,
        InboxService inboxService
    ) {
        this.eventStreamService = eventStreamService;
        this.inboxService = inboxService;
    }

    /**
     * POST /api/v1/tracker/items/{id}/transitions
     *
     * Transition a tracker item to a new status.
     * Validates the transition is legal based on current projected state.
     * On accept, appends a structured request to the orchestrator inbox.
     *
     * Request body:
     * {
     *   "targetStatus": "done|open|blocked|...",
     *   "note": "optional context"
     * }
     *
     * Responses:
     * - 202 Accepted: transition queued for asynchronous processing
     * - 400 Bad Request: invalid transition or missing required fields
     * - 401 Unauthorized: missing or invalid authentication token
     * - 503 Service Unavailable: write-path disabled (AESOP_SERVER_TOKEN not set)
     */
    @PostMapping("/items/{id}/transitions")
    public ResponseEntity<?> postTransition(
        @PathVariable String id,
        @RequestBody TrackerTransitionRequest request
    ) {
        // Validate request
        if (request.targetStatus() == null || request.targetStatus().isEmpty()) {
            return ResponseEntity.badRequest().body(
                Map.of("error", "targetStatus is required")
            );
        }

        // Get current item state via projection
        TrackerItem currentItem = eventStreamService.getTrackerItemById(id);
        if (currentItem == null) {
            return ResponseEntity.badRequest().body(
                Map.of("error", "Item not found: " + id)
            );
        }

        // Validate transition is legal
        if (!isValidTransition(currentItem.status(), request.targetStatus())) {
            return ResponseEntity.badRequest().body(
                Map.of(
                    "error", "Invalid transition from " + currentItem.status() +
                             " to " + request.targetStatus()
                )
            );
        }

        // Append to inbox for asynchronous processing
        try {
            inboxService.appendTrackerTransition(
                id,
                request.targetStatus(),
                request.note()
            );
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                Map.of("error", e.getMessage())
            );
        }

        // Return 202 Accepted with the appended request echoed back
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ts", java.time.Instant.now().toString());
        response.put("source", "aesop-server");
        response.put("kind", "tracker-transition");
        response.put("itemId", id);
        response.put("targetStatus", request.targetStatus());
        if (request.note() != null && !request.note().isEmpty()) {
            response.put("note", request.note());
        }

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    /**
     * Validate a state transition.
     * Simple state machine: any status is reachable from any other.
     * More sophisticated validation (e.g., no double-done) can be added here.
     */
    private boolean isValidTransition(String currentStatus, String targetStatus) {
        // For now, allow any transition as long as they're different.
        // The orchestrator can enforce stricter rules.
        if (currentStatus == null || targetStatus == null) {
            return false;
        }
        return !currentStatus.equals(targetStatus);
    }
}
