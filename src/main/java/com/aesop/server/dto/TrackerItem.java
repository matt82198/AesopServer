package com.aesop.server.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/**
 * A tracker item (from tracker.json or event projection).
 * camelCase mirrors tracker.json field names.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TrackerItem(
    @JsonProperty("id")
    String id,

    @JsonProperty("title")
    String title,

    @JsonProperty("priority")
    String priority,

    @JsonProperty("status")
    String status,

    @JsonProperty("lane")
    String lane,

    @JsonProperty("source")
    String source,

    @JsonProperty("tags")
    List<String> tags,

    @JsonProperty("notes")
    String notes,

    @JsonProperty("prLink")
    String prLink,

    @JsonProperty("createdAt")
    Instant createdAt,

    @JsonProperty("completedAt")
    Instant completedAt
) {}
