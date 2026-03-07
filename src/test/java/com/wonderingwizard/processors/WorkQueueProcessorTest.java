package com.wonderingwizard.processors;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.DeviceType;
import com.wonderingwizard.domain.takt.Takt;
import com.wonderingwizard.engine.EventProcessingEngine;
import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.WorkInstructionEvent;
import com.wonderingwizard.events.WorkQueueMessage;
import com.wonderingwizard.sideeffects.ScheduleAborted;
import com.wonderingwizard.sideeffects.ScheduleCreated;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import static com.wonderingwizard.events.WorkInstructionStatus.*;
import static com.wonderingwizard.events.WorkQueueStatus.ACTIVE;
import static com.wonderingwizard.events.WorkQueueStatus.INACTIVE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for F-2: Schedule Creation, F-4: Work Instruction Event, and F-5: Takt Generation features.
 *
 * @see <a href="docs/requirements.md">F-2, F-4, and F-5 Requirements</a>
 */
@DisplayName("WorkQueueProcessor Tests")
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
                    new WorkQueueMessage("queue-1", ACTIVE, 0));

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
                    new WorkQueueMessage("queue-1", ACTIVE, 0));
            List<SideEffect> sideEffects2 = engine.processEvent(
                    new WorkQueueMessage("queue-2", ACTIVE, 0));

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
            engine.processEvent(new WorkQueueMessage("queue-1", ACTIVE, 0));

            // Duplicate Active message for same workQueueId
            List<SideEffect> sideEffects = engine.processEvent(
                    new WorkQueueMessage("queue-1", ACTIVE, 0));

            assertTrue(sideEffects.isEmpty(),
                    "Duplicate Active message should not produce side effects");
        }

        @Test
        @DisplayName("Should return empty for multiple duplicate Active messages")
        void multipleDuplicateActiveMessages_returnsEmptySideEffects() {
            // First Active message
            List<SideEffect> first = engine.processEvent(
                    new WorkQueueMessage("queue-1", ACTIVE, 0));

            // Multiple duplicate Active messages
            List<SideEffect> second = engine.processEvent(
                    new WorkQueueMessage("queue-1", ACTIVE, 0));
            List<SideEffect> third = engine.processEvent(
                    new WorkQueueMessage("queue-1", ACTIVE, 0));
            List<SideEffect> fourth = engine.processEvent(
                    new WorkQueueMessage("queue-1", ACTIVE, 0));

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
            engine.processEvent(new WorkQueueMessage("queue-1", ACTIVE, 0));

            // Then abort it
            List<SideEffect> sideEffects = engine.processEvent(
                    new WorkQueueMessage("queue-1", INACTIVE, 0));

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
                    new WorkQueueMessage("queue-1", INACTIVE, 0));

            assertTrue(sideEffects.isEmpty(),
                    "Inactive without prior Active should not produce side effects");
        }

        @Test
        @DisplayName("Should return empty for duplicate Inactive messages")
        void duplicateInactiveMessage_returnsEmptySideEffects() {
            // Create and abort
            engine.processEvent(new WorkQueueMessage("queue-1", ACTIVE, 0));
            engine.processEvent(new WorkQueueMessage("queue-1", INACTIVE, 0));

            // Duplicate Inactive
            List<SideEffect> sideEffects = engine.processEvent(
                    new WorkQueueMessage("queue-1", INACTIVE, 0));

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
                    new WorkQueueMessage("queue-1", ACTIVE, 0));

            // Abort
            List<SideEffect> aborted = engine.processEvent(
                    new WorkQueueMessage("queue-1", INACTIVE, 0));

            // Reactivate
            List<SideEffect> reactivated = engine.processEvent(
                    new WorkQueueMessage("queue-1", ACTIVE, 0));

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
            engine.processEvent(new WorkQueueMessage("queue-1", ACTIVE, 0));
            engine.processEvent(new WorkQueueMessage("queue-2", ACTIVE, 0));

            // Deactivate only queue-1
            List<SideEffect> aborted = engine.processEvent(
                    new WorkQueueMessage("queue-1", INACTIVE, 0));

            // queue-2 should still be idempotent
            List<SideEffect> stillActive = engine.processEvent(
                    new WorkQueueMessage("queue-2", ACTIVE, 0));

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
                    new WorkQueueMessage("queue-1", ACTIVE, 0));
            assertEquals(1, step1.size());
            assertInstanceOf(ScheduleCreated.class, step1.get(0));

            // Duplicate Active (idempotent)
            List<SideEffect> step2 = engine.processEvent(
                    new WorkQueueMessage("queue-1", ACTIVE, 0));
            assertTrue(step2.isEmpty());

            // Abort
            List<SideEffect> step3 = engine.processEvent(
                    new WorkQueueMessage("queue-1", INACTIVE, 0));
            assertEquals(1, step3.size());
            assertInstanceOf(ScheduleAborted.class, step3.get(0));

            // Duplicate Inactive (no effect)
            List<SideEffect> step4 = engine.processEvent(
                    new WorkQueueMessage("queue-1", INACTIVE, 0));
            assertTrue(step4.isEmpty());

            // Recreate
            List<SideEffect> step5 = engine.processEvent(
                    new WorkQueueMessage("queue-1", ACTIVE, 0));
            assertEquals(1, step5.size());
            assertInstanceOf(ScheduleCreated.class, step5.get(0));
        }
    }

    @Nested
    @DisplayName("F-2.5: Null status handling")
    class NullStatus {

        @Test
        @DisplayName("Should return empty for null status")
        void nullStatus_returnsEmptySideEffects() {
            List<SideEffect> sideEffects = engine.processEvent(
                    new WorkQueueMessage("queue-1", null, 0));

            assertTrue(sideEffects.isEmpty(),
                    "Null status should not produce side effects");
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
                    new WorkQueueMessage("queue-1", ACTIVE, 0));
            assertEquals(1, sideEffects1.size(),
                    "F-2 Requirement: First Active should create schedule");
            assertInstanceOf(ScheduleCreated.class, sideEffects1.get(0));
            assertEquals("queue-1", ((ScheduleCreated) sideEffects1.get(0)).workQueueId());

            // Step 2: Duplicate Active message is idempotent
            List<SideEffect> sideEffects2 = engine.processEvent(
                    new WorkQueueMessage("queue-1", ACTIVE, 0));
            assertTrue(sideEffects2.isEmpty(),
                    "F-2 Requirement: Duplicate Active should be idempotent");

            // Step 3: Inactive message aborts schedule
            List<SideEffect> sideEffects3 = engine.processEvent(
                    new WorkQueueMessage("queue-1", INACTIVE, 0));
            assertEquals(1, sideEffects3.size(),
                    "F-2 Requirement: Inactive should abort schedule");
            assertInstanceOf(ScheduleAborted.class, sideEffects3.get(0));
            assertEquals("queue-1", ((ScheduleAborted) sideEffects3.get(0)).workQueueId());
        }
    }

    @Nested
    @DisplayName("F-4: Work Instruction Event")
    class WorkInstructionEventTests {

        @Nested
        @DisplayName("F-4.1: WorkInstructionEvent registration")
        class WorkInstructionRegistration {

            @Test
            @DisplayName("Should return empty side effects when work instruction is registered")
            void workInstructionEvent_returnsEmptySideEffects() {
                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null, 120));

                assertTrue(sideEffects.isEmpty(),
                        "WorkInstructionEvent should not produce side effects");
            }

            @Test
            @DisplayName("Should store multiple work instructions for same work queue")
            void multipleWorkInstructions_sameQueue_allStored() {
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null, 120));
                engine.processEvent(new WorkInstructionEvent("wi-2", "queue-1", "CHE-002", PENDING, null, 120));
                engine.processEvent(new WorkInstructionEvent("wi-3", "queue-1", "CHE-003", IN_PROGRESS, null, 120));

                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE, 0));

                assertEquals(1, sideEffects.size());
                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);
                // 3 containers with multi-device workflow: 3 + 3 (offset) = 6 takts
                assertEquals(6, created.takts().size(),
                        "Should have 6 takts for 3 work instructions with multi-device workflow");
            }
        }

        @Nested
        @DisplayName("F-4.2: Takts generated from work instructions in ScheduleCreated")
        class TaktsInScheduleCreated {

            @Test
            @DisplayName("Should generate takts from work instructions in ScheduleCreated")
            void activeMessage_generatesTakts() {
                // Register work instructions
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null, 120));
                engine.processEvent(new WorkInstructionEvent("wi-2", "queue-1", "CHE-002", IN_PROGRESS, null, 120));

                // Activate work queue
                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE, 0));

                assertEquals(1, sideEffects.size());
                assertInstanceOf(ScheduleCreated.class, sideEffects.get(0));

                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);
                assertEquals("queue-1", created.workQueueId());
                // 2 containers with multi-device workflow: 2 + 3 (offset) = 5 takts
                assertEquals(5, created.takts().size());
            }

            @Test
            @DisplayName("Should return empty takts list when no work instructions registered")
            void activeMessage_noWorkInstructions_returnsEmptyTaktsList() {
                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE, 0));

                assertEquals(1, sideEffects.size());
                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);
                assertTrue(created.takts().isEmpty(),
                        "Takts list should be empty when no work instructions registered");
            }

            @Test
            @DisplayName("Should only generate takts for the activated work queue")
            void activeMessage_onlyGeneratesTaktsForMatchingWorkInstructions() {
                // Register work instructions for different queues
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null, 120));
                engine.processEvent(new WorkInstructionEvent("wi-2", "queue-1", "CHE-002", PENDING, null, 120));
                engine.processEvent(new WorkInstructionEvent("wi-3", "queue-2", "CHE-003", PENDING, null, 120));

                // Activate queue-1
                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE, 0));

                assertEquals(1, sideEffects.size());
                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);
                // 2 containers with multi-device workflow: 2 + 3 (offset) = 5 takts
                assertEquals(5, created.takts().size(),
                        "Only takts for queue-1 work instructions should be generated");
            }
        }

        @Nested
        @DisplayName("F-4.3: Work instructions persistence after abort")
        class WorkInstructionsPersistence {

            @Test
            @DisplayName("Should retain work instructions after schedule abort")
            void abort_retainsWorkInstructions() {
                // Register work instructions
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null, 120));
                engine.processEvent(new WorkInstructionEvent("wi-2", "queue-1", "CHE-002", PENDING, null, 120));

                // Activate and then abort
                engine.processEvent(new WorkQueueMessage("queue-1", ACTIVE, 0));
                engine.processEvent(new WorkQueueMessage("queue-1", INACTIVE, 0));

                // Reactivate
                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE, 0));

                assertEquals(1, sideEffects.size());
                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);
                // 2 containers with multi-device workflow: 2 + 3 (offset) = 5 takts
                assertEquals(5, created.takts().size(),
                        "Takts should be regenerated from retained work instructions after abort");
            }

            @Test
            @DisplayName("Should include new work instructions added after abort on reactivation")
            void abort_newInstructionsIncludedOnReactivation() {
                // Register initial work instructions
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null, 120));

                // Activate and abort
                engine.processEvent(new WorkQueueMessage("queue-1", ACTIVE, 0));
                engine.processEvent(new WorkQueueMessage("queue-1", INACTIVE, 0));

                // Add new work instruction
                engine.processEvent(new WorkInstructionEvent("wi-2", "queue-1", "CHE-002", PENDING, null, 120));

                // Reactivate
                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE, 0));

                assertEquals(1, sideEffects.size());
                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);
                // 2 containers with multi-device workflow: 2 + 3 (offset) = 5 takts
                assertEquals(5, created.takts().size(),
                        "Both original and new work instructions should generate takts");
            }
        }

        @Nested
        @DisplayName("F-4.4: Work instruction status values")
        class WorkInstructionStatusValues {

            @Test
            @DisplayName("Should handle all work instruction status values")
            void allStatusValues_handled() {
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null, 120));
                engine.processEvent(new WorkInstructionEvent("wi-2", "queue-1", "CHE-002", IN_PROGRESS, null, 120));
                engine.processEvent(new WorkInstructionEvent("wi-3", "queue-1", "CHE-003", COMPLETED, null, 120));
                engine.processEvent(new WorkInstructionEvent("wi-4", "queue-1", "CHE-004", CANCELLED, null, 120));

                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE, 0));

                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);
                // 4 containers with multi-device workflow: 4 + 3 (offset) = 7 takts
                assertEquals(7, created.takts().size(),
                        "All 4 work instructions should generate takts");
            }
        }

        @Nested
        @DisplayName("F-4.5: Complete F-4 workflow as per requirements")
        class CompleteF4Workflow {

            @Test
            @DisplayName("Should match exact behavior from F-4 requirements")
            void completeF4Workflow() {
                // Step 1: Register work instructions (no side effects)
                List<SideEffect> effects1 = engine.processEvent(
                        new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null, 120));
                assertTrue(effects1.isEmpty(),
                        "F-4 Requirement: WorkInstructionEvent should not produce side effects");

                List<SideEffect> effects2 = engine.processEvent(
                        new WorkInstructionEvent("wi-2", "queue-1", "CHE-002", PENDING, null, 120));
                assertTrue(effects2.isEmpty(),
                        "F-4 Requirement: WorkInstructionEvent should not produce side effects");

                List<SideEffect> effects3 = engine.processEvent(
                        new WorkInstructionEvent("wi-3", "queue-2", "CHE-003", PENDING, null, 120));
                assertTrue(effects3.isEmpty(),
                        "F-4 Requirement: WorkInstructionEvent should not produce side effects");

                // Step 2: Activate queue-1 - should generate takts for wi-1 and wi-2 but not wi-3
                List<SideEffect> effects4 = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE, 0));

                assertEquals(1, effects4.size(),
                        "F-4 Requirement: Active should produce ScheduleCreated");
                assertInstanceOf(ScheduleCreated.class, effects4.get(0));

                ScheduleCreated created = (ScheduleCreated) effects4.get(0);
                assertEquals("queue-1", created.workQueueId());
                // 2 containers with multi-device workflow: 2 + 3 (offset) = 5 takts
                assertEquals(5, created.takts().size(),
                        "F-4 Requirement: Only queue-1 work instructions should generate takts");
            }

            @Test
            @DisplayName("Should generate takts on reactivation after abort")
            void reactivationGeneratesTakts() {
                // Register initial instructions
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null, 120));
                engine.processEvent(new WorkInstructionEvent("wi-2", "queue-1", "CHE-002", PENDING, null, 120));

                // Activate
                List<SideEffect> created1 = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE, 0));
                // 2 containers with multi-device workflow: 2 + 3 (offset) = 5 takts
                assertEquals(5, ((ScheduleCreated) created1.get(0)).takts().size());

                // Abort
                engine.processEvent(new WorkQueueMessage("queue-1", INACTIVE, 0));

                // Add new instruction
                engine.processEvent(new WorkInstructionEvent("wi-4", "queue-1", "CHE-004", PENDING, null, 120));

                // Reactivate - should include all 3 instructions as takts
                List<SideEffect> created2 = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE, 0));

                ScheduleCreated reactivated = (ScheduleCreated) created2.get(0);
                // 3 containers with multi-device workflow: 3 + 3 (offset) = 6 takts
                assertEquals(6, reactivated.takts().size(),
                        "F-4 Requirement: All work instructions including new ones should generate takts");
            }
        }

        @Nested
        @DisplayName("F-4.6: Step back with work instructions")
        class StepBackWithWorkInstructions {

            @Test
            @DisplayName("Should restore work instructions state on step back")
            void stepBack_restoresWorkInstructionsState() {
                // Register work instruction
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null, 120));

                // Activate queue
                engine.processEvent(new WorkQueueMessage("queue-1", ACTIVE, 0));

                // Add another work instruction
                engine.processEvent(new WorkInstructionEvent("wi-2", "queue-1", "CHE-002", PENDING, null, 120));

                // Step back - should remove wi-2
                boolean success = engine.stepBack();
                assertTrue(success, "Step back should succeed");

                // Abort and reactivate to see current takts
                engine.processEvent(new WorkQueueMessage("queue-1", INACTIVE, 0));
                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE, 0));

                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);
                // 1 container with multi-device workflow: 1 + 3 (offset) = 4 takts
                assertEquals(4, created.takts().size(),
                        "Only 3 takts should be present after step back (1 container)");
            }
        }

        @Nested
        @DisplayName("F-4.7: Work instruction queue changes")
        class WorkInstructionQueueChanges {

            @Test
            @DisplayName("Should move work instruction when workQueueId changes")
            void workInstructionMovedToNewQueue() {
                // Register work instruction to queue-1
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null, 120));

                // Move work instruction to queue-2
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-2", "CHE-001", PENDING, null, 120));

                // Activate both queues and verify
                List<SideEffect> effects1 = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE, 0));
                List<SideEffect> effects2 = engine.processEvent(
                        new WorkQueueMessage("queue-2", ACTIVE, 0));

                ScheduleCreated created1 = (ScheduleCreated) effects1.get(0);
                ScheduleCreated created2 = (ScheduleCreated) effects2.get(0);

                assertTrue(created1.takts().isEmpty(),
                        "queue-1 should have no takts after move");
                // 1 container with multi-device workflow: 1 + 3 (offset) = 4 takts
                assertEquals(4, created2.takts().size(),
                        "queue-2 should have takts from moved work instruction");
            }

            @Test
            @DisplayName("Should update work instruction properties when same ID is processed again")
            void workInstructionUpdatedInSameQueue() {
                // Register work instruction
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null, 120));

                // Update the same work instruction with new properties
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-002", IN_PROGRESS, null, 120));

                // Activate and verify only one container's worth of takts
                List<SideEffect> effects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE, 0));

                ScheduleCreated created = (ScheduleCreated) effects.get(0);
                // 1 container with multi-device workflow: 1 + 3 (offset) = 4 takts
                assertEquals(4, created.takts().size(),
                        "Should have only 3 takts (1 container, not duplicated)");
            }

            @Test
            @DisplayName("Should correctly track instruction after multiple queue moves")
            void workInstructionMultipleMoves() {
                // Register to queue-1
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null, 120));

                // Move to queue-2
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-2", "CHE-001", PENDING, null, 120));

                // Move to queue-3
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-3", "CHE-001", PENDING, null, 120));

                // Move back to queue-1
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null, 120));

                // Activate all queues
                List<SideEffect> effects1 = engine.processEvent(new WorkQueueMessage("queue-1", ACTIVE, 0));
                List<SideEffect> effects2 = engine.processEvent(new WorkQueueMessage("queue-2", ACTIVE, 0));
                List<SideEffect> effects3 = engine.processEvent(new WorkQueueMessage("queue-3", ACTIVE, 0));

                ScheduleCreated created1 = (ScheduleCreated) effects1.get(0);
                ScheduleCreated created2 = (ScheduleCreated) effects2.get(0);
                ScheduleCreated created3 = (ScheduleCreated) effects3.get(0);

                // 1 container with multi-device workflow: 1 + 3 (offset) = 4 takts
                assertEquals(4, created1.takts().size(),
                        "queue-1 should have the takts");
                assertTrue(created2.takts().isEmpty(),
                        "queue-2 should have no takts");
                assertTrue(created3.takts().isEmpty(),
                        "queue-3 should have no takts");
            }

            @Test
            @DisplayName("Should not affect other work instructions when one is moved")
            void moveDoesNotAffectOtherInstructions() {
                // Register multiple instructions
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null, 120));
                engine.processEvent(new WorkInstructionEvent("wi-2", "queue-1", "CHE-002", PENDING, null, 120));
                engine.processEvent(new WorkInstructionEvent("wi-3", "queue-2", "CHE-003", PENDING, null, 120));

                // Move wi-1 to queue-2
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-2", "CHE-001", PENDING, null, 120));

                // Activate both queues
                List<SideEffect> effects1 = engine.processEvent(new WorkQueueMessage("queue-1", ACTIVE, 0));
                List<SideEffect> effects2 = engine.processEvent(new WorkQueueMessage("queue-2", ACTIVE, 0));

                ScheduleCreated created1 = (ScheduleCreated) effects1.get(0);
                ScheduleCreated created2 = (ScheduleCreated) effects2.get(0);

                // queue-1 should have 4 takts (1 container: wi-2)
                assertEquals(4, created1.takts().size());

                // queue-2 should have 5 takts (2 containers: wi-1 and wi-3)
                assertEquals(5, created2.takts().size());
            }
        }
    }

    @Nested
    @DisplayName("F-5: Multi-Device Takt Generation")
    class TaktGenerationTests {

        @Nested
        @DisplayName("F-5.1: Takt naming convention")
        class TaktNamingConvention {

            @Test
            @DisplayName("Should name pre-QC takts as PULSE and QC takts as TAKT")
            void taktsNamedWithPulseAndTakt() {
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null, 120));
                engine.processEvent(new WorkInstructionEvent("wi-2", "queue-1", "CHE-002", PENDING, null, 120));
                engine.processEvent(new WorkInstructionEvent("wi-3", "queue-1", "CHE-003", PENDING, null, 120));

                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE, 0));

                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);
                List<Takt> takts = created.takts();

                // 3 containers with multi-device workflow: 3 + 3 (offset) = 6 takts
                assertEquals(6, takts.size());
                assertEquals("PULSE97", takts.get(0).name());
                assertEquals("PULSE98", takts.get(1).name());
                assertEquals("PULSE99", takts.get(2).name());
                assertEquals("TAKT100", takts.get(3).name());
                assertEquals("TAKT101", takts.get(4).name());
                assertEquals("TAKT102", takts.get(5).name());
            }

            @Test
            @DisplayName("Single work instruction should create 4 takts (PULSE97-TAKT100)")
            void singleWorkInstruction_createsFourTakts() {
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null, 120));

                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE, 0));

                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);
                // 1 container with extra pulse: PULSE97, PULSE98, PULSE99, TAKT100
                assertEquals(4, created.takts().size());
                assertEquals("PULSE97", created.takts().get(0).name());
                assertEquals("PULSE98", created.takts().get(1).name());
                assertEquals("PULSE99", created.takts().get(2).name());
                assertEquals("TAKT100", created.takts().get(3).name());
            }
        }

        @Nested
        @DisplayName("F-5.2: Multi-device actions")
        class MultiDeviceActions {

            @Test
            @DisplayName("Single container should have 14 actions across 4 takts")
            void singleContainer_hasFourteenActions() {
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null, 120));

                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE, 0));

                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);
                int totalActions = created.takts().stream()
                        .mapToInt(t -> t.actions().size())
                        .sum();
                assertEquals(14, totalActions, "Single container workflow should have 14 actions total");
            }

            @Test
            @DisplayName("PULSE97 should have RTG prep + TT approach (4 actions)")
            void taktA_rtgPrepAndTtApproach() {
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null, 120));

                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE, 0));

                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);
                Takt takt100 = created.takts().get(0);

                assertEquals(4, takt100.actions().size());

                List<Action> rtgActions = takt100.actions().stream()
                        .filter(a -> a.deviceType() == DeviceType.RTG)
                        .collect(Collectors.toList());
                assertEquals(2, rtgActions.size());
                assertEquals("rtg drive", rtgActions.get(0).description());
                assertEquals("fetch", rtgActions.get(1).description());

                List<Action> ttActions = takt100.actions().stream()
                        .filter(a -> a.deviceType() == DeviceType.TT)
                        .collect(Collectors.toList());
                assertEquals(2, ttActions.size());
                assertEquals("drive to RTG pull", ttActions.get(0).description());
                assertEquals("drive to RTG standby", ttActions.get(1).description());
            }

            @Test
            @DisplayName("PULSE98 should have RTG-TT handover + TT transit + drive under QC (6 actions)")
            void taktB_rtgTtHandoverAndTransit() {
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null, 120));

                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE, 0));

                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);
                Takt pulse98 = created.takts().get(1);

                assertEquals(6, pulse98.actions().size());

                List<Action> rtgActions = pulse98.actions().stream()
                        .filter(a -> a.deviceType() == DeviceType.RTG)
                        .collect(Collectors.toList());
                assertEquals(1, rtgActions.size());
                assertEquals("rtg handover to TT", rtgActions.get(0).description());

                List<Action> ttActions = pulse98.actions().stream()
                        .filter(a -> a.deviceType() == DeviceType.TT)
                        .collect(Collectors.toList());
                assertEquals(5, ttActions.size());
                assertEquals("drive to RTG under", ttActions.get(0).description());
                assertEquals("handover from RTG", ttActions.get(1).description());
                assertEquals("drive to QC pull", ttActions.get(2).description());
                assertEquals("drive to QC standby", ttActions.get(3).description());
                assertEquals("drive under QC", ttActions.get(4).description());

                // No QC actions yet - TT not in position
                List<Action> qcActions = pulse98.actions().stream()
                        .filter(a -> a.deviceType() == DeviceType.QC)
                        .collect(Collectors.toList());
                assertTrue(qcActions.isEmpty(), "No QC actions in handover+transit takt");
            }

            @Test
            @DisplayName("PULSE99 should be an empty gap takt (0 actions)")
            void taktC_emptyGap() {
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null, 120));

                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE, 0));

                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);
                Takt pulse99 = created.takts().get(2);

                assertEquals(0, pulse99.actions().size(),
                        "Gap takt should have no actions for a single container");
            }

            @Test
            @DisplayName("TAKT100 should have TT-QC handover + QC ops (4 actions)")
            void taktD_ttQcHandoverAndQcOps() {
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null, 120));

                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE, 0));

                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);
                Takt takt100 = created.takts().get(3);

                assertEquals(4, takt100.actions().size());

                List<Action> ttActions = takt100.actions().stream()
                        .filter(a -> a.deviceType() == DeviceType.TT)
                        .collect(Collectors.toList());
                assertEquals(2, ttActions.size());
                assertEquals("handover to QC", ttActions.get(0).description());
                assertEquals("drive to buffer", ttActions.get(1).description());

                List<Action> qcActions = takt100.actions().stream()
                        .filter(a -> a.deviceType() == DeviceType.QC)
                        .collect(Collectors.toList());
                assertEquals(2, qcActions.size());
                assertEquals("handover from TT", qcActions.get(0).description());
                assertEquals("place on vessel", qcActions.get(1).description());
            }
        }

        @Nested
        @DisplayName("F-5.3: Multi-container overlapping schedule")
        class MultiContainerOverlap {

            @Test
            @DisplayName("Two containers should have overlapping actions in shared takts")
            void twoContainers_haveOverlappingActions() {
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null, 120));
                engine.processEvent(new WorkInstructionEvent("wi-2", "queue-1", "CHE-002", PENDING, null, 120));

                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE, 0));

                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);
                // 2 containers: 2 + 3 (offset) = 5 takts
                assertEquals(5, created.takts().size());

                // PULSE97: Container 0 Takt A (4 actions)
                assertEquals(4, created.takts().get(0).actions().size());

                // PULSE98: Container 0 Takt B (5) + Container 1 Takt A (4) = 9 actions
                assertEquals(9, created.takts().get(1).actions().size());

                // PULSE99: Container 0 Takt C (1) + Container 1 Takt B (5) = 6 actions
                assertEquals(6, created.takts().get(2).actions().size());

                // TAKT100: Container 0 Takt D (4) + Container 1 Takt C (1) = 5 actions
                assertEquals(5, created.takts().get(3).actions().size());

                // TAKT101: Container 1 Takt D (4 actions)
                assertEquals(4, created.takts().get(4).actions().size());
            }
        }

        @Nested
        @DisplayName("F-5.4: Action dependencies")
        class ActionDependencies {

            @Test
            @DisplayName("First RTG and first TT action should have no dependencies (parallel start)")
            void firstActionsOfEachDeviceHaveNoDependencies() {
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null, 120));

                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE, 0));

                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);
                Takt takt100 = created.takts().get(0);

                // First RTG action (rtg drive) has no dependencies
                Action rtgDrive = takt100.actions().stream()
                        .filter(a -> a.description().equals("rtg drive"))
                        .findFirst().orElseThrow();
                assertTrue(rtgDrive.hasNoDependencies(),
                        "First RTG action should have no dependencies");

                // First TT action (drive to RTG pull) has no dependencies
                Action ttDrive = takt100.actions().stream()
                        .filter(a -> a.description().equals("drive to RTG pull"))
                        .findFirst().orElseThrow();
                assertTrue(ttDrive.hasNoDependencies(),
                        "First TT action should have no dependencies");
            }

            @Test
            @DisplayName("Actions within same device should be sequential")
            void sameDeviceActionsAreSequential() {
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null, 120));

                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE, 0));

                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);

                // Collect all actions and group by device
                List<Action> allActions = created.takts().stream()
                        .flatMap(t -> t.actions().stream())
                        .collect(Collectors.toList());

                // RTG: rtg drive → fetch → rtg handover to TT
                List<Action> rtgActions = allActions.stream()
                        .filter(a -> a.deviceType() == DeviceType.RTG)
                        .collect(Collectors.toList());
                assertEquals(3, rtgActions.size());
                assertTrue(rtgActions.get(0).hasNoDependencies());
                assertTrue(rtgActions.get(1).dependsOn().contains(rtgActions.get(0).id()),
                        "fetch should depend on rtg drive");
                assertTrue(rtgActions.get(2).dependsOn().contains(rtgActions.get(1).id()),
                        "rtg handover to TT should depend on fetch");

                // QC: handover from TT → place on vessel
                List<Action> qcActions = allActions.stream()
                        .filter(a -> a.deviceType() == DeviceType.QC)
                        .collect(Collectors.toList());
                assertEquals(2, qcActions.size());
                assertTrue(qcActions.get(1).dependsOn().contains(qcActions.get(0).id()),
                        "place on vessel should depend on handover from TT");
            }

            @Test
            @DisplayName("Handover from RTG should depend on previous TT action (drive to RTG under)")
            void handoverFromRtg_dependsOnPreviousTtAction() {
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null, 120));

                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE, 0));

                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);
                List<Action> allActions = created.takts().stream()
                        .flatMap(t -> t.actions().stream())
                        .collect(Collectors.toList());

                Action driveToRtgUnder = allActions.stream()
                        .filter(a -> a.description().equals("drive to RTG under"))
                        .findFirst().orElseThrow();
                Action handoverFromRtg = allActions.stream()
                        .filter(a -> a.description().equals("handover from RTG"))
                        .findFirst().orElseThrow();

                assertEquals(1, handoverFromRtg.dependsOn().size(),
                        "handover from RTG should have 1 dependency (prev TT action)");
                assertTrue(handoverFromRtg.dependsOn().contains(driveToRtgUnder.id()),
                        "handover from RTG should depend on drive to RTG under (prev TT)");
            }

            @Test
            @DisplayName("Handover from TT and handover to QC both depend on drive under QC")
            void handoverFromTtAndHandoverToQc_bothDependOnDriveUnderQc() {
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null, 120));

                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE, 0));

                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);
                List<Action> allActions = created.takts().stream()
                        .flatMap(t -> t.actions().stream())
                        .collect(Collectors.toList());

                Action driveUnderQc = allActions.stream()
                        .filter(a -> a.description().equals("drive under QC"))
                        .findFirst().orElseThrow();
                Action handoverToQc = allActions.stream()
                        .filter(a -> a.description().equals("handover to QC"))
                        .findFirst().orElseThrow();
                Action handoverFromTt = allActions.stream()
                        .filter(a -> a.description().equals("handover from TT"))
                        .findFirst().orElseThrow();

                // TT "handover to QC" depends on TT "drive under QC" (same-device sequential)
                assertTrue(handoverToQc.dependsOn().contains(driveUnderQc.id()),
                        "handover to QC should depend on drive under QC (same-device TT)");

                // QC "handover from TT" depends on TT "drive under QC" (cross-device)
                assertTrue(handoverFromTt.dependsOn().contains(driveUnderQc.id()),
                        "handover from TT should depend on drive under QC (cross-device TT dependency)");
            }

            @Test
            @DisplayName("RTG handover to TT must depend on TT drive to RTG under (cross-device)")
            void rtgHandoverToTt_dependsOnDriveToRtgUnder() {
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null, 120));

                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE, 0));

                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);
                List<Action> allActions = created.takts().stream()
                        .flatMap(t -> t.actions().stream())
                        .collect(Collectors.toList());

                Action rtgHandoverToTT = allActions.stream()
                        .filter(a -> a.description().equals("rtg handover to TT"))
                        .findFirst().orElseThrow();
                Action driveToRtgUnder = allActions.stream()
                        .filter(a -> a.description().equals("drive to RTG under"))
                        .findFirst().orElseThrow();
                Action fetch = allActions.stream()
                        .filter(a -> a.description().equals("fetch"))
                        .findFirst().orElseThrow();

                assertTrue(rtgHandoverToTT.dependsOn().contains(driveToRtgUnder.id()),
                        "rtg handover to TT must depend on drive to RTG under (cross-device TT dependency)");
                assertTrue(rtgHandoverToTT.dependsOn().contains(fetch.id()),
                        "rtg handover to TT must depend on fetch (same-device RTG dependency)");
                assertEquals(2, rtgHandoverToTT.dependsOn().size(),
                        "rtg handover to TT should have exactly 2 dependencies");
            }

            @Test
            @DisplayName("Shared devices (RTG, QC) chain across containers")
            void sharedDevices_chainAcrossContainers() {
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null, 120));
                engine.processEvent(new WorkInstructionEvent("wi-2", "queue-1", "CHE-002", PENDING, null, 120));

                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE, 0));

                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);
                List<Action> allActions = created.takts().stream()
                        .flatMap(t -> t.actions().stream())
                        .collect(Collectors.toList());

                // Container 0's last RTG action
                Action c0RtgHandoverToTT = allActions.stream()
                        .filter(a -> a.containerIndex() == 0 && a.description().equals("rtg handover to TT"))
                        .findFirst().orElseThrow();
                // Container 1's first RTG action
                Action c1RtgDrive = allActions.stream()
                        .filter(a -> a.containerIndex() == 1 && a.description().equals("rtg drive"))
                        .findFirst().orElseThrow();

                assertTrue(c1RtgDrive.dependsOn().contains(c0RtgHandoverToTT.id()),
                        "Container 1's RTG rtg drive must depend on container 0's RTG rtg handover to TT");
                assertEquals(1, c1RtgDrive.dependsOn().size(),
                        "Container 1's first RTG action should only depend on container 0's last RTG action");

                // Container 0's last QC action
                Action c0PlaceOnVessel = allActions.stream()
                        .filter(a -> a.containerIndex() == 0 && a.description().equals("place on vessel"))
                        .findFirst().orElseThrow();
                // Container 1's first QC action
                Action c1HandoverFromTT = allActions.stream()
                        .filter(a -> a.containerIndex() == 1 && a.description().equals("handover from TT"))
                        .findFirst().orElseThrow();

                assertTrue(c1HandoverFromTT.dependsOn().contains(c0PlaceOnVessel.id()),
                        "Container 1's QC handover from TT must depend on container 0's QC place on vessel");
            }

            @Test
            @DisplayName("Per-container device (TT) does not chain across containers")
            void perContainerDevice_TT_doesNotChainAcrossContainers() {
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null, 120));
                engine.processEvent(new WorkInstructionEvent("wi-2", "queue-1", "CHE-002", PENDING, null, 120));

                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE, 0));

                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);
                List<Action> allActions = created.takts().stream()
                        .flatMap(t -> t.actions().stream())
                        .collect(Collectors.toList());

                // Container 0's last TT action
                Action c0DriveToBuffer = allActions.stream()
                        .filter(a -> a.containerIndex() == 0 && a.description().equals("drive to buffer"))
                        .findFirst().orElseThrow();
                // Container 1's first TT action
                Action c1DriveToRtgPull = allActions.stream()
                        .filter(a -> a.containerIndex() == 1 && a.description().equals("drive to RTG pull"))
                        .findFirst().orElseThrow();

                assertFalse(c1DriveToRtgPull.dependsOn().contains(c0DriveToBuffer.id()),
                        "Container 1's TT drive to RTG pull must NOT depend on container 0's TT drive to buffer");
                assertTrue(c1DriveToRtgPull.hasNoDependencies(),
                        "Container 1's first TT action should have no dependencies (separate truck)");
            }
        }
    }
}
