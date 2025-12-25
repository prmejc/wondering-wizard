package com.wonderingwizard.processors;

import com.wonderingwizard.engine.EventProcessingEngine;
import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.WorkQueueMessage;
import com.wonderingwizard.sideeffects.ScheduleAborted;
import com.wonderingwizard.sideeffects.ScheduleCreated;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for F-2: Schedule Creation feature.
 *
 * @see <a href="docs/requirements.md">F-2 Requirements</a>
 */
@DisplayName("F-2: Schedule Creation")
class WorkQueueProcessorTest {

    private EventProcessingEngine engine;

    @BeforeEach
    void setUp() {
        engine = new EventProcessingEngine();
        engine.register(new WorkQueueProcessor());
    }

    @Nested
    @DisplayName("F-2.1: WorkQueueMessage with Active status")
    class ActiveStatus {

        @Test
        @DisplayName("Should return ScheduleCreated when first Active message is received")
        void activeMessage_returnsScheduleCreated() {
            List<SideEffect> sideEffects = engine.processEvent(
                    new WorkQueueMessage("queue-1", "Active"));

            assertEquals(1, sideEffects.size(),
                    "Should contain exactly one side effect");
            assertInstanceOf(ScheduleCreated.class, sideEffects.get(0),
                    "Side effect should be ScheduleCreated");

            ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);
            assertEquals("queue-1", created.workQueueId());
        }

        @Test
        @DisplayName("Should return ScheduleCreated for different workQueueIds")
        void multipleActiveMessages_differentQueues_returnsScheduleCreatedForEach() {
            List<SideEffect> sideEffects1 = engine.processEvent(
                    new WorkQueueMessage("queue-1", "Active"));
            List<SideEffect> sideEffects2 = engine.processEvent(
                    new WorkQueueMessage("queue-2", "Active"));

            assertEquals(1, sideEffects1.size());
            assertEquals(1, sideEffects2.size());
            assertInstanceOf(ScheduleCreated.class, sideEffects1.get(0));
            assertInstanceOf(ScheduleCreated.class, sideEffects2.get(0));
            assertEquals("queue-1", ((ScheduleCreated) sideEffects1.get(0)).workQueueId());
            assertEquals("queue-2", ((ScheduleCreated) sideEffects2.get(0)).workQueueId());
        }
    }

    @Nested
    @DisplayName("F-2.2: Idempotent Active status (duplicate messages)")
    class IdempotentActive {

        @Test
        @DisplayName("Should return empty side effects for duplicate Active message")
        void duplicateActiveMessage_returnsEmptySideEffects() {
            // First Active message
            engine.processEvent(new WorkQueueMessage("queue-1", "Active"));

            // Duplicate Active message for same workQueueId
            List<SideEffect> sideEffects = engine.processEvent(
                    new WorkQueueMessage("queue-1", "Active"));

            assertTrue(sideEffects.isEmpty(),
                    "Duplicate Active message should not produce side effects");
        }

        @Test
        @DisplayName("Should return empty for multiple duplicate Active messages")
        void multipleDuplicateActiveMessages_returnsEmptySideEffects() {
            // First Active message
            List<SideEffect> first = engine.processEvent(
                    new WorkQueueMessage("queue-1", "Active"));

            // Multiple duplicate Active messages
            List<SideEffect> second = engine.processEvent(
                    new WorkQueueMessage("queue-1", "Active"));
            List<SideEffect> third = engine.processEvent(
                    new WorkQueueMessage("queue-1", "Active"));
            List<SideEffect> fourth = engine.processEvent(
                    new WorkQueueMessage("queue-1", "Active"));

            assertEquals(1, first.size(), "First Active should create schedule");
            assertTrue(second.isEmpty(), "Second Active should be idempotent");
            assertTrue(third.isEmpty(), "Third Active should be idempotent");
            assertTrue(fourth.isEmpty(), "Fourth Active should be idempotent");
        }
    }

    @Nested
    @DisplayName("F-2.3: WorkQueueMessage with Inactive status")
    class InactiveStatus {

        @Test
        @DisplayName("Should return ScheduleAborted when Inactive message follows Active")
        void inactiveAfterActive_returnsScheduleAborted() {
            // First create a schedule
            engine.processEvent(new WorkQueueMessage("queue-1", "Active"));

            // Then abort it
            List<SideEffect> sideEffects = engine.processEvent(
                    new WorkQueueMessage("queue-1", "Inactive"));

            assertEquals(1, sideEffects.size(),
                    "Should contain exactly one side effect");
            assertInstanceOf(ScheduleAborted.class, sideEffects.get(0),
                    "Side effect should be ScheduleAborted");

            ScheduleAborted aborted = (ScheduleAborted) sideEffects.get(0);
            assertEquals("queue-1", aborted.workQueueId());
        }

        @Test
        @DisplayName("Should return empty when Inactive message has no prior Active")
        void inactiveWithoutActive_returnsEmptySideEffects() {
            List<SideEffect> sideEffects = engine.processEvent(
                    new WorkQueueMessage("queue-1", "Inactive"));

            assertTrue(sideEffects.isEmpty(),
                    "Inactive without prior Active should not produce side effects");
        }

        @Test
        @DisplayName("Should return empty for duplicate Inactive messages")
        void duplicateInactiveMessage_returnsEmptySideEffects() {
            // Create and abort
            engine.processEvent(new WorkQueueMessage("queue-1", "Active"));
            engine.processEvent(new WorkQueueMessage("queue-1", "Inactive"));

            // Duplicate Inactive
            List<SideEffect> sideEffects = engine.processEvent(
                    new WorkQueueMessage("queue-1", "Inactive"));

            assertTrue(sideEffects.isEmpty(),
                    "Duplicate Inactive message should not produce side effects");
        }
    }

    @Nested
    @DisplayName("F-2.4: Schedule lifecycle")
    class ScheduleLifecycle {

        @Test
        @DisplayName("Should allow reactivation after abort")
        void reactivationAfterAbort_returnsScheduleCreated() {
            // Create
            List<SideEffect> created = engine.processEvent(
                    new WorkQueueMessage("queue-1", "Active"));

            // Abort
            List<SideEffect> aborted = engine.processEvent(
                    new WorkQueueMessage("queue-1", "Inactive"));

            // Reactivate
            List<SideEffect> reactivated = engine.processEvent(
                    new WorkQueueMessage("queue-1", "Active"));

            assertEquals(1, created.size());
            assertInstanceOf(ScheduleCreated.class, created.get(0));

            assertEquals(1, aborted.size());
            assertInstanceOf(ScheduleAborted.class, aborted.get(0));

            assertEquals(1, reactivated.size());
            assertInstanceOf(ScheduleCreated.class, reactivated.get(0));
        }

        @Test
        @DisplayName("Should handle multiple queues independently")
        void multipleQueues_handledIndependently() {
            // Activate queue-1 and queue-2
            engine.processEvent(new WorkQueueMessage("queue-1", "Active"));
            engine.processEvent(new WorkQueueMessage("queue-2", "Active"));

            // Deactivate only queue-1
            List<SideEffect> aborted = engine.processEvent(
                    new WorkQueueMessage("queue-1", "Inactive"));

            // queue-2 should still be idempotent
            List<SideEffect> stillActive = engine.processEvent(
                    new WorkQueueMessage("queue-2", "Active"));

            assertEquals(1, aborted.size());
            assertInstanceOf(ScheduleAborted.class, aborted.get(0));
            assertEquals("queue-1", ((ScheduleAborted) aborted.get(0)).workQueueId());

            assertTrue(stillActive.isEmpty(),
                    "queue-2 Active should be idempotent as it's still active");
        }

        @Test
        @DisplayName("Full lifecycle: create -> duplicate -> abort -> duplicate abort -> recreate")
        void fullLifecycle() {
            // Create
            List<SideEffect> step1 = engine.processEvent(
                    new WorkQueueMessage("queue-1", "Active"));
            assertEquals(1, step1.size());
            assertInstanceOf(ScheduleCreated.class, step1.get(0));

            // Duplicate Active (idempotent)
            List<SideEffect> step2 = engine.processEvent(
                    new WorkQueueMessage("queue-1", "Active"));
            assertTrue(step2.isEmpty());

            // Abort
            List<SideEffect> step3 = engine.processEvent(
                    new WorkQueueMessage("queue-1", "Inactive"));
            assertEquals(1, step3.size());
            assertInstanceOf(ScheduleAborted.class, step3.get(0));

            // Duplicate Inactive (no effect)
            List<SideEffect> step4 = engine.processEvent(
                    new WorkQueueMessage("queue-1", "Inactive"));
            assertTrue(step4.isEmpty());

            // Recreate
            List<SideEffect> step5 = engine.processEvent(
                    new WorkQueueMessage("queue-1", "Active"));
            assertEquals(1, step5.size());
            assertInstanceOf(ScheduleCreated.class, step5.get(0));
        }
    }

    @Nested
    @DisplayName("F-2.5: Unknown status handling")
    class UnknownStatus {

        @Test
        @DisplayName("Should return empty for unknown status")
        void unknownStatus_returnsEmptySideEffects() {
            List<SideEffect> sideEffects = engine.processEvent(
                    new WorkQueueMessage("queue-1", "Unknown"));

            assertTrue(sideEffects.isEmpty(),
                    "Unknown status should not produce side effects");
        }

        @Test
        @DisplayName("Should return empty for null-like status strings")
        void emptyStatus_returnsEmptySideEffects() {
            List<SideEffect> sideEffects = engine.processEvent(
                    new WorkQueueMessage("queue-1", ""));

            assertTrue(sideEffects.isEmpty(),
                    "Empty status should not produce side effects");
        }
    }

    @Nested
    @DisplayName("F-2.6: Complete workflow as per requirements")
    class CompleteWorkflow {

        @Test
        @DisplayName("Should match exact behavior from F-2 requirements")
        void completeF2Workflow() {
            // Step 1: First Active message creates schedule
            List<SideEffect> sideEffects1 = engine.processEvent(
                    new WorkQueueMessage("queue-1", "Active"));
            assertEquals(1, sideEffects1.size(),
                    "F-2 Requirement: First Active should create schedule");
            assertInstanceOf(ScheduleCreated.class, sideEffects1.get(0));
            assertEquals("queue-1", ((ScheduleCreated) sideEffects1.get(0)).workQueueId());

            // Step 2: Duplicate Active message is idempotent
            List<SideEffect> sideEffects2 = engine.processEvent(
                    new WorkQueueMessage("queue-1", "Active"));
            assertTrue(sideEffects2.isEmpty(),
                    "F-2 Requirement: Duplicate Active should be idempotent");

            // Step 3: Inactive message aborts schedule
            List<SideEffect> sideEffects3 = engine.processEvent(
                    new WorkQueueMessage("queue-1", "Inactive"));
            assertEquals(1, sideEffects3.size(),
                    "F-2 Requirement: Inactive should abort schedule");
            assertInstanceOf(ScheduleAborted.class, sideEffects3.get(0));
            assertEquals("queue-1", ((ScheduleAborted) sideEffects3.get(0)).workQueueId());
        }
    }
}
