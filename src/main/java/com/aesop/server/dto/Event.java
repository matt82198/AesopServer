package com.aesop.server.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Map;

/**
 * A single event from the state_store event log.
 * Mirrors the schema of the events table: id, ts, actor, stream, type, payload, version.
 */
public record Event(
    @JsonProperty("id")
    long id,

    @JsonProperty("ts")
    Instant ts,

    @JsonProperty("actor")
    String actor,

    @JsonProperty("stream")
    String stream,

    @JsonProperty("type")
    String type,

    @JsonProperty("version")
    int version,

    @JsonProperty("payload")
    Map<String, Object> payload
) {}
