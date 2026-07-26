# AesopServer

**AesopServer** is a Spring Boot microservice that observes the aesop orchestration brain through a typed JVM lens.

## What it is

AesopServer applies the Unix philosophy to orchestration: coordination through files. This service is just another process at the hub, reading the shared state files that aesop and its supporting daemons write to disk. It surfaces those observations through a clean, typed REST API suitable for dashboards, monitoring systems, and operational tools.

It holds NO state internally. Every request re-reads the filesystem. If the brain files are unavailable, endpoints gracefully degrade to "unavailable" fields rather than returning 500 errors.

## Quickstart

### Requirements
- Java 21+ (checked via `JAVA_HOME`)
- Maven (via included `mvnw` wrapper)

### Run

```bash
# Set Java home if needed
export JAVA_HOME="/c/Users/matt8/AppData/Local/Programs/IntelliJ IDEA/jbr"

# Run tests
./mvnw -q test

# Start the server (port 8870)
./mvnw -q spring-boot:run
```

### Verify endpoints

While the server is running:

```bash
# J1: Fleet status
curl http://localhost:8870/api/v1/fleet/status | jq
curl http://localhost:8870/api/v1/fleet/health | jq

# J2: Event store projections
curl http://localhost:8870/api/v1/events/streams | jq
curl "http://localhost:8870/api/v1/events?stream=tracker&limit=3" | jq
curl http://localhost:8870/api/v1/tracker | jq
curl http://localhost:8870/api/v1/agents | jq

# J2: SSE stream (Ctrl-C to exit)
curl http://localhost:8870/api/v1/stream

# Actuator
curl http://localhost:8870/actuator/health | jq
curl http://localhost:8870/actuator/metrics | jq
```

## API

### J1: Fleet Observation Endpoints

#### `GET /api/v1/fleet/status`

Complete snapshot of fleet state read from the aesop brain.

**Response:**
```json
{
  "aesop_stats": {
    "available": true,
    "error": null
  },
  "watchdog": {
    "available": true,
    "age_seconds": 42,
    "status": "FRESH",
    "error": null
  },
  "monitor": {
    "available": true,
    "age_seconds": 35,
    "status": "FRESH",
    "error": null
  },
  "orchestrator_status": {
    "available": true,
    "phase": "execute",
    "state": "running",
    "error": null
  },
  "timestamp": "2026-07-26T12:34:56Z"
}
```

**Graceful degradation:** If any source file is missing or unreadable, the corresponding field will have `available: false` and an `error` message describing why.

### `GET /api/v1/fleet/health`

Aggregate health: OK if all components healthy, DEGRADED with reasons otherwise.

**Response (OK):**
```json
{
  "status": "OK",
  "reasons": []
}
```

**Response (DEGRADED):**
```json
{
  "status": "DEGRADED",
  "reasons": [
    "watchdog stale (> 300s)",
    "monitor unavailable: File not found"
  ]
}
```

### J2: Event Store Projections (SQLite WAL Read-Only)

#### `GET /api/v1/events/streams`

List all event streams with metadata.

**Response:**
```json
[
  {
    "stream": "tracker",
    "lastVersion": 193,
    "count": 193
  }
]
```

#### `GET /api/v1/events?stream={stream}&afterVersion={version}&limit={limit}`

Read events from a stream (paginated).

**Query Parameters:**
- `stream` (required): Stream name (e.g., "tracker")
- `afterVersion` (optional): Return events with version > this value; default=from beginning
- `limit` (optional): Max events (default 100, capped at 500)

**Response:**
```json
[
  {
    "id": 1,
    "ts": "2026-07-14T23:24:42.678Z",
    "actor": "migration",
    "stream": "tracker",
    "type": "item_created",
    "version": 1,
    "payload": {
      "id": "item-123",
      "title": "Task title",
      "priority": "high",
      "status": "open",
      "createdAt": "2026-07-14T23:24:42.678Z"
    }
  }
]
```

#### `GET /api/v1/tracker?status={status}&priority={priority}`

Tracker projection from events (item_created/updated/archived fold).

**Query Parameters:**
- `status` (optional): Filter by status (e.g., "open", "done")
- `priority` (optional): Filter by priority (e.g., "high", "medium")

**Response:**
```json
{
  "source": "db",
  "version": 193,
  "items": [
    {
      "id": "item-1",
      "title": "Task 1",
      "priority": "high",
      "status": "done",
      "lane": "done",
      "source": "db",
      "tags": ["security"],
      "notes": "Completed",
      "prLink": "https://github.com/...",
      "createdAt": "2026-07-14T23:24:42.678Z",
      "completedAt": "2026-07-26T12:34:56Z"
    }
  ]
}
```

**Note:** `source: "db"` indicates the projection came from the event store SQLite database. If the database is unavailable, `source: "unavailable"` and `items: []`.

#### `GET /api/v1/agents`

Agent lifecycle projections (agent_dispatched/working/done/stalled fold).

**Response:**
```json
[
  {
    "agentId": "agent-123",
    "state": "done",
    "since": "2026-07-26T12:00:00Z",
    "transitions": [
      { "state": "dispatched", "ts": "2026-07-26T12:00:00Z" },
      { "state": "working", "ts": "2026-07-26T12:01:00Z" },
      { "state": "done", "ts": "2026-07-26T12:05:00Z" }
    ]
  }
]
```

#### `GET /api/v1/stream` (SSE)

Server-sent event stream: real-time updates for fleet, tracker, events, agents.

**Content-Type:** `text/event-stream`

**Protocol:**
```
event: fleet
data: {"status":"UP",...}

event: tracker
data: {"source":"db","items":[...]}

event: events
data: [{"id":1,"ts":"...",...]

event: agents
data: [...]

: keepalive (every 15s)
```

**Client Example (vanilla JS):**
```javascript
const eventSource = new EventSource('http://localhost:8870/api/v1/stream');
eventSource.addEventListener('tracker', (e) => {
  const tracker = JSON.parse(e.data);
  console.log('Tracker updated:', tracker.items.length, 'items');
});
```

## Configuration

Edit `src/main/resources/application.yml` to customize paths:

```yaml
spring:
  threads:
    virtual:
      enabled: true    # Use virtual threads (default: true)

server:
  port: 8870

aesop:
  brain:
    aesop-root: "C:\\Users\\matt8\\aesop"
    conductor-root: "C:\\Users\\matt8\\conductor3"
  db-path: "C:\\Users\\matt8\\aesop\\state\\tracker_events.db"  # SQLite read-only
```

The service reads:
- **J1 (fleet):**
  - `{aesop-root}/stats.json`
  - `{conductor-root}/state/.watchdog-heartbeat`
  - `{conductor-root}/monitor/.monitor-heartbeat`
  - `{aesop-root}/state/orchestrator-status.json` (optional)
- **J2 (events):**
  - `{db-path}` (SQLite WAL, read-only mode)

## Architecture Decisions

### Why Spring WebMVC (not WebFlux)?

This service combines:
1. **Synchronous file I/O** (stats.json, heartbeats) — no performance gain from reactive streams
2. **JDBC polling** (SQLite read-only) — blocks naturally, virtual threads handle concurrency idiomatically
3. **Virtual threads** (Spring Boot 3.5) — one affordable OS thread per request, no thread-pool tuning theater

**Result:** WebMVC + virtual threads outperforms reactive for this workload while keeping code straightforward. No monadic chaining; just readable sequential logic.

### SQLite WAL on Windows (J2 note)

The state_store database uses `PRAGMA journal_mode=WAL` for multi-reader concurrency. On Windows, this presents a cross-process read challenge: the WAL `-shm` file cannot be memory-mapped reliably across some network and VM boundaries.

**Mitigation (production):** Open connection read-only, set `busy_timeout=5000`, retry on `SQLITE_BUSY` (handled transparently). If unavailable, fall back to tracker.json.

**Testing:** `WalSoakTest` verifies 150+ concurrent reads+writes on WAL without deadlock. The test is marked `@Tag("soak")` and runs during `mvnw verify`.

### Virtual Threads + SSE

The `EventStreamService` polls the event store every 2s on a dedicated virtual thread and broadcasts to all connected clients. Each client gets its own virtual thread (dirt-cheap, scales to 1000s). Heartbeat every 15s keeps connections alive. No reactive complexity; clean, readable async loop.

## Roadmap

- **J1** (SHIPPED): Walking skeleton — typed DTOs, basic observation endpoints, tests.
- **J2** (THIS WAVE): Event store projections from SQLite; SSE tail with virtual threads; golden-master tests.
- **J3** (NEXT): AesopDashboard — server-rendered Thymeleaf + vanilla-JS SSE hydration (no SPA toolchain).
- **J4** (FUTURE): Typed write path (inbox-mediated mutations); multi-process coordination.

**Structure note:** Single Maven module houses server + dashboard. Dashboard artifacts (templates, static files) are copied into the jar at package time.

## Building

```bash
./mvnw clean verify
```

Produces `target/aesop-server-0.0.1-SNAPSHOT.jar` (executable, fully tested).

The build runs:
1. **Unit tests:** 26 tests, ~2s (EventStoreReaderTest: 11; EventStoreControllerTest: 9; FleetControllerTest: 5; etc.)
2. **Soak test:** `WalSoakTest` — concurrent write/read on WAL database for 10s, 150+ events, zero errors (marked `@Tag("soak")`)
3. **Package:** Spring Boot jar repackaging with nested dependencies

## Testing

```bash
# All tests (including soak)
./mvnw test

# Skip slow soak test
./mvnw test -Dgroups="!soak"

# Run only soak test
./mvnw test -Dgroups="soak"
```

**Test Strategy:**
- **EventStoreReaderTest (11 tests):** Fixture SQLite database (prepared statements, WAL mode) verifies projections against known events. Golden-master style: payloads are well-formed JSON, queries return typed DTOs.
- **EventStoreControllerTest (9 tests):** MockMvc contract tests verify all endpoints accept requests and return typed JSON.
- **FleetControllerTest (5 tests):** J1 tests (existing).
- **WalSoakTest (1 test, slow):** Concurrent writer + 4 readers on WAL database; confirms no SQLITE_BUSY, deadlock, or data inconsistency over 10s.

**Test Isolation:** Tests never touch live brain paths (application-test.yml sets `aesop.db-path: ""`). All state reads from `@TempDir` fixtures or are mocked.

**Note on Python semantics:** `EventStoreReader.projectTracker()` and `projectAgents()` mirror the logic from `aesop/state_store/read_api.py`. Event type mapping (item_created → payload fold) is verified against the Python export as a golden master, ensuring Java projections match Python truth.

---

**License:** PolyForm Strict 1.0.0 (noncommercial, source-available)

**Author:** Matt Culliton
