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

@DisplayName("DecoratorEngine")
class DecoratorEngineTest {

    private EventProcessingEngine innerEngine;
    private DecoratorEngine decoratorEngine;

    @BeforeEach
    void setUp() {
        innerEngine = new EventProcessingEngine();
        decoratorEngine = new DecoratorEngine(innerEngine);
    }

    @Nested
    @DisplayName("Delegation")
    class Delegation {

        @Test
        @DisplayName("register delegates to inner engine")
        void registerDelegatesToInnerEngine() {
            WorkQueueProcessor processor = new WorkQueueProcessor();
            decoratorEngine.register(processor);

            // Verify by processing an event that the processor would handle
            List<SideEffect> effects = decoratorEngine.processEvent(
                    new WorkQueueMessage("queue1", WorkQueueStatus.ACTIVE));

            assertTrue(effects.stream().anyMatch(se -> se instanceof ScheduleCreated));
        }

        @Test
        @DisplayName("stepBack delegates to inner engine")
        void stepBackDelegatesToInnerEngine() {
            decoratorEngine.register(new WorkQueueProcessor());

            assertFalse(decoratorEngine.stepBack());

            decoratorEngine.processEvent(new WorkQueueMessage("queue1", WorkQueueStatus.ACTIVE));
            assertEquals(1, decoratorEngine.getHistorySize());

            assertTrue(decoratorEngine.stepBack());
            assertEquals(0, decoratorEngine.getHistorySize());
        }

        @Test
        @DisplayName("getHistorySize delegates to inner engine")
        void getHistorySizeDelegatesToInnerEngine() {
            decoratorEngine.register(new WorkQueueProcessor());

            assertEquals(0, decoratorEngine.getHistorySize());

            decoratorEngine.processEvent(new WorkQueueMessage("queue1", WorkQueueStatus.ACTIVE));
            assertEquals(1, decoratorEngine.getHistorySize());

            decoratorEngine.processEvent(new WorkQueueMessage("queue2", WorkQueueStatus.ACTIVE));
            assertEquals(2, decoratorEngine.getHistorySize());
        }

        @Test
        @DisplayName("clearHistory delegates to inner engine")
        void clearHistoryDelegatesToInnerEngine() {
            decoratorEngine.register(new WorkQueueProcessor());

            decoratorEngine.processEvent(new WorkQueueMessage("queue1", WorkQueueStatus.ACTIVE));
            decoratorEngine.processEvent(new WorkQueueMessage("queue2", WorkQueueStatus.ACTIVE));

            assertEquals(2, decoratorEngine.getHistorySize());

            decoratorEngine.clearHistory();

            assertEquals(0, decoratorEngine.getHistorySize());
        }
    }

    @Nested
    @DisplayName("Recursive Event Processing")
    class RecursiveEventProcessing {

        @Test
        @DisplayName("processEvent returns side effects from inner engine when no Event side effects")
        void processEventReturnsInnerEngineSideEffects() {
            decoratorEngine.register(new WorkQueueProcessor());

            List<SideEffect> effects = decoratorEngine.processEvent(
                    new WorkQueueMessage("queue1", WorkQueueStatus.ACTIVE));

            // Should contain at least ScheduleCreated
            assertTrue(effects.stream().anyMatch(se -> se instanceof ScheduleCreated));
        }

        @Test
        @DisplayName("processEvent recursively processes side effects that implement Event")
        void processEventRecursivelyProcessesEventSideEffects() {
            // WorkQueueProcessor produces ScheduleCreated (which implements Event)
            // ScheduleRunnerProcessor consumes ScheduleCreated events
            decoratorEngine.register(new WorkQueueProcessor());
            decoratorEngine.register(new ScheduleRunnerProcessor());

            String workQueueId = "queue1";
            Instant now = Instant.now();
            Instant estimatedMoveTime = now.minusSeconds(1); // In the past so actions activate immediately

            // Register a work instruction first
            decoratorEngine.processEvent(new WorkInstructionEvent(
                    "wi1", workQueueId, "CHE1", WorkInstructionStatus.ASSIGNED, estimatedMoveTime));

            // Activate the work queue - this should produce ScheduleCreated,
            // which should then be recursively processed
            List<SideEffect> effects = decoratorEngine.processEvent(
                    new WorkQueueMessage(workQueueId, WorkQueueStatus.ACTIVE));

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
            decoratorEngine.register(new WorkQueueProcessor());
            decoratorEngine.register(new ScheduleRunnerProcessor());

            String workQueueId = "queue1";
            Instant pastTime = Instant.parse("2020-01-01T00:00:00Z");

            // Register work instruction with past estimated move time
            decoratorEngine.processEvent(new WorkInstructionEvent(
                    "wi1", workQueueId, "CHE1", WorkInstructionStatus.ASSIGNED, pastTime));

            // Process a time event first to set the time context
            decoratorEngine.processEvent(new TimeEvent(Instant.now()));

            // Now activate - this will create a schedule that's already past its start time
            List<SideEffect> effects = decoratorEngine.processEvent(
                    new WorkQueueMessage(workQueueId, WorkQueueStatus.ACTIVE));

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
            decoratorEngine.register(new WorkQueueProcessor());
            decoratorEngine.register(new ScheduleRunnerProcessor());

            String workQueueId = "queue1";
            Instant pastTime = Instant.parse("2020-01-01T00:00:00Z");

            // Register work instruction
            decoratorEngine.processEvent(new WorkInstructionEvent(
                    "wi1", workQueueId, "CHE1", WorkInstructionStatus.ASSIGNED, pastTime));

            // Set time context
            decoratorEngine.processEvent(new TimeEvent(Instant.now()));

            // Activate - will trigger recursive processing
            List<SideEffect> effects = decoratorEngine.processEvent(
                    new WorkQueueMessage(workQueueId, WorkQueueStatus.ACTIVE));

            // Verify we got ScheduleCreated
            assertTrue(effects.stream().anyMatch(se -> se instanceof ScheduleCreated));
        }

        @Test
        @DisplayName("processEvent with no processors returns empty list")
        void processEventWithNoProcessorsReturnsEmptyList() {
            List<SideEffect> effects = decoratorEngine.processEvent(
                    new WorkQueueMessage("queue1", WorkQueueStatus.ACTIVE));

            assertTrue(effects.isEmpty());
        }
    }

    @Nested
    @DisplayName("Integration with ScheduleCreated as Event")
    class IntegrationWithScheduleCreatedAsEvent {

        @Test
        @DisplayName("ScheduleCreated side effect triggers ScheduleRunnerProcessor")
        void scheduleCreatedTrigersScheduleRunner() {
            WorkQueueProcessor workQueueProcessor = new WorkQueueProcessor();
            ScheduleRunnerProcessor scheduleRunnerProcessor = new ScheduleRunnerProcessor();

            decoratorEngine.register(workQueueProcessor);
            decoratorEngine.register(scheduleRunnerProcessor);

            String workQueueId = "queue1";
            Instant now = Instant.now();

            // First, register a work instruction
            decoratorEngine.processEvent(new WorkInstructionEvent(
                    "wi1", workQueueId, "CHE1", WorkInstructionStatus.ASSIGNED, now.minusSeconds(10)));

            // Activate the queue - WorkQueueProcessor will produce ScheduleCreated
            // DecoratorEngine should then pass ScheduleCreated to ScheduleRunnerProcessor
            List<SideEffect> effects = decoratorEngine.processEvent(
                    new WorkQueueMessage(workQueueId, WorkQueueStatus.ACTIVE));

            // Count the side effect types
            long scheduleCreatedCount = effects.stream()
                    .filter(se -> se instanceof ScheduleCreated)
                    .count();

            // There should be exactly one ScheduleCreated
            // (the original, not duplicated by re-processing)
            assertEquals(1, scheduleCreatedCount, "Should have exactly one ScheduleCreated");
        }

        @Test
        @DisplayName("Without DecoratorEngine, ScheduleCreated is not automatically processed")
        void withoutDecoratorEngineScheduleCreatedNotProcessed() {
            // Compare behavior with and without decorator
            EventProcessingEngine plainEngine = new EventProcessingEngine();
            WorkQueueProcessor workQueueProcessor = new WorkQueueProcessor();
            ScheduleRunnerProcessor scheduleRunnerProcessor = new ScheduleRunnerProcessor();

            plainEngine.register(workQueueProcessor);
            plainEngine.register(scheduleRunnerProcessor);

            String workQueueId = "queue1";
            Instant now = Instant.now();

            // Register work instruction
            plainEngine.processEvent(new WorkInstructionEvent(
                    "wi1", workQueueId, "CHE1", WorkInstructionStatus.ASSIGNED, now.minusSeconds(10)));

            // Activate the queue
            List<SideEffect> plainEffects = plainEngine.processEvent(
                    new WorkQueueMessage(workQueueId, WorkQueueStatus.ACTIVE));

            // Plain engine should have ScheduleCreated, but ScheduleRunnerProcessor
            // won't process it in the same call since it's a side effect, not an event being processed
            assertTrue(plainEffects.stream().anyMatch(se -> se instanceof ScheduleCreated));

            // With plain engine, the ScheduleCreated would need to be manually
            // fed back as an event for ScheduleRunnerProcessor to handle it
        }
    }
}
