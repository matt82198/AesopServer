package com.aesop.server.service;

import com.aesop.server.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Service for reading aesop orchestration brain state from disk.
 * Implements graceful degradation when files are unavailable.
 */
@Service
public class FleetBrainService {
    private final String aesopRoot;
    private final String conductorRoot;
    private final ObjectMapper objectMapper;

    public FleetBrainService(
        @Value("${aesop.brain.aesop-root}") String aesopRoot,
        @Value("${aesop.brain.conductor-root}") String conductorRoot
    ) {
        this.aesopRoot = aesopRoot;
        this.conductorRoot = conductorRoot;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Read complete fleet status from brain.
     */
    public FleetStatus getFleetStatus() {
        return new FleetStatus(
            readAesopStats(),
            readWatchdogHeartbeat(),
            readMonitorHeartbeat(),
            readOrchestratorStatus(),
            Instant.now()
        );
    }

    /**
     * Compute aggregate health from fleet status.
     */
    public FleetHealth getFleetHealth() {
        FleetStatus status = getFleetStatus();
        List<String> issues = new ArrayList<>();

        // Check each component
        if (status.aesopStats() != null && status.aesopStats().available() == false) {
            issues.add("aesop_stats unavailable");
        }

        if (status.watchdog() != null && status.watchdog().available() == false) {
            issues.add("watchdog unavailable: " + status.watchdog().error());
        } else if (status.watchdog() != null && "STALE".equals(status.watchdog().status())) {
            issues.add("watchdog stale (> 300s)");
        }

        if (status.monitor() != null && status.monitor().available() == false) {
            issues.add("monitor unavailable: " + status.monitor().error());
        } else if (status.monitor() != null && "STALE".equals(status.monitor().status())) {
            issues.add("monitor stale (> 300s)");
        }

        if (status.orchestratorStatus() != null && status.orchestratorStatus().available() == false) {
            issues.add("orchestrator unavailable: " + status.orchestratorStatus().error());
        }

        return issues.isEmpty() ? FleetHealth.ok() : FleetHealth.degraded(issues.toArray(new String[0]));
    }

    private AesopStats readAesopStats() {
        Path path = Paths.get(aesopRoot, "stats.json");
        try {
            String content = Files.readString(path);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.readValue(content, Map.class);
            return new AesopStats(true, null);
        } catch (Exception e) {
            return new AesopStats(false, "File not found or unreadable: " + e.getMessage());
        }
    }

    private HeartbeatStatus readWatchdogHeartbeat() {
        Path path = Paths.get(conductorRoot, "state", ".watchdog-heartbeat");
        return readHeartbeatFile(path, "watchdog");
    }

    private HeartbeatStatus readMonitorHeartbeat() {
        Path path = Paths.get(conductorRoot, "monitor", ".monitor-heartbeat");
        return readHeartbeatFile(path, "monitor");
    }

    private HeartbeatStatus readHeartbeatFile(Path path, String name) {
        try {
            String content = Files.readString(path).trim();
            long epochSeconds = Long.parseLong(content);
            long ageSeconds = System.currentTimeMillis() / 1000 - epochSeconds;
            String status = ageSeconds < 300 ? "FRESH" : "STALE";
            return HeartbeatStatus.fresh(ageSeconds);
        } catch (Exception e) {
            return HeartbeatStatus.unavailable("File not found or unreadable: " + e.getMessage());
        }
    }

    private OrchestratorStatus readOrchestratorStatus() {
        Path path = Paths.get(aesopRoot, "state", "orchestrator-status.json");
        try {
            if (!Files.exists(path)) {
                return OrchestratorStatus.unavailable("File does not exist");
            }
            String content = Files.readString(path);
            @SuppressWarnings("unchecked")
            Map<String, String> data = objectMapper.readValue(content, Map.class);
            String phase = data.get("phase");
            String state = data.get("state");
            return OrchestratorStatus.available(phase, state);
        } catch (Exception e) {
            return OrchestratorStatus.unavailable("File not found or unreadable: " + e.getMessage());
        }
    }
}
