package com.aesop.server.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Heartbeat status for a daemon process.
 * Age is in seconds since the epoch file was last updated.
 * Status is FRESH if age < 300s, STALE otherwise.
 */
public record HeartbeatStatus(
    @JsonProperty("age_seconds")
    Long ageSeconds,

    @JsonProperty("status")
    String status,

    @JsonProperty("available")
    Boolean available,

    @JsonProperty("error")
    String error
) {
    public HeartbeatStatus(Long ageSeconds, String status, Boolean available, String error) {
        this.ageSeconds = ageSeconds;
        this.status = status;
        this.available = available;
        this.error = error;
    }

    public static HeartbeatStatus unavailable(String reason) {
        return new HeartbeatStatus(null, null, false, reason);
    }

    public static HeartbeatStatus fresh(long ageSeconds) {
        return new HeartbeatStatus(ageSeconds, "FRESH", true, null);
    }

    public static HeartbeatStatus stale(long ageSeconds) {
        return new HeartbeatStatus(ageSeconds, "STALE", true, null);
    }
}
