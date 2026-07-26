# AesopServer — A Typed JVM Lens on Orchestration

AesopServer is a Spring Boot microservice that observes and exposes the aesop orchestration brain through a clean, typed REST API. The service applies the Unix philosophy to distributed coordination: durable state lives on disk and in SQLite, and the Java service is simply one more reader at the hub.

**Related:** AesopServer observes the brain maintained by [aesop](https://github.com/matt82198/aesop), the Python/Node orchestration harness.

## Architecture

The aesop system is crash-only and stateless: the Python daemons (orchestrator, watchdog, monitor) write facts to shared files and a SQLite event log. AesopServer reads these same files and database—never writing (writes arrive in a later phase via an inbox-mediated channel, preserving single-writer discipline).

Every request re-reads disk. The service holds zero durable in-process state. This design has two forces:

1. **Coordination through the filesystem.** AesopServer is not a "state server" that owns a replica; it's an observer that serializes its reads against the live brain. Stale files degrade gracefully to `available: false` rather than crashing.
2. **Virtual threads for concurrency.** File reads, JDBC polls, and SSE client fan-out each get a cheap virtual thread. No reactive chains, no thread-pool tuning theater. Spring Boot 3.5 + Java 21 make this idiomatic.

Result: a service that scales to hundreds of concurrent SSE clients while remaining straightforward to read and test.

## Why These Choices

| Decision | Rationale |
|---|---|
| **Spring Boot 3.5.x** | Current supported 3.x line (3.3.x OSS end-of-life in 2026); conservative, mainstream enterprise choice for a portfolio artifact. Boot 4.0 exists; 3.5 is the pick. |
| **Java 21 LTS bytecode** | Compiled with `--release 21`, runs on JDK 21+. Virtual threads are GA everywhere ≥21. CI and Docker pin Temurin 21; local JBR (25) used for dev, bytecode targets 21 for portability. |
| **Virtual threads ON** | `spring.threads.virtual.enabled=true`. Cheap OS threads for file I/O, JDBC polls, and SSE—each client gets its own thread. Idiomatically safe with Spring WebMVC. |
| **Spring WebMVC, not WebFlux** | File I/O and JDBC are synchronous and block naturally. Virtual threads handle concurrency without reactive overhead. The code reads straight, with no monadic chaining. |
| **Records as DTOs** | Java records (`record FleetStatus(...)`) encode the REST contract at compile-time. Jackson serializes natively. Shapes in §6 (below) are normative; tests verify contract drift. |
| **Actuator + Micrometer** | Health, metrics, Prometheus registry for free. Custom gauges: heartbeat staleness, SSE client count, event-store lag. Minimal boilerplate. |
| **SQLite JDBC, read-only** | `org.xerial:sqlite-jdbc` with `SQLiteConfig.setReadOnly(true)`, `busy_timeout=5000`. Never issue `PRAGMA journal_mode` (writer owns it). See §5 for WAL/Windows mitigations. |
| **SSR + SSE, not SPA** | Server-rendered Thymeleaf templates + vanilla-JS `EventSource` hydration. Zero Node toolchain; `mvnw` stays the sole build. Clone-and-run preserved. Info-dense operator tables render trivially server-side. |

---

## Running

### Requirements

- Java 21+ (local: `JAVA_HOME` set to JDK/JBR path)
- Maven (included as `mvnw`)
- Docker (optional, for containerized deployment)

### Start the Service

**Run from source:**
```bash
# Set the paths to your aesop brain and conductor state directories
export AESOP_ROOT="/path/to/aesop"
export CONDUCTOR_ROOT="/path/to/conductor3"
./mvnw spring-boot:run
```

(Windows PowerShell: `$env:AESOP_ROOT = "C:\path\to\aesop"; $env:CONDUCTOR_ROOT = "C:\path\to\conductor3"`)

**Run from jar:**
```bash
./mvnw clean package
export AESOP_ROOT="/path/to/aesop"
export CONDUCTOR_ROOT="/path/to/conductor3"
java -jar target/aesop-server.jar
```

**Run in Docker (if Docker is available):**
```bash
docker build -t aesop-server .
# Edit docker-compose.yml to set the volume paths to your aesop and conductor3 directories
docker-compose up
```

The service binds to port `8870` and serves a dashboard at `http://localhost:8870/`.

**Configuration:** The service reads paths from environment variables with sensible defaults:
- `AESOP_ROOT` — path to the aesop brain directory (default: `./aesop`)
- `CONDUCTOR_ROOT` — path to the conductor3 state directory (default: `./conductor3`)
- `AESOP_DB_PATH` — path to the SQLite event store (default: `./aesop/state/tracker_events.db`)

### Verify Endpoints

**Fleet health:**
```bash
curl http://localhost:8870/api/v1/fleet/health | jq
```

**Actuator (Spring Boot internals):**
```bash
curl http://localhost:8870/actuator/health | jq
curl http://localhost:8870/actuator/metrics | jq
```

**Event store:**
```bash
curl http://localhost:8870/api/v1/events/streams | jq
curl "http://localhost:8870/api/v1/events?stream=tracker&limit=3" | jq
```

**SSE stream (real-time updates, Ctrl-C to exit):**
```bash
curl http://localhost:8870/api/v1/stream
```

---

## API

### Contracts (J1–J3)

All endpoints return JSON (UTF-8). Instants are ISO-8601 UTC. Absent inputs degrade in-band; never 404 or 500 for missing files.

#### `GET /api/v1/fleet/status` — Complete Fleet Snapshot

Observes orchestrator status, heartbeat ages, backed-up repos, and git statistics.

**Response:**
```json
{
  "aesop_stats": {
    "available": true,
    "error": null,
    "data": {
      "merged_prs": 24,
      "total_commits": 1847,
      "project_age_days": 387,
      "wave_count": 27,
      "insertions_deletions": 234567,
      "files_tracked": 156,
      "distinct_coauthors": 3,
      "loc": 45321,
      "generated_at": "2026-07-26T12:34:56Z"
    }
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

Heartbeat status is `FRESH` (age < 200s), `STALE` (age ≥ 200s), or `MISSING` (file absent).

#### `GET /api/v1/fleet/health` — Health Summary

Aggregate: OK if all components healthy; DEGRADED if any component missing or stale.

**Response:**
```json
{
  "status": "OK",
  "reasons": []
}
```

#### `GET /api/v1/events/streams` — Event Stream Metadata

Lists all event streams in the SQLite database with counts and versions.

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

#### `GET /api/v1/events?stream={stream}&afterVersion={version}&limit={limit}` — Event Tail

Paginated reads from the event store. Max limit is 500.

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

#### `GET /api/v1/tracker?status={status}&priority={priority}` — Tracker Projection

Projects events (fold over item_created/updated/archived) into a live tracker snapshot. Source is the SQLite database; falls back to `tracker.json` if DB unavailable.

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

#### `GET /api/v1/agents` — Agent Lifecycle Projections

Folds agent lifecycle events (dispatched/working/done/stalled) into current states.

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

#### `GET /api/v1/stream` — Server-Sent Events (SSE)

Real-time updates. Emits sections: `fleet`, `tracker`, `events`, `agents`. Keepalive every ~15s.

**Client Example (vanilla JavaScript):**
```javascript
const source = new EventSource('http://localhost:8870/api/v1/stream');

source.addEventListener('tracker', (e) => {
  const tracker = JSON.parse(e.data);
  console.log('Tracker update:', tracker.items.length, 'items');
});

source.addEventListener('fleet', (e) => {
  const fleet = JSON.parse(e.data);
  console.log('Fleet status:', fleet.timestamp);
});
```

---

## Honest Bounds

This service is shaped for its use case and does not pretend beyond it.

**Read-only by design.** AesopServer observes; it does not write tracker mutations. A future phase (J4, not yet scheduled) will add an inbox-mediated write path, preserving single-writer discipline on the Python side. For now, this is a pure observer.

**Single-box scope.** The jar and Docker image are designed to run on the operator's box or a container on the same machine, with the aesop brain directory mounted read-only. Cloud deployment claims are NOT made; the SQLite WAL gotcha (below) makes multi-machine scenarios complex.

**SQLite WAL cross-process reads on Windows.** The event store uses `PRAGMA journal_mode=WAL` for multi-reader concurrency. On Windows, a WAL database cannot be reliably read by a connection that cannot memory-map the `-shm` file while the Python writer holds it. Mitigations employed:
- Read-only JDBC connection with no immutable flag.
- `busy_timeout=5000`: retries on `SQLITE_BUSY`.
- Fall back to `tracker.json` if the database is unavailable.
- **Windows + Docker**: Do NOT mount the state directory over a network or 9p-FUSE bridge; run the jar natively on the box that owns the state, or mount a read-only snapshot.

**Soak test results (50s run on Windows, 150+ concurrent ops):** 178 writes by the Python side, 365 reads by the Java service, zero contention errors.

**Contract drift.** The shapes in §6 are runtime files, not stable APIs. The brain evolves; the Java service ports gracefully degrade (`available: false`, `source: unavailable`) rather than crash when a field disappears.

---

## Build & Test

```bash
# Full build, unit tests, and soak test
./mvnw verify

# Test only (skip package)
./mvnw test

# Package jar (no test)
./mvnw -q package

# Run tests excluding soak (fast)
./mvnw test -Dgroups="!soak"
```

**Test suite:** 38+ tests covering fleet observation, event-store projections, and SSE client fan-out. Fixtures use synthetic state directories (`@TempDir`), never touching the live brain. Golden-master tests verify projections against Python-exported data.

Soak test: Verifies 150+ concurrent reads on WAL database with zero stale-data issues, no `SQLITE_BUSY` hangs.

---

## Next Steps

- **J3 (in progress)**: AesopDashboard module — server-rendered Thymeleaf shell with vanilla-JS hydration from J2's SSE stream.
- **J4 (scheduled)**: Typed write endpoints — inbox-mediated tracker mutations, preserving the single-writer contract.

---

**License:** PolyForm Strict 1.0.0 (noncommercial, source-available).

**Author:** Matt Culliton <matt82198@gmail.com>
