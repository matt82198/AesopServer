package com.aesop.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the InboxService.
 * Validates JSON format, file encoding, concurrent appends, and file lock safety.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {"aesop.inbox-path="})  // Will be overridden per test
class InboxServiceTest {
    @Autowired
    private ObjectMapper objectMapper;

    @TempDir
    private Path tempDir;

    @Test
    void testAppendTrackerTransition_JsonFormat() throws IOException {
        Path inboxPath = tempDir.resolve("inbox.jsonl");
        InboxService service = new InboxService(inboxPath.toString(), objectMapper);

        service.appendTrackerTransition("item-1", "done", "Completed");

        // Read the file and verify format
        List<String> lines = Files.readAllLines(inboxPath);
        assertEquals(1, lines.size(), "Should have one line");

        // Parse the JSON object
        Map<String, Object> obj = objectMapper.readValue(lines.get(0), Map.class);

        // Verify fields
        assertEquals("aesop-server", obj.get("source"));
        assertEquals("tracker-transition", obj.get("kind"));
        assertEquals("item-1", obj.get("itemId"));
        assertEquals("done", obj.get("targetStatus"));
        assertEquals("Completed", obj.get("note"));
        assertNotNull(obj.get("ts"), "ts field should be present");

        // Verify ts is a valid ISO-8601 instant
        String ts = (String) obj.get("ts");
        assertDoesNotThrow(() -> java.time.Instant.parse(ts));
    }

    @Test
    void testAppendTrackerTransition_WithoutNote() throws IOException {
        Path inboxPath = tempDir.resolve("inbox.jsonl");
        InboxService service = new InboxService(inboxPath.toString(), objectMapper);

        service.appendTrackerTransition("item-1", "blocked", null);

        List<String> lines = Files.readAllLines(inboxPath);
        Map<String, Object> obj = objectMapper.readValue(lines.get(0), Map.class);

        assertEquals("item-1", obj.get("itemId"));
        assertEquals("blocked", obj.get("targetStatus"));
        assertFalse(obj.containsKey("note"), "note field should not be present if null");
    }

    @Test
    void testAppendNote_Format() throws IOException {
        Path inboxPath = tempDir.resolve("inbox.jsonl");
        InboxService service = new InboxService(inboxPath.toString(), objectMapper);

        service.appendNote("Some note text");

        List<String> lines = Files.readAllLines(inboxPath);
        Map<String, Object> obj = objectMapper.readValue(lines.get(0), Map.class);

        assertEquals("aesop-server", obj.get("source"));
        assertEquals("note", obj.get("kind"));
        assertEquals("Some note text", obj.get("text"));
        assertNotNull(obj.get("ts"));
    }

    @Test
    void testAppendTrackerTransition_FileEncoding_Utf8() throws IOException {
        Path inboxPath = tempDir.resolve("inbox.jsonl");
        InboxService service = new InboxService(inboxPath.toString(), objectMapper);

        // Use non-ASCII characters to test UTF-8 encoding
        // Using é (Latin small letter e with acute)
        String noteWithAccent = "Complété with accent";
        service.appendTrackerTransition("item-1", "done", noteWithAccent);

        // Verify JSON can be parsed
        List<String> lines = Files.readAllLines(inboxPath);
        assertEquals(1, lines.size(), "Should have one line");
        Map<String, Object> obj = objectMapper.readValue(lines.get(0), Map.class);
        assertEquals(noteWithAccent, obj.get("note"),
            "UTF-8 content should be preserved in JSON");
    }

    @Test
    void testAppendTrackerTransition_MultipleAppends() throws IOException {
        Path inboxPath = tempDir.resolve("inbox.jsonl");
        InboxService service = new InboxService(inboxPath.toString(), objectMapper);

        service.appendTrackerTransition("item-1", "done", "First");
        service.appendTrackerTransition("item-2", "blocked", "Second");
        service.appendTrackerTransition("item-3", "open", "Third");

        List<String> lines = Files.readAllLines(inboxPath);
        assertEquals(3, lines.size(), "Should have 3 lines");

        // Parse all and verify
        Map<String, Object> obj1 = objectMapper.readValue(lines.get(0), Map.class);
        Map<String, Object> obj2 = objectMapper.readValue(lines.get(1), Map.class);
        Map<String, Object> obj3 = objectMapper.readValue(lines.get(2), Map.class);

        assertEquals("item-1", obj1.get("itemId"));
        assertEquals("item-2", obj2.get("itemId"));
        assertEquals("item-3", obj3.get("itemId"));
    }

    @Test
    void testConcurrentAppends_TenThreads_AllSucceed() throws InterruptedException, IOException {
        Path inboxPath = tempDir.resolve("inbox.jsonl");
        InboxService service = new InboxService(inboxPath.toString(), objectMapper);

        int threadCount = 10;
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    service.appendTrackerTransition(
                        "item-" + index,
                        "done",
                        "Concurrent-" + index
                    );
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assertEquals(threadCount, successCount.get(), "All appends should succeed");

        // Verify all lines are intact and parseable
        List<String> lines = Files.readAllLines(inboxPath);
        assertEquals(threadCount, lines.size(), "Should have exactly " + threadCount + " lines");

        Set<String> itemIds = new HashSet<>();
        for (String line : lines) {
            Map<String, Object> obj = objectMapper.readValue(line, Map.class);
            assertEquals("aesop-server", obj.get("source"));
            assertEquals("tracker-transition", obj.get("kind"));
            String itemId = (String) obj.get("itemId");
            itemIds.add(itemId);
            assertNotNull(obj.get("ts"));
        }

        assertEquals(threadCount, itemIds.size(), "Should have " + threadCount + " unique item IDs");
    }

    @Test
    void testNoConfigured_ThrowsRuntimeException() {
        InboxService service = new InboxService("", objectMapper);
        assertThrows(RuntimeException.class, () -> service.appendNote("test"));
    }

    @Test
    void testIsConfigured() {
        InboxService service1 = new InboxService("", objectMapper);
        assertFalse(service1.isConfigured());

        InboxService service2 = new InboxService("/tmp/inbox", objectMapper);
        assertTrue(service2.isConfigured());
    }
}
