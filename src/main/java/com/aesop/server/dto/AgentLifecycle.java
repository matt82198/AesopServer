package com.aesop.server.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/**
 * Agent lifecycle projection: agent id, current state, first state entry time, and transition history.
 */
public record AgentLifecycle(
    @JsonProperty("agentId")
    String agentId,

    @JsonProperty("state")
    String state,

    @JsonProperty("since")
    Instant since,

    @JsonProperty("transitions")
    List<AgentTransition> transitions
) {}
