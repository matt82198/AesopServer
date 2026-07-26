package com.aesop.server.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * A state transition for an agent (state + timestamp).
 */
public record AgentTransition(
    @JsonProperty("state")
    String state,

    @JsonProperty("ts")
    Instant ts
) {}
