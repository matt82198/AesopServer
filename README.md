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
# Get complete fleet status
curl http://localhost:8870/api/v1/fleet/status | jq

# Get aggregate health
curl http://localhost:8870/api/v1/fleet/health | jq

# Actuator endpoints
curl http://localhost:8870/actuator/health | jq
curl http://localhost:8870/actuator/info | jq
```

## API

### `GET /api/v1/fleet/status`

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

## Configuration

Edit `src/main/resources/application.yml` to customize brain paths:

```yaml
aesop:
  brain:
    aesop-root: "C:\\Users\\matt8\\aesop"      # Path to aesop root (stats.json location)
    conductor-root: "C:\\Users\\matt8\\conductor3"  # Path to conductor state
```

The service reads:
- `{aesop-root}/stats.json`
- `{conductor-root}/state/.watchdog-heartbeat`
- `{conductor-root}/monitor/.monitor-heartbeat`
- `{aesop-root}/state/orchestrator-status.json` (optional)

## Roadmap

- **J1** (this wave): Walking skeleton — typed DTOs, basic observation endpoints, tests.
- **J2**: Projections from `state_store` SQLite layer; timeline and historical queries.
- **J3**: AesopDashboard — web UI consuming these endpoints.

**Structure note:** This project uses a single Maven module for J1. J3 will add `aesop-dashboard` as a sibling module by converting this to a multi-module parent project if needed, or by restructuring the repo at that time.

## Building

```bash
./mvnw clean package
```

Produces `target/aesop-server-0.0.1-SNAPSHOT.jar` (executable).

## Testing

```bash
./mvnw test
```

Tests run against fixture files in `src/test/resources/fixtures` — never the live brain paths.

---

**License:** PolyForm Strict 1.0.0 (noncommercial, source-available)

**Author:** Matt Culliton
