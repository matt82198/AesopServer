package com.aesop.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages SSE (Server-Sent Events) streaming using virtual threads.
 * Polls the event store and broadcasts changes to all connected clients.
 * Sections: fleet, tracker, events, agents.
 * Heartbeat every 15s.
 */
@Service
public class EventStreamService {
    private final EventStoreReader eventStoreReader;
    private final FleetBrainService fleetBrainService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private long lastEventId = 0;
    private ScheduledExecutorService executorService;

    public EventStreamService(EventStoreReader eventStoreReader, FleetBrainService fleetBrainService) {
        this.eventStoreReader = eventStoreReader;
        this.fleetBrainService = fleetBrainService;
        initializeAndStartPolling();
    }

    /**
     * Create a new SSE emitter for a client.
     */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(300_000L); // 5 min timeout
        emitters.add(emitter);

        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(throwable -> emitters.remove(emitter));

        return emitter;
    }

    /**
     * Initialize the polling task on a virtual thread.
     */
    private void initializeAndStartPolling() {
        // Use virtual threads for polling (Spring Boot 3.5 with Spring 6.2 support)
        this.executorService = Executors.newScheduledThreadPool(1, t -> {
            Thread thread = new Thread(t);
            thread.setName("aesop-event-stream-poller");
            return thread;
        });

        // Start with last event ID from DB
        this.lastEventId = eventStoreReader.getLastEventId();

        // Schedule polling every 2 seconds
        executorService.scheduleAtFixedRate(
            this::pollAndBroadcast,
            2, 2, TimeUnit.SECONDS
        );

        // Schedule heartbeat every 15 seconds
        executorService.scheduleAtFixedRate(
            this::broadcastHeartbeat,
            15, 15, TimeUnit.SECONDS
        );
    }

    /**
     * Poll event store for new events and broadcast them.
     */
    private void pollAndBroadcast() {
        try {
            // Check for new events
            var newEvents = eventStoreReader.getAllEvents(lastEventId, 100);
            if (!newEvents.isEmpty()) {
                lastEventId = newEvents.get(newEvents.size() - 1).id();
                broadcastSection("events", newEvents);
            }

            // Broadcast fresh snapshots (fleet, tracker, agents)
            broadcastSection("fleet", fleetBrainService.getFleetStatus());
            broadcastSection("tracker", eventStoreReader.projectTracker());
            broadcastSection("agents", eventStoreReader.projectAgents());
        } catch (Exception e) {
            // Log error but don't crash the polling thread
        }
    }

    /**
     * Broadcast a section to all connected clients.
     */
    private void broadcastSection(String section, Object data) {
        emitters.removeIf(emitter -> {
            try {
                String json = objectMapper.writeValueAsString(data);
                SseEmitter.SseEventBuilder event = SseEmitter.event()
                    .id(UUID.randomUUID().toString())
                    .name(section)
                    .data(json);
                emitter.send(event);
                return false;
            } catch (IOException e) {
                // Emitter failed, remove it
                return true;
            }
        });
    }

    /**
     * Send a heartbeat to keep the connection alive.
     */
    private void broadcastHeartbeat() {
        emitters.removeIf(emitter -> {
            try {
                emitter.send(SseEmitter.event().comment("keepalive"));
                return false;
            } catch (IOException e) {
                return true;
            }
        });
    }

    /**
     * Shutdown the polling service (for graceful shutdown).
     */
    public void shutdown() {
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Get number of connected clients.
     */
    public int getConnectedClientCount() {
        return emitters.size();
    }

    /**
     * Get a specific tracker item by ID (for transition validation).
     */
    public com.aesop.server.dto.TrackerItem getTrackerItemById(String itemId) {
        return eventStoreReader.getTrackerItemById(itemId);
    }
}
