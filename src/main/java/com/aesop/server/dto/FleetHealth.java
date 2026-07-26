package com.aesop.server.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Aggregate fleet health status.
 */
public record FleetHealth(
    @JsonProperty("status")
    String status,

    @JsonProperty("reasons")
    List<String> reasons
) {
    public static FleetHealth ok() {
        return new FleetHealth("OK", List.of());
    }

    public static FleetHealth degraded(String... reasons) {
        return new FleetHealth("DEGRADED", List.of(reasons));
    }
}
