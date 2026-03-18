package com.wonderingwizard.server;

import com.wonderingwizard.engine.Engine;
import com.wonderingwizard.engine.EventProcessingEngine;
import com.wonderingwizard.engine.EventPropagatingEngine;
import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.TimeEvent;
import com.wonderingwizard.events.WorkInstructionEvent;
import com.wonderingwizard.events.MoveStage;
import com.wonderingwizard.events.WorkQueueMessage;
import com.wonderingwizard.events.WorkQueueStatus;
import com.wonderingwizard.processors.ScheduleRunnerProcessor;
import com.wonderingwizard.processors.WorkQueueProcessor;
import com.wonderingwizard.sideeffects.ActionActivated;
import com.wonderingwizard.sideeffects.ScheduleCreated;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DemoServer Tests")
class DemoServerTest {

    private DemoServer server;

    @BeforeEach
    void setUp() {
        server = new DemoServer();
    }

    @Nested
    @DisplayName("Step Tracking")
    class StepTrackingTests {

        @Test
        @DisplayName("Should track steps with sequential numbering")
        void tracksSteps() {
            server.processStep("WI 1", new WorkInstructionEvent(
                    1L, 1L, "RTG-01", MoveStage.PLANNED,
                    Instant.parse("2024-01-01T00:05:00Z"), 120));
            server.processStep("WI 2", new WorkInstructionEvent(
                    2L, 1L, "RTG-02", MoveStage.PLANNED,
                    Instant.parse("2024-01-01T00:05:00Z"), 120));

            List<DemoServer.Step> steps = server.getSteps();
            assertEquals(2, steps.size());
            assertEquals(1, steps.get(0).stepNumber());
            assertEquals(2, steps.get(1).stepNumber());
            assertEquals("WI 1", steps.get(0).description());
            assertEquals("WI 2", steps.get(1).description());
        }

        @Test
        @DisplayName("Should record side effects in steps")
        void recordsSideEffects() {
            server.processStep("WI 1", new WorkInstructionEvent(
                    1L, 1L, "RTG-01", MoveStage.PLANNED,
                    Instant.parse("2024-01-01T00:05:00Z"), 120));

            DemoServer.StepResult result = server.processStep("Activate WQ",
                    new WorkQueueMessage(1L, WorkQueueStatus.ACTIVE, 0, null));

            assertFalse(result.sideEffects().isEmpty());
            boolean hasScheduleCreated = result.sideEffects().stream().anyMatch(se -> se instanceof ScheduleCreated);
            assertTrue(hasScheduleCreated);
        }
    }

    @Nested
    @DisplayName("Step Back")
    class StepBackTests {

        @Test
        @DisplayName("Should step back to target step")
        void stepsBackToTarget() {
            server.processStep("WI 1", new WorkInstructionEvent(
                    1L, 1L, "RTG-01", MoveStage.PLANNED,
                    Instant.parse("2024-01-01T00:05:00Z"), 120));
            server.processStep("WI 2", new WorkInstructionEvent(
                    2L, 1L, "RTG-02", MoveStage.PLANNED,
                    Instant.parse("2024-01-01T00:05:00Z"), 120));
            server.processStep("Activate WQ",
                    new WorkQueueMessage(1L, WorkQueueStatus.ACTIVE, 0, null));

            assertEquals(3, server.getSteps().size());

            boolean success = server.stepBackTo(1);
            assertTrue(success);
            assertEquals(1, server.getSteps().size());
        }

        @Test
        @DisplayName("Should step back to beginning (step 0)")
        void stepsBackToBeginning() {
            server.processStep("WI 1", new WorkInstructionEvent(
                    1L, 1L, "RTG-01", MoveStage.PLANNED,
                    Instant.parse("2024-01-01T00:05:00Z"), 120));
            server.processStep("WI 2", new WorkInstructionEvent(
                    2L, 1L, "RTG-02", MoveStage.PLANNED,
                    Instant.parse("2024-01-01T00:05:00Z"), 120));

            boolean success = server.stepBackTo(0);
            assertTrue(success);
            assertEquals(0, server.getSteps().size());
        }

        @Test
        @DisplayName("Should return false for invalid target step")
        void returnsFalseForInvalidStep() {
            server.processStep("WI 1", new WorkInstructionEvent(
                    1L, 1L, "RTG-01", MoveStage.PLANNED,
                    Instant.parse("2024-01-01T00:05:00Z"), 120));

            assertFalse(server.stepBackTo(-1));
            assertFalse(server.stepBackTo(5));
        }

        @Test
        @DisplayName("Should correctly handle step-back with EventPropagatingEngine expansion")
        void handlesStepBackWithPropagation() {
            // Register work instructions
            server.processStep("WI 1", new WorkInstructionEvent(
                    1L, 1L, "RTG-01", MoveStage.PLANNED,
                    Instant.parse("2024-01-01T00:05:00Z"), 120));

            // Activate - this creates ScheduleCreated which propagates
            server.processStep("Activate WQ",
                    new WorkQueueMessage(1L, WorkQueueStatus.ACTIVE, 0, null));

            assertEquals(2, server.getSteps().size());

            // Step back to before activation
            boolean success = server.stepBackTo(1);
            assertTrue(success);
            assertEquals(1, server.getSteps().size());

            // Re-activate should produce ScheduleCreated again
            DemoServer.StepResult reactivateResult = server.processStep("Activate WQ again",
                    new WorkQueueMessage(1L, WorkQueueStatus.ACTIVE, 0, null));
            boolean hasScheduleCreated = reactivateResult.sideEffects().stream().anyMatch(se -> se instanceof ScheduleCreated);
            assertTrue(hasScheduleCreated);
        }
    }

    @Nested
    @DisplayName("State Retrieval")
    class StateTests {

        @Test
        @DisplayName("Should return empty state initially")
        void returnsEmptyState() {
            Map<String, Object> state = server.getState();
            assertNotNull(state.get("currentTime"));
            assertTrue(((List<?>) state.get("steps")).isEmpty());
            assertTrue(((List<?>) state.get("schedules")).isEmpty());
        }

        @Test
        @DisplayName("Should include schedules in state after activation")
        void includesSchedulesInState() {
            server.processStep("WI 1", new WorkInstructionEvent(
                    1L, 1L, "RTG-01", MoveStage.PLANNED,
                    Instant.parse("2024-01-01T00:05:00Z"), 120));
            server.processStep("Activate WQ",
                    new WorkQueueMessage(1L, WorkQueueStatus.ACTIVE, 0, null));

            Map<String, Object> state = server.getState();
            List<?> schedules = (List<?>) state.get("schedules");
            assertEquals(1, schedules.size());

            DemoServer.ScheduleView schedule = (DemoServer.ScheduleView) schedules.get(0);
            assertEquals(1L, schedule.workQueueId());
            assertTrue(schedule.active());
            assertFalse(schedule.takts().isEmpty());
        }

        @Test
        @DisplayName("Should show action statuses correctly after activation and completion")
        void showsActionStatuses() {
            Instant emt = Instant.parse("2024-01-01T00:05:00Z");
            server.processStep("WI 1", new WorkInstructionEvent(
                    1L, 1L, "RTG-01", MoveStage.PLANNED, emt, 120));
            server.processStep("Activate WQ",
                    new WorkQueueMessage(1L, WorkQueueStatus.ACTIVE, 0, null));

            // All actions should be PENDING initially
            Map<String, Object> state = server.getState();
            List<DemoServer.ScheduleView> schedules = (List<DemoServer.ScheduleView>) state.get("schedules");
            DemoServer.ScheduleView schedule = schedules.get(0);
            for (DemoServer.TaktView takt : schedule.takts()) {
                for (DemoServer.ActionView action : takt.actions()) {
                    assertEquals(DemoServer.ActionState.PENDING, action.status());
                }
            }

            // Tick time past estimated move time - should activate root actions
            server.processStep("Tick", new TimeEvent(emt.plusSeconds(1)));

            state = server.getState();
            schedules = (List<DemoServer.ScheduleView>) state.get("schedules");
            schedule = schedules.get(0);

            // At least one action should now be ACTIVE
            boolean hasActive = schedule.takts().stream()
                    .flatMap(t -> t.actions().stream())
                    .anyMatch(a -> a.status() == DemoServer.ActionState.ACTIVE);
            assertTrue(hasActive);
        }

        @Test
        @DisplayName("Should serialize state to valid JSON")
        void serializesStateToJson() {
            server.processStep("WI 1", new WorkInstructionEvent(
                    1L, 1L, "RTG-01", MoveStage.PLANNED,
                    Instant.parse("2024-01-01T00:05:00Z"), 120));
            server.processStep("Activate WQ",
                    new WorkQueueMessage(1L, WorkQueueStatus.ACTIVE, 0, null));

            String json = JsonSerializer.serialize(server.getState());
            assertNotNull(json);
            assertTrue(json.startsWith("{"));
            assertTrue(json.endsWith("}"));
            assertTrue(json.contains("\"currentTime\""));
            assertTrue(json.contains("\"steps\""));
            assertTrue(json.contains("\"schedules\""));
            assertTrue(json.contains("\"workQueueId\":1"));
        }
    }

    @Nested
    @DisplayName("Full Workflow")
    class FullWorkflowTests {

        @Test
        @DisplayName("Should execute full demo workflow: add WIs, activate, tick, complete")
        void fullDemoWorkflow() {
            Instant emt = Instant.parse("2024-01-01T00:05:00Z");

            // Step 1: Add work instruction
            DemoServer.StepResult step1 = server.processStep("Add WI",
                    new WorkInstructionEvent(1L, 1L, "RTG-01",
                            MoveStage.PLANNED, emt, 120));
            assertTrue(step1.sideEffects().isEmpty());

            // Step 2: Activate work queue
            DemoServer.StepResult step2 = server.processStep("Activate",
                    new WorkQueueMessage(1L, WorkQueueStatus.ACTIVE, 0, null));
            assertTrue(step2.sideEffects().stream().anyMatch(se -> se instanceof ScheduleCreated));

            // Step 3: Tick time
            DemoServer.StepResult step3 = server.processStep("Tick",
                    new TimeEvent(emt.plusSeconds(1)));
            assertTrue(step3.sideEffects().stream().anyMatch(se -> se instanceof ActionActivated));

            // Find the active action ID
            ActionActivated activated = (ActionActivated) step3.sideEffects().stream()
                    .filter(se -> se instanceof ActionActivated)
                    .findFirst().orElseThrow();

            // Step 4: Complete the action
            DemoServer.StepResult step4 = server.processStep("Complete",
                    new com.wonderingwizard.events.ActionCompletedEvent(
                            activated.actionId(), 1L));
            assertFalse(step4.sideEffects().isEmpty());

            // Verify state
            Map<String, Object> state = server.getState();
            assertEquals(4, ((List<?>) state.get("steps")).size());

            // Step 5: Step back to after activation
            boolean success = server.stepBackTo(2);
            assertTrue(success);
            assertEquals(2, server.getSteps().size());

            // Actions should be back to PENDING after step-back
            state = server.getState();
            List<DemoServer.ScheduleView> schedules = (List<DemoServer.ScheduleView>) state.get("schedules");
            assertFalse(schedules.isEmpty());
        }
    }

    @Nested
    @DisplayName("HTTP Endpoints")
    class HttpEndpointTests {

        private DemoServer httpServer;

        @BeforeEach
        void startServer() throws Exception {
            var props = new java.util.Properties();
            props.setProperty("kafka.enabled", "false");
            httpServer = new DemoServer(Settings.of(props));
            httpServer.start(0); // random available port
        }

        @AfterEach
        void stopServer() {
            httpServer.stop();
        }

        private int getPort() throws Exception {
            var field = DemoServer.class.getDeclaredField("httpServer");
            field.setAccessible(true);
            var server = (com.sun.net.httpserver.HttpServer) field.get(httpServer);
            return server.getAddress().getPort();
        }

        @Test
        @DisplayName("Should serve work instructions page at /workinstructions")
        void servesWorkInstructionsPage() throws Exception {
            int port = getPort();
            HttpURLConnection conn = (HttpURLConnection) URI.create(
                    "http://localhost:" + port + "/workinstructions").toURL().openConnection();
            conn.setRequestMethod("GET");
            assertEquals(200, conn.getResponseCode());
            assertEquals("text/html; charset=UTF-8", conn.getHeaderField("Content-Type"));
            String body = new String(conn.getInputStream().readAllBytes());
            assertTrue(body.contains("Work Instructions"));
            assertTrue(body.contains("WorkInstructionEvent"));
        }

        @Test
        @DisplayName("Should serve main page at /")
        void servesMainPage() throws Exception {
            int port = getPort();
            HttpURLConnection conn = (HttpURLConnection) URI.create(
                    "http://localhost:" + port + "/").toURL().openConnection();
            conn.setRequestMethod("GET");
            assertEquals(200, conn.getResponseCode());
        }

        @Test
        @DisplayName("Should serve editor page at /editor")
        void servesEditorPage() throws Exception {
            int port = getPort();
            HttpURLConnection conn = (HttpURLConnection) URI.create(
                    "http://localhost:" + port + "/editor").toURL().openConnection();
            conn.setRequestMethod("GET");
            assertEquals(200, conn.getResponseCode());
        }

        @Test
        @DisplayName("Should serve work queues page at /workqueues")
        void servesWorkQueuesPage() throws Exception {
            int port = getPort();
            HttpURLConnection conn = (HttpURLConnection) URI.create(
                    "http://localhost:" + port + "/workqueues").toURL().openConnection();
            conn.setRequestMethod("GET");
            assertEquals(200, conn.getResponseCode());
            assertEquals("text/html; charset=UTF-8", conn.getHeaderField("Content-Type"));
            String body = new String(conn.getInputStream().readAllBytes());
            assertTrue(body.contains("Work Queues"));
            assertTrue(body.contains("WorkQueueMessage"));
        }
        @Test
        @DisplayName("Should return version from release-notes.html via /api/version")
        void returnsVersion() throws Exception {
            int port = getPort();
            HttpURLConnection conn = (HttpURLConnection) URI.create(
                    "http://localhost:" + port + "/api/version").toURL().openConnection();
            conn.setRequestMethod("GET");
            assertEquals(200, conn.getResponseCode());
            assertEquals("application/json; charset=UTF-8", conn.getHeaderField("Content-Type"));
            String body = new String(conn.getInputStream().readAllBytes());
            assertTrue(body.contains("\"version\""));
            assertTrue(body.contains("4.0.13"));
        }

        @Test
        @DisplayName("Should serve release notes page at /release-notes")
        void servesReleaseNotesPage() throws Exception {
            int port = getPort();
            HttpURLConnection conn = (HttpURLConnection) URI.create(
                    "http://localhost:" + port + "/release-notes").toURL().openConnection();
            conn.setRequestMethod("GET");
            assertEquals(200, conn.getResponseCode());
            assertEquals("text/html; charset=UTF-8", conn.getHeaderField("Content-Type"));
            String body = new String(conn.getInputStream().readAllBytes());
            assertTrue(body.contains("Release Notes"));
            assertTrue(body.contains("v4.0.0"));
        }

        @Test
        @DisplayName("Should reject non-GET requests to /api/version")
        void rejectsNonGetVersion() throws Exception {
            int port = getPort();
            HttpURLConnection conn = (HttpURLConnection) URI.create(
                    "http://localhost:" + port + "/api/version").toURL().openConnection();
            conn.setRequestMethod("POST");
            assertEquals(405, conn.getResponseCode());
        }
    }
}
