package com.aesop.server.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashMap;
import java.util.Map;

/**
 * Aesop orchestration statistics from stats.json.
 * Holds dynamic content; unknown fields are captured in extras.
 */
public record AesopStats(
    @JsonProperty("available")
    Boolean available,

    @JsonProperty("error")
    String error
) {
    // Constructor with no-arg companion for Jackson deserialization
    public static AesopStats fromJson(Map<String, Object> data) {
        Boolean available = (Boolean) data.get("available");
        String error = (String) data.get("error");
        return new AesopStats(available, error);
    }
}
