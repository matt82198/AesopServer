package com.aesop.server.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * J3 Dashboard controller tests.
 * Tests server-side rendering with Thymeleaf.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "aesop.brain.aesop-root=src/test/resources/fixtures",
    "aesop.brain.conductor-root=src/test/resources/fixtures"
})
public class DashboardControllerTest {
    @Autowired
    private MockMvc mockMvc;

    /**
     * Test that GET / renders the dashboard page.
     */
    @Test
    public void testDashboardRootPath() throws Exception {
        mockMvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("text/html;charset=UTF-8"))
            .andExpect(content().string(containsString("AesopDashboard")))
            .andExpect(content().string(containsString("Fleet Status")))
            .andExpect(content().string(containsString("Tracker")));
    }

    /**
     * Test that GET /dashboard also renders.
     */
    @Test
    public void testDashboardExplicitPath() throws Exception {
        mockMvc.perform(get("/dashboard"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("text/html;charset=UTF-8"))
            .andExpect(content().string(containsString("AesopDashboard")));
    }

    /**
     * Test that the page contains key sections.
     */
    @Test
    public void testDashboardSectionsPresent() throws Exception {
        MvcResult result = mockMvc.perform(get("/"))
            .andExpect(status().isOk())
            .andReturn();

        String content = result.getResponse().getContentAsString();

        // Check for section headers
        assert(content.contains("Fleet Status"));
        assert(content.contains("Tracker"));
        assert(content.contains("Recent Events"));
        assert(content.contains("Agents"));

        // Check for key elements
        assert(content.contains("dashboard-header"));
        assert(content.contains("fleet-strip"));
        assert(content.contains("tracker-section"));
        assert(content.contains("events-section"));
        assert(content.contains("agents-section"));
    }

    /**
     * Test that the page contains fleet status pills.
     */
    @Test
    public void testFleetStatusPills() throws Exception {
        MvcResult result = mockMvc.perform(get("/"))
            .andExpect(status().isOk())
            .andReturn();

        String content = result.getResponse().getContentAsString();

        // Check for watchdog and monitor status pills
        assert(content.contains("watchdog"));
        assert(content.contains("monitor"));
        assert(content.contains("status-pill"));
    }

    /**
     * Test that the page contains live indicator.
     */
    @Test
    public void testLiveIndicator() throws Exception {
        MvcResult result = mockMvc.perform(get("/"))
            .andExpect(status().isOk())
            .andReturn();

        String content = result.getResponse().getContentAsString();

        assert(content.contains("live-indicator"));
        assert(content.contains("disconnected"));
    }

    /**
     * Test that tracker table renders with columns.
     */
    @Test
    public void testTrackerTableStructure() throws Exception {
        MvcResult result = mockMvc.perform(get("/"))
            .andExpect(status().isOk())
            .andReturn();

        String content = result.getResponse().getContentAsString();

        // Check for table structure
        assert(content.contains("tracker-table"));
        assert(content.contains("col-id"));
        assert(content.contains("col-title"));
        assert(content.contains("col-status"));
        assert(content.contains("col-priority"));
        assert(content.contains("col-lane"));
    }

    /**
     * Test that events table renders.
     */
    @Test
    public void testEventsTableStructure() throws Exception {
        MvcResult result = mockMvc.perform(get("/"))
            .andExpect(status().isOk())
            .andReturn();

        String content = result.getResponse().getContentAsString();

        // Check for events table
        assert(content.contains("events-table"));
        assert(content.contains("col-time"));
        assert(content.contains("col-stream"));
        assert(content.contains("col-type"));
        assert(content.contains("col-actor"));
        assert(content.contains("col-version"));
    }

    /**
     * Test that the page loads JavaScript.
     */
    @Test
    public void testJavaScriptLoaded() throws Exception {
        MvcResult result = mockMvc.perform(get("/"))
            .andExpect(status().isOk())
            .andReturn();

        String content = result.getResponse().getContentAsString();

        assert(content.contains("dashboard.js"));
        assert(content.contains("<script"));
    }

    /**
     * Test that CSS is included.
     */
    @Test
    public void testCSSLoaded() throws Exception {
        MvcResult result = mockMvc.perform(get("/"))
            .andExpect(status().isOk())
            .andReturn();

        String content = result.getResponse().getContentAsString();

        assert(content.contains("dashboard.css"));
        assert(content.contains("<link"));
    }

    /**
     * Test that the page renders without exceptions even with missing data.
     */
    @Test
    public void testRobustRenderingWithMissingData() throws Exception {
        // The test fixture has minimal data; ensure page still renders
        mockMvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("AesopDashboard")));
    }

    /**
     * Test that the page is valid HTML5.
     */
    @Test
    public void testHTMLStructure() throws Exception {
        MvcResult result = mockMvc.perform(get("/"))
            .andExpect(status().isOk())
            .andReturn();

        String content = result.getResponse().getContentAsString();

        // Check basic HTML structure
        assert(content.contains("<!DOCTYPE html>"));
        assert(content.contains("<html"));
        assert(content.contains("</html>"));
        assert(content.contains("<head>"));
        assert(content.contains("</head>"));
        assert(content.contains("<body>"));
        assert(content.contains("</body>"));
    }
}
