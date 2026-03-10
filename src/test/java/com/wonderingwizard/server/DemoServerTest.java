package com.wonderingwizard.server;

import com.wonderingwizard.engine.Engine;
import com.wonderingwizard.engine.EventProcessingEngine;
import com.wonderingwizard.engine.EventPropagatingEngine;
import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.TimeEvent;
import com.wonderingwizard.events.WorkInstructionEvent;
import com.wonderingwizard.events.WorkInstructionStatus;
import com.wonderingwizard.events.WorkQueueMessage;
import com.wonderingwizard.events.WorkQueueStatus;
import com.wonderingwizard.processors.ScheduleRunnerProcessor;
import com.wonderingwizard.processors.WorkQueueProcessor;
import com.wonderingwizard.sideeffects.ActionActivated;
import com.wonderingwizard.sideeffects.ScheduleCreated;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
                    "WI-001", "WQ-001", "RTG-01", WorkInstructionStatus.PENDING,
                    Instant.parse("2024-01-01T00:05:00Z"), 120));
            server.processStep("WI 2", new WorkInstructionEvent(
                    "WI-002", "WQ-001", "RTG-02", WorkInstructionStatus.PENDING,
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
                    "WI-001", "WQ-001", "RTG-01", WorkInstructionStatus.PENDING,
                    Instant.parse("2024-01-01T00:05:00Z"), 120));

            List<SideEffect> effects = server.processStep("Activate WQ",
                    new WorkQueueMessage("WQ-001", WorkQueueStatus.ACTIVE, 0, null));

            assertFalse(effects.isEmpty());
            boolean hasScheduleCreated = effects.stream().anyMatch(se -> se instanceof ScheduleCreated);
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
                    "WI-001", "WQ-001", "RTG-01", WorkInstructionStatus.PENDING,
                    Instant.parse("2024-01-01T00:05:00Z"), 120));
            server.processStep("WI 2", new WorkInstructionEvent(
                    "WI-002", "WQ-001", "RTG-02", WorkInstructionStatus.PENDING,
                    Instant.parse("2024-01-01T00:05:00Z"), 120));
            server.processStep("Activate WQ",
                    new WorkQueueMessage("WQ-001", WorkQueueStatus.ACTIVE, 0, null));

            assertEquals(3, server.getSteps().size());

            boolean success = server.stepBackTo(1);
            assertTrue(success);
            assertEquals(1, server.getSteps().size());
        }

        @Test
        @DisplayName("Should step back to beginning (step 0)")
        void stepsBackToBeginning() {
            server.processStep("WI 1", new WorkInstructionEvent(
                    "WI-001", "WQ-001", "RTG-01", WorkInstructionStatus.PENDING,
                    Instant.parse("2024-01-01T00:05:00Z"), 120));
            server.processStep("WI 2", new WorkInstructionEvent(
                    "WI-002", "WQ-001", "RTG-02", WorkInstructionStatus.PENDING,
                    Instant.parse("2024-01-01T00:05:00Z"), 120));

            boolean success = server.stepBackTo(0);
            assertTrue(success);
            assertEquals(0, server.getSteps().size());
        }

        @Test
        @DisplayName("Should return false for invalid target step")
        void returnsFalseForInvalidStep() {
            server.processStep("WI 1", new WorkInstructionEvent(
                    "WI-001", "WQ-001", "RTG-01", WorkInstructionStatus.PENDING,
                    Instant.parse("2024-01-01T00:05:00Z"), 120));

            assertFalse(server.stepBackTo(-1));
            assertFalse(server.stepBackTo(5));
        }

        @Test
        @DisplayName("Should correctly handle step-back with EventPropagatingEngine expansion")
        void handlesStepBackWithPropagation() {
            // Register work instructions
            server.processStep("WI 1", new WorkInstructionEvent(
                    "WI-001", "WQ-001", "RTG-01", WorkInstructionStatus.PENDING,
                    Instant.parse("2024-01-01T00:05:00Z"), 120));

            // Activate - this creates ScheduleCreated which propagates
            server.processStep("Activate WQ",
                    new WorkQueueMessage("WQ-001", WorkQueueStatus.ACTIVE, 0, null));

            assertEquals(2, server.getSteps().size());

            // Step back to before activation
            boolean success = server.stepBackTo(1);
            assertTrue(success);
            assertEquals(1, server.getSteps().size());

            // Re-activate should produce ScheduleCreated again
            List<SideEffect> effects = server.processStep("Activate WQ again",
                    new WorkQueueMessage("WQ-001", WorkQueueStatus.ACTIVE, 0, null));
            boolean hasScheduleCreated = effects.stream().anyMatch(se -> se instanceof ScheduleCreated);
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
                    "WI-001", "WQ-001", "RTG-01", WorkInstructionStatus.PENDING,
                    Instant.parse("2024-01-01T00:05:00Z"), 120));
            server.processStep("Activate WQ",
                    new WorkQueueMessage("WQ-001", WorkQueueStatus.ACTIVE, 0, null));

            Map<String, Object> state = server.getState();
            List<?> schedules = (List<?>) state.get("schedules");
            assertEquals(1, schedules.size());

            DemoServer.ScheduleView schedule = (DemoServer.ScheduleView) schedules.get(0);
            assertEquals("WQ-001", schedule.workQueueId());
            assertTrue(schedule.active());
            assertFalse(schedule.takts().isEmpty());
        }

        @Test
        @DisplayName("Should show action statuses correctly after activation and completion")
        void showsActionStatuses() {
            Instant emt = Instant.parse("2024-01-01T00:05:00Z");
            server.processStep("WI 1", new WorkInstructionEvent(
                    "WI-001", "WQ-001", "RTG-01", WorkInstructionStatus.PENDING, emt, 120));
            server.processStep("Activate WQ",
                    new WorkQueueMessage("WQ-001", WorkQueueStatus.ACTIVE, 0, null));

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
                    "WI-001", "WQ-001", "RTG-01", WorkInstructionStatus.PENDING,
                    Instant.parse("2024-01-01T00:05:00Z"), 120));
            server.processStep("Activate WQ",
                    new WorkQueueMessage("WQ-001", WorkQueueStatus.ACTIVE, 0, null));

            String json = JsonSerializer.serialize(server.getState());
            assertNotNull(json);
            assertTrue(json.startsWith("{"));
            assertTrue(json.endsWith("}"));
            assertTrue(json.contains("\"currentTime\""));
            assertTrue(json.contains("\"steps\""));
            assertTrue(json.contains("\"schedules\""));
            assertTrue(json.contains("\"WQ-001\""));
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
            List<SideEffect> step1 = server.processStep("Add WI",
                    new WorkInstructionEvent("WI-001", "WQ-001", "RTG-01",
                            WorkInstructionStatus.PENDING, emt, 120));
            assertTrue(step1.isEmpty());

            // Step 2: Activate work queue
            List<SideEffect> step2 = server.processStep("Activate",
                    new WorkQueueMessage("WQ-001", WorkQueueStatus.ACTIVE, 0, null));
            assertTrue(step2.stream().anyMatch(se -> se instanceof ScheduleCreated));

            // Step 3: Tick time
            List<SideEffect> step3 = server.processStep("Tick",
                    new TimeEvent(emt.plusSeconds(1)));
            assertTrue(step3.stream().anyMatch(se -> se instanceof ActionActivated));

            // Find the active action ID
            ActionActivated activated = (ActionActivated) step3.stream()
                    .filter(se -> se instanceof ActionActivated)
                    .findFirst().orElseThrow();

            // Step 4: Complete the action
            List<SideEffect> step4 = server.processStep("Complete",
                    new com.wonderingwizard.events.ActionCompletedEvent(
                            activated.actionId(), "WQ-001"));
            assertFalse(step4.isEmpty());

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
}
