package com.aesop.server.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * Fleet status snapshot read from the aesop brain.
 * All fields are optional to gracefully handle missing files.
 */
public record FleetStatus(
    @JsonProperty("aesop_stats")
    AesopStats aesopStats,

    @JsonProperty("watchdog")
    HeartbeatStatus watchdog,

    @JsonProperty("monitor")
    HeartbeatStatus monitor,

    @JsonProperty("orchestrator_status")
    OrchestratorStatus orchestratorStatus,

    @JsonProperty("timestamp")
    Instant timestamp
) {
    public FleetStatus {
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }
}
