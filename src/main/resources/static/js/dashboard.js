/**
 * AesopDashboard EventSource hydration
 * Subscribes to /api/v1/stream and updates dashboard sections live.
 * Vanilla JS, no framework dependencies.
 */

(function () {
    const STREAM_URL = '/api/v1/stream';
    const MAX_EVENTS_SHOWN = 25;
    const RECONNECT_DELAYS = [1000, 2000, 5000, 10000]; // backoff in ms

    let eventSource = null;
    let reconnectAttempt = 0;
    let reconnectTimer = null;

    const DOM = {
        liveIndicator: () => document.getElementById('live-indicator'),
        eventsTable: () => document.querySelector('.events-table tbody'),
        trackerTable: () => document.querySelector('.tracker-table tbody'),
        agentsTable: () => document.querySelector('.agents-table tbody'),
    };

    /**
     * Update the live indicator with status and color.
     * States: connected (green), reconnecting (yellow), disconnected (grey)
     */
    function updateLiveIndicator(state) {
        const indicator = DOM.liveIndicator();
        if (!indicator) return;

        indicator.classList.remove('grey', 'green', 'yellow');
        if (state === 'connected') {
            indicator.textContent = '● connected';
            indicator.classList.add('green');
        } else if (state === 'reconnecting') {
            indicator.textContent = '● reconnecting…';
            indicator.classList.add('yellow');
        } else {
            indicator.textContent = '● disconnected';
            indicator.classList.add('grey');
        }
    }

    /**
     * Handle a new event from the stream.
     * Data format: { event: section, data: json }
     */
    function handleStreamEvent(event) {
        try {
            if (event.type === 'close') {
                updateLiveIndicator('disconnected');
                return;
            }

            const section = event.type || event.name;
            const data = JSON.parse(event.data);

            switch (section) {
                case 'fleet':
                    updateFleetSection(data);
                    break;
                case 'tracker':
                    updateTrackerSection(data);
                    break;
                case 'events':
                    updateEventsSection(data);
                    break;
                case 'agents':
                    updateAgentsSection(data);
                    break;
            }
        } catch (err) {
            console.error('Failed to process stream event:', err);
        }
    }

    /**
     * Update the fleet status strip with fresh data.
     */
    function updateFleetSection(fleetStatus) {
        // Update heartbeat ages and status colors in the strip
        const statusPills = document.querySelectorAll('.status-pill');
        statusPills.forEach(pill => {
            const label = pill.querySelector('.label');
            if (!label) return;

            if (label.textContent.includes('watchdog')) {
                updateStatusPill(pill, fleetStatus.watchdog);
            } else if (label.textContent.includes('monitor')) {
                updateStatusPill(pill, fleetStatus.monitor);
            }
        });
    }

    /**
     * Update a single status pill with new heartbeat data.
     */
    function updateStatusPill(pill, heartbeat) {
        const ageSpan = pill.querySelector('.age');
        if (!ageSpan) return;

        // Remove old status classes
        pill.classList.remove('fresh', 'stale', 'missing');

        if (!heartbeat || !heartbeat.status) {
            ageSpan.textContent = 'missing';
            pill.classList.add('missing');
        } else {
            pill.classList.add(heartbeat.status.toLowerCase());
            ageSpan.textContent = heartbeat.ageSeconds !== null
                ? heartbeat.ageSeconds + 's'
                : 'missing';
        }
    }

    /**
     * Update the tracker table with new items.
     * Keep existing rows if not changed; prepend new ones at top.
     */
    function updateTrackerSection(trackerSnapshot) {
        const tbody = DOM.trackerTable();
        if (!tbody) return;

        const existingIds = new Set(
            Array.from(tbody.querySelectorAll('tr')).map(tr => {
                const idCell = tr.querySelector('.col-id');
                return idCell ? idCell.textContent : null;
            }).filter(Boolean)
        );

        // Add new items at top (prepend)
        trackerSnapshot.items.forEach(item => {
            if (!existingIds.has(item.id)) {
                const row = createTrackerRow(item);
                tbody.insertBefore(row, tbody.firstChild);
            }
        });

        // Keep only the most recent items (limit to prevent table explosion)
        while (tbody.querySelectorAll('tr').length > 100) {
            tbody.removeChild(tbody.lastChild);
        }
    }

    /**
     * Create a single tracker table row from item data.
     */
    function createTrackerRow(item) {
        const row = document.createElement('tr');
        row.classList.add('status-' + (item.status || 'unknown'));

        row.innerHTML = `
            <td class="col-id">${escapeHtml(item.id || '')}</td>
            <td class="col-title">${escapeHtml(item.title || '')}</td>
            <td class="col-status">${escapeHtml(item.status || '')}</td>
            <td class="col-priority">${escapeHtml(item.priority || '')}</td>
            <td class="col-lane">${escapeHtml(item.lane || '—')}</td>
        `;
        return row;
    }

    /**
     * Update the events table by prepending new events.
     */
    function updateEventsSection(events) {
        const tbody = DOM.eventsTable();
        if (!tbody) return;

        // events could be an array (from SSE) or a single event
        const eventList = Array.isArray(events) ? events : [events];

        eventList.forEach(event => {
            const row = createEventRow(event);
            tbody.insertBefore(row, tbody.firstChild);
        });

        // Keep only MAX_EVENTS_SHOWN
        while (tbody.querySelectorAll('tr').length > MAX_EVENTS_SHOWN) {
            tbody.removeChild(tbody.lastChild);
        }
    }

    /**
     * Create a single event table row.
     */
    function createEventRow(event) {
        const row = document.createElement('tr');
        row.classList.add('type-' + (event.type || 'unknown'));

        const ts = event.ts ? new Date(event.ts).toLocaleTimeString('en-US', {
            hour: '2-digit',
            minute: '2-digit',
            second: '2-digit',
            hour12: false
        }) : '—';

        row.innerHTML = `
            <td class="col-time monospace">${escapeHtml(ts)}</td>
            <td class="col-stream">${escapeHtml(event.stream || '')}</td>
            <td class="col-type">${escapeHtml(event.type || '')}</td>
            <td class="col-actor">${escapeHtml(event.actor || '')}</td>
            <td class="col-version monospace">${escapeHtml(event.version || '')}</td>
        `;
        return row;
    }

    /**
     * Update the agents section with new agent data.
     */
    function updateAgentsSection(agents) {
        const tbody = DOM.agentsTable();
        if (!tbody) return;

        if (!agents || agents.length === 0) {
            // Show empty state or clear table
            tbody.parentElement.parentElement.innerHTML = '<div class="empty-state">No agents currently running.</div>';
            return;
        }

        // Re-render all agents (they're transient, not keyed by ID in the same way)
        tbody.innerHTML = '';
        agents.forEach(agent => {
            const row = createAgentRow(agent);
            tbody.appendChild(row);
        });
    }

    /**
     * Create a single agent table row.
     */
    function createAgentRow(agent) {
        const row = document.createElement('tr');
        row.classList.add('state-' + (agent.state || 'unknown'));

        const since = agent.since ? new Date(agent.since).toLocaleString('en-US', {
            year: 'numeric',
            month: '2-digit',
            day: '2-digit',
            hour: '2-digit',
            minute: '2-digit',
            second: '2-digit',
            hour12: false
        }) : '—';

        row.innerHTML = `
            <td class="col-agent-id monospace">${escapeHtml(agent.agentId || '')}</td>
            <td class="col-state">${escapeHtml(agent.state || '')}</td>
            <td class="col-since monospace">${escapeHtml(since)}</td>
        `;
        return row;
    }

    /**
     * Escape HTML to prevent XSS.
     */
    function escapeHtml(text) {
        const map = {
            '&': '&amp;',
            '<': '&lt;',
            '>': '&gt;',
            '"': '&quot;',
            "'": '&#39;'
        };
        return String(text).replace(/[&<>"']/g, c => map[c]);
    }

    /**
     * Establish EventSource connection and handle reconnection.
     */
    function connect() {
        updateLiveIndicator('reconnecting');

        try {
            eventSource = new EventSource(STREAM_URL);

            eventSource.onopen = () => {
                reconnectAttempt = 0;
                updateLiveIndicator('connected');
                console.log('Connected to event stream');
            };

            eventSource.onmessage = (event) => {
                handleStreamEvent(event);
            };

            eventSource.onerror = (event) => {
                console.error('Event stream error:', event);
                eventSource.close();
                updateLiveIndicator('disconnected');
                scheduleReconnect();
            };

            // Listen for specific event types (fleet, tracker, events, agents)
            ['fleet', 'tracker', 'events', 'agents'].forEach(section => {
                eventSource.addEventListener(section, handleStreamEvent);
            });

        } catch (err) {
            console.error('Failed to create EventSource:', err);
            updateLiveIndicator('disconnected');
            scheduleReconnect();
        }
    }

    /**
     * Schedule a reconnect attempt with exponential backoff.
     */
    function scheduleReconnect() {
        if (reconnectTimer) clearTimeout(reconnectTimer);

        const delay = RECONNECT_DELAYS[Math.min(reconnectAttempt, RECONNECT_DELAYS.length - 1)];
        reconnectAttempt++;

        console.log(`Reconnecting in ${delay}ms (attempt ${reconnectAttempt})`);
        reconnectTimer = setTimeout(connect, delay);
    }

    /**
     * Initialize the dashboard: connect to SSE stream.
     */
    function init() {
        console.log('Initializing AesopDashboard');
        updateLiveIndicator('disconnected');
        connect();
    }

    // Start when DOM is ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

    // Cleanup on page unload
    window.addEventListener('beforeunload', () => {
        if (eventSource) {
            eventSource.close();
        }
        if (reconnectTimer) {
            clearTimeout(reconnectTimer);
        }
    });
})();
