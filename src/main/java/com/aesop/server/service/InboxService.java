package com.aesop.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Service for appending structured requests to the orchestrator inbox.
 * Preserves single-writer discipline by appending to a file instead of
 * directly mutating tracker state.
 *
 * Contract: one JSON object per line, with fields:
 * - ts: ISO-8601 instant
 * - source: "aesop-server"
 * - kind: "tracker-transition" or "note"
 * - itemId: tracker item ID (for transitions)
 * - targetStatus: new status (for transitions)
 * - note: optional note
 *
 * File is written with exclusive lock + fsync for crash-safety.
 */
@Service
public class InboxService {
    private final String inboxPath;
    private final ObjectMapper objectMapper;
    private final ReentrantLock writeLock = new ReentrantLock();

    public InboxService(
        @Value("${aesop.inbox-path:}") String inboxPath,
        ObjectMapper objectMapper
    ) {
        this.inboxPath = inboxPath;
        this.objectMapper = objectMapper;
    }

    /**
     * Append a tracker transition request to the inbox.
     * Returns true on success; throws RuntimeException on failure.
     */
    public void appendTrackerTransition(String itemId, String targetStatus, String note) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("ts", Instant.now().toString());
        request.put("source", "aesop-server");
        request.put("kind", "tracker-transition");
        request.put("itemId", itemId);
        request.put("targetStatus", targetStatus);
        if (note != null && !note.isEmpty()) {
            request.put("note", note);
        }
        appendRequest(request);
    }

    /**
     * Append a generic note to the inbox.
     */
    public void appendNote(String text) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("ts", Instant.now().toString());
        request.put("source", "aesop-server");
        request.put("kind", "note");
        request.put("text", text);
        appendRequest(request);
    }

    /**
     * Append a structured request (JSON object) to the inbox file.
     * Uses a ReentrantLock for in-process synchronization + fsync for crash-safety.
     * Thread-safe for concurrent appends within the same JVM.
     *
     * @param request Map to serialize as JSON
     * @throws RuntimeException if inbox path is not configured or write fails
     */
    private void appendRequest(Map<String, Object> request) {
        if (inboxPath == null || inboxPath.isEmpty()) {
            throw new RuntimeException("Inbox path not configured (AESOP_INBOX_PATH)");
        }

        Path path = Paths.get(inboxPath);

        // Serialize outside the lock
        String json;
        try {
            json = objectMapper.writeValueAsString(request);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize request to JSON: " + e.getMessage(), e);
        }

        // Acquire lock and write atomically
        writeLock.lock();
        try {
            // Ensure parent directory exists
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }

            // Append to file with explicit UTF-8 encoding and fsync for durability
            try (FileOutputStream fos = new FileOutputStream(path.toFile(), true);
                 OutputStreamWriter writer = new OutputStreamWriter(fos, java.nio.charset.StandardCharsets.UTF_8);
                 FileChannel channel = ((FileOutputStream) fos).getChannel()) {

                // Write as single line (LF-terminated)
                writer.write(json);
                writer.write("\n");
                writer.flush();

                // Force sync to disk for crash-safety
                channel.force(true);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to append to inbox: " + e.getMessage(), e);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Check if inbox path is configured.
     */
    public boolean isConfigured() {
        return inboxPath != null && !inboxPath.isEmpty();
    }
}
