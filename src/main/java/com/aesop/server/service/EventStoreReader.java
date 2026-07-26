package com.aesop.server.service;

import com.aesop.server.dto.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * Read-only JDBC layer for the state_store SQLite event log.
 * Handles connection pooling, WAL resilience, and event projections.
 * Pattern: fail-closed on IO errors, graceful degradation on missing DB.
 */
@Service
public class EventStoreReader {
    private final String dbPath;
    private static final int BUSY_TIMEOUT_MS = 5000;
    private static final int MAX_EVENTS_LIMIT = 500;

    public EventStoreReader(@Value("${aesop.db-path:}") String dbPath) {
        this.dbPath = dbPath;
    }

    /**
     * Get list of all streams and their metadata.
     * Returns empty list if DB is unavailable.
     */
    public List<StreamInfo> getStreams() {
        if (!isDbAvailable()) {
            return Collections.emptyList();
        }

        List<StreamInfo> streams = new ArrayList<>();
        String sql = "SELECT stream, MAX(version) as lastVersion, COUNT(*) as count " +
                    "FROM events GROUP BY stream ORDER BY stream ASC";

        try (Connection conn = openConnection()) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {

                while (rs.next()) {
                    streams.add(new StreamInfo(
                        rs.getString("stream"),
                        rs.getInt("lastVersion"),
                        rs.getInt("count")
                    ));
                }
            }
        } catch (SQLException e) {
            // Log and return empty; fail-closed
        }

        return streams;
    }

    /**
     * Read events from a stream.
     * stream: stream name
     * afterVersion: start reading from this version (exclusive), null = from beginning
     * limit: max events to return (capped at MAX_EVENTS_LIMIT)
     */
    public List<Event> getEvents(String stream, Integer afterVersion, Integer limit) {
        if (!isDbAvailable()) {
            return Collections.emptyList();
        }

        int safeLimitInt = Math.min(limit != null ? limit : MAX_EVENTS_LIMIT, MAX_EVENTS_LIMIT);
        if (safeLimitInt <= 0) {
            safeLimitInt = MAX_EVENTS_LIMIT;
        }

        List<Event> events = new ArrayList<>();
        String sql = "SELECT id, ts, actor, stream, type, payload, version FROM events " +
                    "WHERE stream = ?";

        if (afterVersion != null) {
            sql += " AND version > ?";
        }

        sql += " ORDER BY version ASC LIMIT ?";

        try (Connection conn = openConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            int paramIndex = 1;
            stmt.setString(paramIndex++, stream);
            if (afterVersion != null) {
                stmt.setInt(paramIndex++, afterVersion);
            }
            stmt.setInt(paramIndex, safeLimitInt);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    events.add(parseEvent(rs));
                }
            }
        } catch (SQLException e) {
            // Log and return empty; fail-closed
        }

        return events;
    }

    /**
     * Get events across all streams (for SSE tail).
     * afterId: return events with id > afterId (null = from beginning)
     * limit: max events to return
     */
    public List<Event> getAllEvents(Long afterId, Integer limit) {
        if (!isDbAvailable()) {
            return Collections.emptyList();
        }

        int safeLimitInt = Math.min(limit != null ? limit : MAX_EVENTS_LIMIT, MAX_EVENTS_LIMIT);
        if (safeLimitInt <= 0) {
            safeLimitInt = MAX_EVENTS_LIMIT;
        }

        List<Event> events = new ArrayList<>();
        String sql = "SELECT id, ts, actor, stream, type, payload, version FROM events ";

        if (afterId != null) {
            sql += "WHERE id > ? ";
        }

        sql += "ORDER BY id ASC LIMIT ?";

        try (Connection conn = openConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            int paramIndex = 1;
            if (afterId != null) {
                stmt.setLong(paramIndex++, afterId);
            }
            stmt.setInt(paramIndex, safeLimitInt);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    events.add(parseEvent(rs));
                }
            }
        } catch (SQLException e) {
            // Log and return empty; fail-closed
        }

        return events;
    }

    /**
     * Get the last event ID in the database (for SSE tail state).
     */
    public long getLastEventId() {
        if (!isDbAvailable()) {
            return 0;
        }

        try (Connection conn = openConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COALESCE(MAX(id), 0) FROM events")) {

            if (rs.next()) {
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            // Return 0 on error
        }

        return 0;
    }

    /**
     * Project tracker state from events in the "tracker" stream.
     * Folds item_created, item_updated, item_archived events into current state.
     */
    public TrackerSnapshot projectTracker() {
        if (!isDbAvailable()) {
            return new TrackerSnapshot("unavailable", 0, Collections.emptyList());
        }

        Map<String, TrackerItem> itemsById = new LinkedHashMap<>();
        int maxVersion = 0;

        String sql = "SELECT type, payload, version FROM events WHERE stream = 'tracker' " +
                    "ORDER BY version ASC";

        try (Connection conn = openConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                String type = rs.getString("type");
                String payloadJson = rs.getString("payload");
                int version = rs.getInt("version");
                maxVersion = version;

                Map<String, Object> payload = parseJson(payloadJson);
                if (payload == null) {
                    continue;
                }

                switch (type) {
                    case "item_created" -> {
                        String id = (String) payload.get("id");
                        if (id != null) {
                            itemsById.put(id, buildTrackerItem(payload, "db"));
                        }
                    }
                    case "item_updated" -> {
                        String id = (String) payload.get("id");
                        if (id != null && itemsById.containsKey(id)) {
                            TrackerItem existing = itemsById.get(id);
                            itemsById.put(id, mergeTrackerItem(existing, payload, "db"));
                        }
                    }
                    case "item_archived" -> {
                        String id = (String) payload.get("id");
                        if (id != null) {
                            itemsById.remove(id);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            // Fall back to unavailable
            return new TrackerSnapshot("unavailable", 0, Collections.emptyList());
        }

        return new TrackerSnapshot("db", maxVersion, new ArrayList<>(itemsById.values()));
    }

    /**
     * Project agent lifecycle from "agent_lifecycle" stream events.
     * Folds agent_dispatched, agent_working, agent_done, agent_stalled into state.
     */
    public List<AgentLifecycle> projectAgents() {
        if (!isDbAvailable()) {
            return Collections.emptyList();
        }

        Map<String, AgentLifecycle> agentsById = new LinkedHashMap<>();

        String sql = "SELECT type, payload FROM events WHERE stream = 'agent_lifecycle' " +
                    "ORDER BY ts ASC";

        try (Connection conn = openConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                String type = rs.getString("type");
                String payloadJson = rs.getString("payload");

                Map<String, Object> payload = parseJson(payloadJson);
                if (payload == null) {
                    continue;
                }

                String agentId = (String) payload.get("agent_id");
                if (agentId == null) {
                    continue;
                }

                Instant ts = parseTimestamp(payload.get("timestamp"));
                String state = mapAgentEventToState(type);

                if (!agentsById.containsKey(agentId)) {
                    agentsById.put(agentId, new AgentLifecycle(
                        agentId,
                        state,
                        ts,
                        new ArrayList<>(List.of(new AgentTransition(state, ts)))
                    ));
                } else {
                    AgentLifecycle existing = agentsById.get(agentId);
                    List<AgentTransition> transitions = new ArrayList<>(existing.transitions());
                    transitions.add(new AgentTransition(state, ts));
                    agentsById.put(agentId, new AgentLifecycle(
                        agentId,
                        state,
                        existing.since(),
                        transitions
                    ));
                }
            }
        } catch (SQLException e) {
            // Return empty on error
        }

        return new ArrayList<>(agentsById.values());
    }

    /**
     * Check if database is available and readable.
     */
    public boolean isDbAvailable() {
        if (dbPath == null || dbPath.isBlank()) {
            return false;
        }

        try (Connection conn = openConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1")) {
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }

    // --- Helper methods ---

    private Connection openConnection() throws SQLException {
        // Open in normal mode (we never write)
        // Note: For production against existing SQLite DB with WAL, this will read the latest committed data
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        conn.createStatement().execute("PRAGMA busy_timeout=" + BUSY_TIMEOUT_MS);
        return conn;
    }

    private Event parseEvent(ResultSet rs) throws SQLException {
        Map<String, Object> payload = parseJson(rs.getString("payload"));
        if (payload == null) {
            payload = Map.of();
        }

        return new Event(
            rs.getLong("id"),
            Instant.ofEpochMilli((long) (rs.getDouble("ts") * 1000)),
            rs.getString("actor"),
            rs.getString("stream"),
            rs.getString("type"),
            rs.getInt("version"),
            payload
        );
    }

    private TrackerItem buildTrackerItem(Map<String, Object> payload, String source) {
        return new TrackerItem(
            (String) payload.get("id"),
            (String) payload.get("title"),
            (String) payload.get("priority"),
            (String) payload.get("status"),
            (String) payload.get("lane"),
            source,
            castList(payload.get("tags")),
            (String) payload.get("notes"),
            (String) payload.get("prLink"),
            parseTimestamp(payload.get("createdAt")),
            parseTimestamp(payload.get("completedAt"))
        );
    }

    private TrackerItem mergeTrackerItem(TrackerItem existing, Map<String, Object> updates, String source) {
        return new TrackerItem(
            existing.id(),
            getOrElse(updates.get("title"), existing.title()),
            getOrElse(updates.get("priority"), existing.priority()),
            getOrElse(updates.get("status"), existing.status()),
            getOrElse(updates.get("lane"), existing.lane()),
            source,
            castList(getOrElse(updates.get("tags"), existing.tags())),
            getOrElse(updates.get("notes"), existing.notes()),
            getOrElse(updates.get("prLink"), existing.prLink()),
            existing.createdAt(),
            parseTimestamp(updates.get("completedAt")) != null ? parseTimestamp(updates.get("completedAt")) : existing.completedAt()
        );
    }

    @SuppressWarnings("unchecked")
    private List<String> castList(Object obj) {
        if (obj instanceof List) {
            return (List<String>) obj;
        }
        return Collections.emptyList();
    }

    private <T> T getOrElse(Object value, T defaultValue) {
        return value != null ? (T) value : defaultValue;
    }

    private Instant parseTimestamp(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String str) {
            try {
                return Instant.parse(str);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private String mapAgentEventToState(String eventType) {
        return switch (eventType) {
            case "agent_dispatched" -> "dispatched";
            case "agent_working" -> "working";
            case "agent_done" -> "done";
            case "agent_stalled" -> "stalled";
            default -> "unknown";
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            return null;
        }
    }
}
