package com.aesop.server.controller;

import com.aesop.server.dto.*;
import com.aesop.server.service.EventStoreReader;
import com.aesop.server.service.EventStreamService;
import com.aesop.server.service.FleetBrainService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * J3 Dashboard controller: server-rendered Thymeleaf + EventSource hydration.
 * Single page with fleet status, tracker board, event tail, and agent summary.
 * No SPA framework; vanilla JS for live updates.
 */
@Controller
public class DashboardController {
    private final FleetBrainService fleetBrainService;
    private final EventStoreReader eventStoreReader;
    private final EventStreamService eventStreamService;

    public DashboardController(FleetBrainService fleetBrainService,
                              EventStoreReader eventStoreReader,
                              EventStreamService eventStreamService) {
        this.fleetBrainService = fleetBrainService;
        this.eventStoreReader = eventStoreReader;
        this.eventStreamService = eventStreamService;
    }

    /**
     * GET / or /dashboard — server-rendered dashboard page.
     * First paint fully populated without JavaScript.
     */
    @GetMapping({"/", "/dashboard"})
    public String dashboard(Model model) {
        // Fleet status strip
        FleetStatus fleetStatus = fleetBrainService.getFleetStatus();
        model.addAttribute("fleetStatus", fleetStatus);

        // Tracker board with counts
        TrackerSnapshot tracker = eventStoreReader.projectTracker();
        model.addAttribute("tracker", tracker);

        // Count tracker items by status and priority
        Map<String, Long> statusCounts = tracker.items().stream()
            .collect(Collectors.groupingBy(TrackerItem::status, Collectors.counting()));
        Map<String, Long> priorityCounts = tracker.items().stream()
            .collect(Collectors.groupingBy(TrackerItem::priority, Collectors.counting()));

        model.addAttribute("statusCounts", statusCounts);
        model.addAttribute("priorityCounts", priorityCounts);

        // Recent events (last 25)
        List<Event> recentEvents = eventStoreReader.getAllEvents(0L, 25);
        model.addAttribute("recentEvents", recentEvents);

        // Agent lifecycle summary
        List<AgentLifecycle> agents = eventStoreReader.projectAgents();
        model.addAttribute("agents", agents);

        // Page metadata
        model.addAttribute("generatedAt", Instant.now());
        model.addAttribute("connectedClients", eventStreamService.getConnectedClientCount());

        return "index";
    }
}
