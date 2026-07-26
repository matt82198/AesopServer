package com.aesop.server.controller;

import com.aesop.server.dto.FleetHealth;
import com.aesop.server.dto.FleetStatus;
import com.aesop.server.service.FleetBrainService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = {
    "aesop.brain.aesop-root=src/test/resources/fixtures",
    "aesop.brain.conductor-root=src/test/resources/fixtures"
})
public class FleetControllerTest {
    @Autowired
    private FleetController fleetController;

    @Test
    public void testGetFleetStatusEndpoint() {
        ResponseEntity<FleetStatus> response = fleetController.getFleetStatus();
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().timestamp());
        assertNotNull(response.getBody().aesopStats());
    }

    @Test
    public void testGetFleetHealthEndpoint() {
        ResponseEntity<FleetHealth> response = fleetController.getFleetHealth();
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().status());
        assertNotNull(response.getBody().reasons());
    }

    @Test
    public void testFleetStatusDTOShape() {
        ResponseEntity<FleetStatus> response = fleetController.getFleetStatus();
        assertEquals(200, response.getStatusCode().value());
        FleetStatus status = response.getBody();
        assertNotNull(status.aesopStats());
        assertNotNull(status.watchdog());
        assertNotNull(status.monitor());
        assertNotNull(status.timestamp());
    }

    @Test
    public void testFleetHealthDTOShape() {
        ResponseEntity<FleetHealth> response = fleetController.getFleetHealth();
        assertEquals(200, response.getStatusCode().value());
        FleetHealth health = response.getBody();
        assertTrue(health.status().equals("OK") || health.status().equals("DEGRADED"));
        assertNotNull(health.reasons());
    }

    @Test
    public void testMissingFilesGracefullyDegrade() {
        // With fixture paths pointing to a minimal test directory,
        // missing files should not cause 500 errors
        ResponseEntity<FleetStatus> statusResponse = fleetController.getFleetStatus();
        assertEquals(200, statusResponse.getStatusCode().value());

        ResponseEntity<FleetHealth> healthResponse = fleetController.getFleetHealth();
        assertEquals(200, healthResponse.getStatusCode().value());
    }
}
