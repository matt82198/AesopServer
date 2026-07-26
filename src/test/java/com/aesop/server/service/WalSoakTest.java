package com.aesop.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WAL multi-process soak test: verify EventStoreReader can handle concurrent
 * reads while a writer appends events.
 *
 * This test spawns a background writer thread that continuously appends events
 * while multiple reader threads query the database. Verifies no crashes, locks,
 * or inconsistencies under contention.
 */
@Tag("soak")
class WalSoakTest {

    private EventStoreReader eventStoreReader;
    private String dbPath;
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setup(@TempDir Path tempDir) throws Exception {
        dbPath = tempDir.resolve("soak_test.db").toString();
        createFixtureDatabase(dbPath);
        eventStoreReader = new EventStoreReader(dbPath);
    }

    /**
     * Create a fixture SQLite database with WAL mode enabled (like the real one).
     */
    private void createFixtureDatabase(String path) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + path)) {
            try (Statement stmt = conn.createStatement()) {
                // Enable WAL mode like the real database (must be before transaction)
                stmt.execute("PRAGMA journal_mode=WAL");
            }
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {

                // Create events table
                stmt.execute("""
                    CREATE TABLE events (
                        id      INTEGER PRIMARY KEY AUTOINCREMENT,
                        ts      REAL    NOT NULL,
                        actor   TEXT    NOT NULL,
                        stream  TEXT    NOT NULL,
                        type    TEXT    NOT NULL,
                        payload TEXT    NOT NULL,
                        version INTEGER NOT NULL
                    )
                    """);

                // Create snapshots table
                stmt.execute("""
                    CREATE TABLE snapshots (
                        id           INTEGER PRIMARY KEY AUTOINCREMENT,
                        ts           REAL    NOT NULL,
                        stream       TEXT    NOT NULL,
                        event_version INTEGER NOT NULL,
                        projection   TEXT    NOT NULL,
                        checksum     TEXT    NOT NULL
                    )
                    """);

                stmt.execute(
                    "CREATE INDEX idx_events_stream_version ON events(stream, version)"
                );
                stmt.execute(
                    "CREATE INDEX idx_snapshots_stream_version ON snapshots(stream, event_version)"
                );

                conn.commit();
            }
        }
    }

    /**
     * Soak test: writer thread appends events while multiple reader threads query.
     * Duration: 10 seconds, verifies no crashes or inconsistencies.
     */
    @Test
    void testWalConcurrency() throws Exception {
        int numReaders = 4;
        int duration = 10;  // seconds
        AtomicInteger writeCount = new AtomicInteger(0);
        AtomicInteger readCount = new AtomicInteger(0);
        List<Exception> errors = Collections.synchronizedList(new ArrayList<>());

        ExecutorService executor = Executors.newFixedThreadPool(numReaders + 1);
        try {
            // Start writer thread
            executor.submit(() -> {
                try {
                    long startTime = System.currentTimeMillis();
                    int version = 1;
                    while (System.currentTimeMillis() - startTime < duration * 1000L) {
                        appendEvent("tracker", "item_created", Map.of(
                            "id", "item-" + version,
                            "title", "Task " + version,
                            "priority", "high",
                            "status", "open",
                            "createdAt", Instant.now().toString()
                        ), version);
                        writeCount.incrementAndGet();
                        version++;
                        Thread.sleep(50);  // Small delay between writes
                    }
                } catch (Exception e) {
                    errors.add(e);
                }
            });

            // Start reader threads
            for (int i = 0; i < numReaders; i++) {
                executor.submit(() -> {
                    try {
                        long startTime = System.currentTimeMillis();
                        while (System.currentTimeMillis() - startTime < duration * 1000L) {
                            // Mix of different read patterns
                            eventStoreReader.getStreams();
                            eventStoreReader.getEvents("tracker", null, 50);
                            eventStoreReader.projectTracker();
                            eventStoreReader.getLastEventId();
                            readCount.incrementAndGet();
                            Thread.sleep(100);
                        }
                    } catch (Exception e) {
                        errors.add(e);
                    }
                });
            }

            // Wait for completion
            executor.shutdown();
            assertTrue(executor.awaitTermination(duration + 5, TimeUnit.SECONDS),
                "Executor should terminate within timeout");

        } finally {
            executor.shutdownNow();
        }

        // Verify results
        System.out.printf("Soak test results: %d writes, %d reads%n", writeCount.get(), readCount.get());

        assertTrue(writeCount.get() > 0, "Should have written some events");
        assertTrue(readCount.get() > 0, "Should have read some events");
        assertTrue(errors.isEmpty(), "No errors should occur: " + errors);

        // Verify final state is consistent
        assertEquals(eventStoreReader.getLastEventId(),
            eventStoreReader.getStreams().stream()
                .filter(s -> "tracker".equals(s.stream()))
                .findFirst()
                .map(s -> (long) s.lastVersion())
                .orElse(0L),
            "Final state should be consistent");
    }

    /**
     * Append an event to the database (simulates writer).
     */
    private void appendEvent(String stream, String type, Map<String, Object> payload, int version) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            conn.setAutoCommit(false);
            try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO events (ts, actor, stream, type, payload, version) VALUES (?, ?, ?, ?, ?, ?)")) {
                stmt.setDouble(1, System.currentTimeMillis() / 1000.0);
                stmt.setString(2, "soak-writer");
                stmt.setString(3, stream);
                stmt.setString(4, type);
                stmt.setString(5, objectMapper.writeValueAsString(payload));
                stmt.setInt(6, version);
                stmt.executeUpdate();
                conn.commit();
            }
        }
    }
}
