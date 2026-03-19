package com.wonderingwizard.e2e;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end performance test that interacts with a running server via HTTP.
 * <p>
 * Start the server first: {@code mvn exec:java}
 * Then run: {@code mvn test -Pe2e}
 */
@Tag("e2e")
@DisplayName("E2E: Schedule Creation Performance")
class ScheduleCreationPerformanceE2ETest {

    private static final String BASE_URL = System.getProperty("e2e.baseUrl", "http://localhost:8080");
    private static final int WORK_QUEUES = 50;
    private static final int WORK_INSTRUCTIONS_PER_QUEUE = 300;

    @Test
    @DisplayName("Create 1000 WIs per WQ x 10 WQs, activate all, measure schedule creation time")
    void shouldCreateSchedulesForAllWorkQueues() throws Exception {
        // Step 1: Verify server is running
        assertServerIsReachable();

        // Step 2: Create 1000 WorkInstructions per WQ for 10 WQs (10,000 total)
        System.out.println("Creating " + WORK_QUEUES * WORK_INSTRUCTIONS_PER_QUEUE
                + " work instructions across " + WORK_QUEUES + " work queues...");

        long wiStartTime = System.nanoTime();
        createAllWorkInstructions();
        long wiDurationMs = (System.nanoTime() - wiStartTime) / 1_000_000;
        System.out.println("Work instructions created in " + wiDurationMs + " ms");

        // Step 3: Activate all 10 WQs and measure time to get all ScheduleCreated back
        System.out.println("Activating all " + WORK_QUEUES + " work queues...");

        long activateStartTime = System.nanoTime();
        List<ActivationResult> results = activateAllWorkQueues();
        long activateDurationMs = (System.nanoTime() - activateStartTime) / 1_000_000;

        // Step 4: Verify all 10 schedules were created
        System.out.println("\n=== Results ===");
        System.out.println("Total activation time (all " + WORK_QUEUES + " WQs): " + activateDurationMs + " ms");

        int schedulesCreated = 0;
        for (ActivationResult result : results) {
            boolean hasScheduleCreated = result.responseBody.contains("ScheduleCreated");
            if (hasScheduleCreated) {
                schedulesCreated++;
            }
            System.out.println("  WQ " + result.workQueueId
                    + ": " + result.durationMs + " ms"
                    + " | ScheduleCreated: " + hasScheduleCreated
                    + " | HTTP " + result.statusCode);
        }

        System.out.println("Schedules created: " + schedulesCreated + "/" + WORK_QUEUES);
        System.out.println("Total time (WI creation + activation): " + (wiDurationMs + activateDurationMs) + " ms");

        // Step 5: Verify via /api/state that all 10 schedules exist
        String state = get("/api/state");
        int scheduleCount = countOccurrences(state, "\"workQueueId\"");
        System.out.println("Schedules in state: " + scheduleCount);

        assertEquals(WORK_QUEUES, schedulesCreated,
                "Expected all " + WORK_QUEUES + " work queues to produce ScheduleCreated");
        assertTrue(scheduleCount >= WORK_QUEUES,
                "Expected at least " + WORK_QUEUES + " schedules in state, found " + scheduleCount);
    }

    private void assertServerIsReachable() {
        try {
            String response = get("/api/state");
            assertNotNull(response, "Server returned null response");
        } catch (Exception e) {
            fail("Server not reachable at " + BASE_URL + ". Start the server first with: mvn exec:java\n" + e.getMessage());
        }
    }

    private void createAllWorkInstructions() throws Exception {
        Instant baseTime = Instant.parse("2026-03-17T08:00:00Z");

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (int wq = 1; wq <= WORK_QUEUES; wq++) {
                for (int wi = 1; wi <= WORK_INSTRUCTIONS_PER_QUEUE; wi++) {
                    long workQueueId = wq;
                    long workInstructionId = wq * 100_000L + wi;
                    Instant estimatedMoveTime = baseTime.plusSeconds(wi * 120L);

                    String body = """
                            {
                              "eventType": "WI Created",
                              "workInstructionId": %d,
                              "workQueueId": %d,
                              "fetchChe": "RTG-%02d",
                              "workInstructionMoveStage": "Planned",
                              "estimatedMoveTime": "%s",
                              "estimatedCycleTimeSeconds": 120,
                              "estimatedRtgCycleTimeSeconds": 60,
                              "putChe": "QC-%02d",
                              "isTwinFetch": false,
                              "isTwinPut": false,
                              "isTwinCarry": false,
                              "containerId": "CONT%07d"
                            }
                            """.formatted(workInstructionId, workQueueId, wq,
                            estimatedMoveTime, wq, (int) workInstructionId);

                    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                        try {
                            int status = post("/api/work-instruction", body);
                            if (status != 200) {
                                System.err.println("Failed to create WI " + workInstructionId + ": HTTP " + status);
                            }
                        } catch (IOException e) {
                            System.err.println("Failed to create WI " + workInstructionId + ": " + e.getMessage());
                        }
                    }, executor);
                    futures.add(future);
                }
            }

            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        }
    }

    private List<ActivationResult> activateAllWorkQueues() throws Exception {
        List<ActivationResult> results = new ArrayList<>();

        for (int wq = 1; wq <= WORK_QUEUES; wq++) {
            String body = """
                    {
                      "workQueueId": %d,
                      "status": "ACTIVE"
                    }
                    """.formatted(wq);

            long start = System.nanoTime();
            HttpURLConnection conn = openPostConnection("/api/work-queue", body);
            int statusCode = conn.getResponseCode();
            String responseBody = readResponse(conn);
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            conn.disconnect();

            results.add(new ActivationResult(wq, statusCode, responseBody, durationMs));
        }

        return results;
    }

    private String get(String path) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(BASE_URL + path).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(30000);
        String response = readResponse(conn);
        conn.disconnect();
        return response;
    }

    private int post(String path, String body) throws IOException {
        HttpURLConnection conn = openPostConnection(path, body);
        int status = conn.getResponseCode();
        conn.disconnect();
        return status;
    }

    private HttpURLConnection openPostConnection(String path, String body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(BASE_URL + path).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(120000);
        conn.setRequestProperty("Content-Type", "application/json");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }
        return conn;
    }

    private String readResponse(HttpURLConnection conn) throws IOException {
        var stream = conn.getResponseCode() >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (stream == null) return "";
        return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static int countOccurrences(String text, String search) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(search, idx)) != -1) {
            count++;
            idx += search.length();
        }
        return count;
    }

    private record ActivationResult(long workQueueId, int statusCode, String responseBody, long durationMs) {}
}
