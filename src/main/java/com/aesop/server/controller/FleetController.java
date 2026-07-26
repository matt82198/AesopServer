package com.aesop.server.controller;

import com.aesop.server.dto.FleetHealth;
import com.aesop.server.dto.FleetStatus;
import com.aesop.server.service.FleetBrainService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for observing aesop orchestration fleet status.
 */
@RestController
@RequestMapping("/api/v1/fleet")
public class FleetController {
    private final FleetBrainService fleetBrainService;

    public FleetController(FleetBrainService fleetBrainService) {
        this.fleetBrainService = fleetBrainService;
    }

    /**
     * Get complete fleet status snapshot.
     * Reads: stats.json, heartbeat files, orchestrator-status.json.
     * All fields gracefully degrade to "unavailable" if files are missing.
     */
    @GetMapping("/status")
    public ResponseEntity<FleetStatus> getFleetStatus() {
        return ResponseEntity.ok(fleetBrainService.getFleetStatus());
    }

    /**
     * Get aggregate fleet health status.
     * Returns OK if all components are healthy, DEGRADED with reasons otherwise.
     */
    @GetMapping("/health")
    public ResponseEntity<FleetHealth> getFleetHealth() {
        return ResponseEntity.ok(fleetBrainService.getFleetHealth());
    }
}
