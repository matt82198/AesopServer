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
     * Uses exclusive file lock + fsync for crash-safety.
     * Retries on lock contention to handle concurrent appends.
     *
     * @param request Map to serialize as JSON
     * @throws RuntimeException if inbox path is not configured or write fails
     */
    private void appendRequest(Map<String, Object> request) {
        if (inboxPath == null || inboxPath.isEmpty()) {
            throw new RuntimeException("Inbox path not configured (AESOP_INBOX_PATH)");
        }

        Path path = Paths.get(inboxPath);
        int maxRetries = 10;
        int retryDelayMs = 10;

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                // Ensure parent directory exists
                Files.createDirectories(path.getParent());

                // Open file for append with exclusive lock + fsync
                try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw");
                     FileChannel channel = raf.getChannel()) {

                    // Try to acquire exclusive lock (blocking)
                    var lock = channel.lock();

                    try {
                        // Seek to end of file
                        raf.seek(raf.length());

                        // Serialize and write as single line (LF-terminated)
                        String json = objectMapper.writeValueAsString(request);
                        raf.writeBytes(json + "\n");

                        // Force sync to disk
                        channel.force(true);
                        return;  // Success
                    } finally {
                        lock.release();
                    }
                }
            } catch (IOException e) {
                if (attempt < maxRetries - 1) {
                    try {
                        Thread.sleep(retryDelayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted while appending to inbox", ie);
                    }
                } else {
                    throw new RuntimeException("Failed to append to inbox after " + maxRetries + " attempts: " + e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Check if inbox path is configured.
     */
    public boolean isConfigured() {
        return inboxPath != null && !inboxPath.isEmpty();
    }
}
