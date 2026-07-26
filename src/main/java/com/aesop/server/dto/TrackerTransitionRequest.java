package com.aesop.server.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Request body for POST /api/v1/tracker/items/{id}/transitions.
 * Represents a mutation to transition a tracker item to a new status.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TrackerTransitionRequest(
    String targetStatus,
    String note
) {
}
