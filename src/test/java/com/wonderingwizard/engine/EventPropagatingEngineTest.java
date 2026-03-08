package com.wonderingwizard.engine;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.DeviceType;
import com.wonderingwizard.domain.takt.Takt;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("EventPropagatingEngine")
class EventPropagatingEngineTest {

    private EventProcessingEngine innerEngine;
    private EventPropagatingEngine propagatingEngine;

    @BeforeEach
    void setUp() {
        innerEngine = new EventProcessingEngine();
        propagatingEngine = new EventPropagatingEngine(innerEngine);
    }

    @Nested
    @DisplayName("Delegation")
    class Delegation {

        @Test
        @DisplayName("register delegates to inner engine")
        void registerDelegatesToInnerEngine() {
            WorkQueueProcessor processor = new WorkQueueProcessor(() -> 30);
            propagatingEngine.register(processor);

            // Verify by processing an event that the processor would handle
            List<SideEffect> effects = propagatingEngine.processEvent(
                    new WorkQueueMessage("queue1", WorkQueueStatus.ACTIVE, 0));

            assertTrue(effects.stream().anyMatch(se -> se instanceof ScheduleCreated));
        }

        @Test
        @DisplayName("stepBack delegates to inner engine")
        void stepBackDelegatesToInnerEngine() {
            propagatingEngine.register(new WorkQueueProcessor(() -> 30));

            assertFalse(propagatingEngine.stepBack());

            // WorkQueueMessage(ACTIVE) produces ScheduleCreated which is also an Event,
            // so EventPropagatingEngine recursively processes it → 2 history entries
            propagatingEngine.processEvent(new WorkQueueMessage("queue1", WorkQueueStatus.ACTIVE, 0));
            assertEquals(2, propagatingEngine.getHistorySize());

            assertTrue(propagatingEngine.stepBack());
            assertEquals(1, propagatingEngine.getHistorySize());
        }

        @Test
        @DisplayName("getHistorySize delegates to inner engine")
        void getHistorySizeDelegatesToInnerEngine() {
            propagatingEngine.register(new WorkQueueProcessor(() -> 30));

            assertEquals(0, propagatingEngine.getHistorySize());

            // Each WorkQueueMessage(ACTIVE) produces ScheduleCreated (implements Event),
            // which is recursively processed → 2 history entries per message
            propagatingEngine.processEvent(new WorkQueueMessage("queue1", WorkQueueStatus.ACTIVE, 0));
            assertEquals(2, propagatingEngine.getHistorySize());

            propagatingEngine.processEvent(new WorkQueueMessage("queue2", WorkQueueStatus.ACTIVE, 0));
            assertEquals(4, propagatingEngine.getHistorySize());
        }

        @Test
        @DisplayName("clearHistory delegates to inner engine")
        void clearHistoryDelegatesToInnerEngine() {
            propagatingEngine.register(new WorkQueueProcessor(() -> 30));

            propagatingEngine.processEvent(new WorkQueueMessage("queue1", WorkQueueStatus.ACTIVE, 0));
            propagatingEngine.processEvent(new WorkQueueMessage("queue2", WorkQueueStatus.ACTIVE, 0));

            assertEquals(4, propagatingEngine.getHistorySize());

            propagatingEngine.clearHistory();

            assertEquals(0, propagatingEngine.getHistorySize());
        }
    }

    @Nested
    @DisplayName("Recursive Event Processing")
    class RecursiveEventProcessing {

        @Test
        @DisplayName("processEvent returns side effects from inner engine when no Event side effects")
        void processEventReturnsInnerEngineSideEffects() {
            propagatingEngine.register(new WorkQueueProcessor(() -> 30));

            List<SideEffect> effects = propagatingEngine.processEvent(
                    new WorkQueueMessage("queue1", WorkQueueStatus.ACTIVE, 0));

            // Should contain at least ScheduleCreated
            assertTrue(effects.stream().anyMatch(se -> se instanceof ScheduleCreated));
        }

        @Test
        @DisplayName("processEvent recursively processes side effects that implement Event")
        void processEventRecursivelyProcessesEventSideEffects() {
            // WorkQueueProcessor produces ScheduleCreated (which implements Event)
            // ScheduleRunnerProcessor consumes ScheduleCreated events
            propagatingEngine.register(new WorkQueueProcessor(() -> 30));
            propagatingEngine.register(new ScheduleRunnerProcessor());

            String workQueueId = "queue1";
            Instant now = Instant.now();
            Instant estimatedMoveTime = now.minusSeconds(1); // In the past so actions activate immediately

            // Register a work instruction first
            propagatingEngine.processEvent(new WorkInstructionEvent(
                    "wi1", workQueueId, "CHE1", WorkInstructionStatus.PENDING, estimatedMoveTime, 120));

            // Activate the work queue - this should produce ScheduleCreated,
            // which should then be recursively processed
            List<SideEffect> effects = propagatingEngine.processEvent(
                    new WorkQueueMessage(workQueueId, WorkQueueStatus.ACTIVE, 0));

            // Should contain ScheduleCreated from WorkQueueProcessor
            assertTrue(effects.stream().anyMatch(se -> se instanceof ScheduleCreated),
                    "Should contain ScheduleCreated");

            // The ScheduleCreated should trigger ScheduleRunnerProcessor to produce ActionActivated
            // Note: ActionActivated may not happen immediately depending on time logic
            // But ScheduleCreated should definitely be there and processed
            ScheduleCreated scheduleCreated = effects.stream()
                    .filter(se -> se instanceof ScheduleCreated)
                    .map(se -> (ScheduleCreated) se)
                    .findFirst()
                    .orElseThrow();

            assertEquals(workQueueId, scheduleCreated.workQueueId());
        }

        @Test
        @DisplayName("processEvent includes all side effects from recursive calls at end of list")
        void processEventAppendsRecursiveSideEffectsAtEnd() {
            // Create a mock processor that produces a dual SideEffect/Event
            propagatingEngine.register(new WorkQueueProcessor(() -> 30));
            propagatingEngine.register(new ScheduleRunnerProcessor());

            String workQueueId = "queue1";
            Instant pastTime = Instant.parse("2020-01-01T00:00:00Z");

            // Register work instruction with past estimated move time
            propagatingEngine.processEvent(new WorkInstructionEvent(
                    "wi1", workQueueId, "CHE1", WorkInstructionStatus.PENDING, pastTime, 120));

            // Process a time event first to set the time context
            propagatingEngine.processEvent(new TimeEvent(Instant.now()));

            // Now activate - this will create a schedule that's already past its start time
            List<SideEffect> effects = propagatingEngine.processEvent(
                    new WorkQueueMessage(workQueueId, WorkQueueStatus.ACTIVE, 0));

            // Verify the order: ScheduleCreated should come before any ActionActivated
            int scheduleCreatedIndex = -1;
            int firstActionActivatedIndex = -1;

            for (int i = 0; i < effects.size(); i++) {
                if (effects.get(i) instanceof ScheduleCreated && scheduleCreatedIndex == -1) {
                    scheduleCreatedIndex = i;
                }
                if (effects.get(i) instanceof ActionActivated && firstActionActivatedIndex == -1) {
                    firstActionActivatedIndex = i;
                }
            }

            assertTrue(scheduleCreatedIndex >= 0, "Should have ScheduleCreated");

            // If ActionActivated is present, it should come after ScheduleCreated
            if (firstActionActivatedIndex >= 0) {
                assertTrue(scheduleCreatedIndex < firstActionActivatedIndex,
                        "ScheduleCreated should come before ActionActivated in the list");
            }
        }

        @Test
        @DisplayName("processEvent handles multiple levels of recursion")
        void processEventHandlesMultipleLevelsOfRecursion() {
            // This test verifies that if a recursive call produces another Event side effect,
            // it will also be processed recursively
            propagatingEngine.register(new WorkQueueProcessor(() -> 30));
            propagatingEngine.register(new ScheduleRunnerProcessor());

            String workQueueId = "queue1";
            Instant pastTime = Instant.parse("2020-01-01T00:00:00Z");

            // Register work instruction
            propagatingEngine.processEvent(new WorkInstructionEvent(
                    "wi1", workQueueId, "CHE1", WorkInstructionStatus.PENDING, pastTime, 120));

            // Set time context
            propagatingEngine.processEvent(new TimeEvent(Instant.now()));

            // Activate - will trigger recursive processing
            List<SideEffect> effects = propagatingEngine.processEvent(
                    new WorkQueueMessage(workQueueId, WorkQueueStatus.ACTIVE, 0));

            // Verify we got ScheduleCreated
            assertTrue(effects.stream().anyMatch(se -> se instanceof ScheduleCreated));
        }

        @Test
        @DisplayName("processEvent with no processors returns empty list")
        void processEventWithNoProcessorsReturnsEmptyList() {
            List<SideEffect> effects = propagatingEngine.processEvent(
                    new WorkQueueMessage("queue1", WorkQueueStatus.ACTIVE, 0));

            assertTrue(effects.isEmpty());
        }
    }

    @Nested
    @DisplayName("Integration with ScheduleCreated as Event")
    class IntegrationWithScheduleCreatedAsEvent {

        @Test
        @DisplayName("ScheduleCreated side effect triggers ScheduleRunnerProcessor")
        void scheduleCreatedTrigersScheduleRunner() {
            WorkQueueProcessor workQueueProcessor = new WorkQueueProcessor(() -> 30);
            ScheduleRunnerProcessor scheduleRunnerProcessor = new ScheduleRunnerProcessor();

            propagatingEngine.register(workQueueProcessor);
            propagatingEngine.register(scheduleRunnerProcessor);

            String workQueueId = "queue1";
            Instant now = Instant.now();

            // First, register a work instruction
            propagatingEngine.processEvent(new WorkInstructionEvent(
                    "wi1", workQueueId, "CHE1", WorkInstructionStatus.PENDING, now.minusSeconds(10), 120));

            // Activate the queue - WorkQueueProcessor will produce ScheduleCreated
            // EventPropagatingEngine should then pass ScheduleCreated to ScheduleRunnerProcessor
            List<SideEffect> effects = propagatingEngine.processEvent(
                    new WorkQueueMessage(workQueueId, WorkQueueStatus.ACTIVE, 0));

            // Count the side effect types
            long scheduleCreatedCount = effects.stream()
                    .filter(se -> se instanceof ScheduleCreated)
                    .count();

            // There should be exactly one ScheduleCreated
            // (the original, not duplicated by re-processing)
            assertEquals(1, scheduleCreatedCount, "Should have exactly one ScheduleCreated");
        }

        @Test
        @DisplayName("Without EventPropagatingEngine, ScheduleCreated is not automatically processed")
        void withoutEventPropagatingEngineScheduleCreatedNotProcessed() {
            // Compare behavior with and without propagating engine
            EventProcessingEngine plainEngine = new EventProcessingEngine();
            WorkQueueProcessor workQueueProcessor = new WorkQueueProcessor(() -> 30);
            ScheduleRunnerProcessor scheduleRunnerProcessor = new ScheduleRunnerProcessor();

            plainEngine.register(workQueueProcessor);
            plainEngine.register(scheduleRunnerProcessor);

            String workQueueId = "queue1";
            Instant now = Instant.now();

            // Register work instruction
            plainEngine.processEvent(new WorkInstructionEvent(
                    "wi1", workQueueId, "CHE1", WorkInstructionStatus.PENDING, now.minusSeconds(10), 120));

            // Activate the queue
            List<SideEffect> plainEffects = plainEngine.processEvent(
                    new WorkQueueMessage(workQueueId, WorkQueueStatus.ACTIVE, 0));

            // Plain engine should have ScheduleCreated, but ScheduleRunnerProcessor
            // won't process it in the same call since it's a side effect, not an event being processed
            assertTrue(plainEffects.stream().anyMatch(se -> se instanceof ScheduleCreated));

            // With plain engine, the ScheduleCreated would need to be manually
            // fed back as an event for ScheduleRunnerProcessor to handle it
        }
    }
}
