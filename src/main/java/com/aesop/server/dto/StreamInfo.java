package com.aesop.server.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Metadata about an event stream (name, last version, event count).
 */
public record StreamInfo(
    @JsonProperty("stream")
    String stream,

    @JsonProperty("lastVersion")
    int lastVersion,

    @JsonProperty("count")
    int count
) {}
