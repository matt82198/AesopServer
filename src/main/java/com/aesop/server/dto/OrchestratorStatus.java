package com.aesop.server.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashMap;
import java.util.Map;

/**
 * Orchestrator status from orchestrator-status.json.
 * Captures the current phase and state of the orchestration.
 */
public record OrchestratorStatus(
    @JsonProperty("available")
    Boolean available,

    @JsonProperty("phase")
    String phase,

    @JsonProperty("state")
    String state,

    @JsonProperty("error")
    String error
) {
    public static OrchestratorStatus unavailable(String reason) {
        return new OrchestratorStatus(false, null, null, reason);
    }

    public static OrchestratorStatus available(String phase, String state) {
        return new OrchestratorStatus(true, phase, state, null);
    }
}
