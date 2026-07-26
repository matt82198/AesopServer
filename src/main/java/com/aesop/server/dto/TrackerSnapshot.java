package com.aesop.server.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Tracker snapshot with dual-source capability (db or file).
 * source: "db" or "file" depending on where data came from.
 */
public record TrackerSnapshot(
    @JsonProperty("source")
    String source,

    @JsonProperty("version")
    int version,

    @JsonProperty("items")
    List<TrackerItem> items
) {}
