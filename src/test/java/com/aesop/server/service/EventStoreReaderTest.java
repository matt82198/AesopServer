package com.aesop.server.service;

import com.aesop.server.dto.Event;
import com.aesop.server.dto.StreamInfo;
import com.aesop.server.dto.TrackerSnapshot;
import com.aesop.server.dto.AgentLifecycle;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for EventStoreReader with a fixture SQLite database.
 * Verifies read-only JDBC layer and projection logic (golden-master style).
 */
class EventStoreReaderTest {

    private EventStoreReader eventStoreReader;
    private String dbPath;
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setup(@TempDir Path tempDir) throws Exception {
        dbPath = tempDir.resolve("test_events.db").toString();
        createFixtureDatabase(dbPath);

        // Verify database was created
        assertTrue(java.nio.file.Files.exists(java.nio.file.Paths.get(dbPath)), "Database file should exist");

        eventStoreReader = new EventStoreReader(dbPath);

        // Verify database is available
        assertTrue(eventStoreReader.isDbAvailable(), "Database should be available after creation");
    }

    /**
     * Create a fixture SQLite database with test events.
     */
    private void createFixtureDatabase(String path) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + path)) {
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

            // Insert test tracker events using prepared statements for proper escaping
            long now = System.currentTimeMillis() / 1000;
            String insertTrackerSQL = "INSERT INTO events (ts, actor, stream, type, payload, version) VALUES (?, ?, ?, ?, ?, ?)";

            try (var pstmt = conn.prepareStatement(insertTrackerSQL)) {
                pstmt.setDouble(1, now);
                pstmt.setString(2, "user1");
                pstmt.setString(3, "tracker");
                pstmt.setString(4, "item_created");
                pstmt.setString(5, objectMapper.writeValueAsString(
                    Map.of("id", "item-1", "title", "Task 1", "priority", "high", "status", "open", "createdAt", Instant.now().toString())
                ));
                pstmt.setInt(6, 1);
                pstmt.addBatch();

                pstmt.setDouble(1, now);
                pstmt.setString(2, "user1");
                pstmt.setString(3, "tracker");
                pstmt.setString(4, "item_created");
                pstmt.setString(5, objectMapper.writeValueAsString(
                    Map.of("id", "item-2", "title", "Task 2", "priority", "medium", "status", "open", "createdAt", Instant.now().toString())
                ));
                pstmt.setInt(6, 2);
                pstmt.addBatch();

                pstmt.setDouble(1, now);
                pstmt.setString(2, "user1");
                pstmt.setString(3, "tracker");
                pstmt.setString(4, "item_updated");
                pstmt.setString(5, objectMapper.writeValueAsString(
                    Map.of("id", "item-1", "status", "done")
                ));
                pstmt.setInt(6, 3);
                pstmt.addBatch();

                pstmt.executeBatch();
            }

            // Insert test agent lifecycle events
            String insertAgentSQL = "INSERT INTO events (ts, actor, stream, type, payload, version) VALUES (?, ?, ?, ?, ?, ?)";
            try (var pstmt = conn.prepareStatement(insertAgentSQL)) {
                pstmt.setDouble(1, now);
                pstmt.setString(2, "system");
                pstmt.setString(3, "agent_lifecycle");
                pstmt.setString(4, "agent_dispatched");
                pstmt.setString(5, objectMapper.writeValueAsString(
                    Map.of("agent_id", "agent-123", "timestamp", Instant.now().toString())
                ));
                pstmt.setInt(6, 1);
                pstmt.addBatch();

                pstmt.setDouble(1, now);
                pstmt.setString(2, "system");
                pstmt.setString(3, "agent_lifecycle");
                pstmt.setString(4, "agent_working");
                pstmt.setString(5, objectMapper.writeValueAsString(
                    Map.of("agent_id", "agent-123", "timestamp", Instant.now().toString())
                ));
                pstmt.setInt(6, 2);
                pstmt.addBatch();

                pstmt.setDouble(1, now);
                pstmt.setString(2, "system");
                pstmt.setString(3, "agent_lifecycle");
                pstmt.setString(4, "agent_done");
                pstmt.setString(5, objectMapper.writeValueAsString(
                    Map.of("agent_id", "agent-123", "timestamp", Instant.now().toString())
                ));
                pstmt.setInt(6, 3);
                pstmt.addBatch();

                pstmt.executeBatch();
            }
            conn.commit();
        }
    }

    @Test
    void testGetStreams() {
        List<StreamInfo> streams = eventStoreReader.getStreams();
        assertNotNull(streams);
        assertTrue(streams.size() >= 2, "Should have at least 2 streams");

        StreamInfo tracker = streams.stream().filter(s -> "tracker".equals(s.stream())).findFirst().orElse(null);
        assertNotNull(tracker);
        assertEquals("tracker", tracker.stream());
        assertEquals(3, tracker.lastVersion());
        assertEquals(3, tracker.count());

        StreamInfo agents = streams.stream().filter(s -> "agent_lifecycle".equals(s.stream())).findFirst().orElse(null);
        assertNotNull(agents);
        assertEquals("agent_lifecycle", agents.stream());
        assertEquals(3, agents.lastVersion());
        assertEquals(3, agents.count());
    }

    @Test
    void testGetEventsFromStream() {
        List<Event> events = eventStoreReader.getEvents("tracker", null, 10);
        assertNotNull(events);
        assertEquals(3, events.size());

        Event first = events.get(0);
        assertEquals(1, first.version());
        assertEquals("tracker", first.stream());
        assertEquals("item_created", first.type());
    }

    @Test
    void testGetEventsWithAfterVersion() {
        List<Event> events = eventStoreReader.getEvents("tracker", 1, 10);
        assertNotNull(events);
        assertEquals(2, events.size());
        assertEquals(2, events.get(0).version());
        assertEquals(3, events.get(1).version());
    }

    @Test
    void testGetEventsLimitCapping() {
        List<Event> events = eventStoreReader.getEvents("tracker", null, 1000);
        assertNotNull(events);
        assertEquals(3, events.size(), "Should cap limit at MAX_EVENTS_LIMIT (500)");
    }

    @Test
    void testGetAllEvents() {
        List<Event> allEvents = eventStoreReader.getAllEvents(null, 100);
        assertNotNull(allEvents);
        assertTrue(allEvents.size() >= 6, "Should have at least 6 total events");
    }

    @Test
    void testGetLastEventId() {
        long lastId = eventStoreReader.getLastEventId();
        assertTrue(lastId > 0);
    }

    @Test
    void testProjectTracker() {
        TrackerSnapshot snapshot = eventStoreReader.projectTracker();
        assertNotNull(snapshot);
        assertEquals("db", snapshot.source());
        assertEquals(3, snapshot.version());
        assertEquals(2, snapshot.items().size());

        var item1 = snapshot.items().stream()
            .filter(i -> "item-1".equals(i.id()))
            .findFirst()
            .orElse(null);
        assertNotNull(item1);
        assertEquals("done", item1.status());
        assertEquals("Task 1", item1.title());
    }

    @Test
    void testProjectAgents() {
        List<AgentLifecycle> agents = eventStoreReader.projectAgents();
        assertNotNull(agents);
        assertEquals(1, agents.size());

        AgentLifecycle agent = agents.get(0);
        assertEquals("agent-123", agent.agentId());
        assertEquals("done", agent.state());
        assertNotNull(agent.since());
        assertEquals(3, agent.transitions().size());
    }

    @Test
    void testIsDbAvailable() {
        assertTrue(eventStoreReader.isDbAvailable());
    }

    @Test
    void testIsDbUnavailable() {
        EventStoreReader unavailableReader = new EventStoreReader("");
        assertFalse(unavailableReader.isDbAvailable(), "Empty db path should be unavailable");
    }

    @Test
    void testProjectTrackerEmptyDb() {
        EventStoreReader emptyReader = new EventStoreReader("");
        TrackerSnapshot snapshot = emptyReader.projectTracker();
        assertNotNull(snapshot);
        assertEquals("unavailable", snapshot.source());
        assertTrue(snapshot.items().isEmpty());
    }
}
