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
                    new WorkQueueMessage("queue-1", ACTIVE));

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
                    new WorkQueueMessage("queue-1", ACTIVE));
            List<SideEffect> sideEffects2 = engine.processEvent(
                    new WorkQueueMessage("queue-2", ACTIVE));

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
            engine.processEvent(new WorkQueueMessage("queue-1", ACTIVE));

            // Duplicate Active message for same workQueueId
            List<SideEffect> sideEffects = engine.processEvent(
                    new WorkQueueMessage("queue-1", ACTIVE));

            assertTrue(sideEffects.isEmpty(),
                    "Duplicate Active message should not produce side effects");
        }

        @Test
        @DisplayName("Should return empty for multiple duplicate Active messages")
        void multipleDuplicateActiveMessages_returnsEmptySideEffects() {
            // First Active message
            List<SideEffect> first = engine.processEvent(
                    new WorkQueueMessage("queue-1", ACTIVE));

            // Multiple duplicate Active messages
            List<SideEffect> second = engine.processEvent(
                    new WorkQueueMessage("queue-1", ACTIVE));
            List<SideEffect> third = engine.processEvent(
                    new WorkQueueMessage("queue-1", ACTIVE));
            List<SideEffect> fourth = engine.processEvent(
                    new WorkQueueMessage("queue-1", ACTIVE));

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
            engine.processEvent(new WorkQueueMessage("queue-1", ACTIVE));

            // Then abort it
            List<SideEffect> sideEffects = engine.processEvent(
                    new WorkQueueMessage("queue-1", INACTIVE));

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
                    new WorkQueueMessage("queue-1", INACTIVE));

            assertTrue(sideEffects.isEmpty(),
                    "Inactive without prior Active should not produce side effects");
        }

        @Test
        @DisplayName("Should return empty for duplicate Inactive messages")
        void duplicateInactiveMessage_returnsEmptySideEffects() {
            // Create and abort
            engine.processEvent(new WorkQueueMessage("queue-1", ACTIVE));
            engine.processEvent(new WorkQueueMessage("queue-1", INACTIVE));

            // Duplicate Inactive
            List<SideEffect> sideEffects = engine.processEvent(
                    new WorkQueueMessage("queue-1", INACTIVE));

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
                    new WorkQueueMessage("queue-1", ACTIVE));

            // Abort
            List<SideEffect> aborted = engine.processEvent(
                    new WorkQueueMessage("queue-1", INACTIVE));

            // Reactivate
            List<SideEffect> reactivated = engine.processEvent(
                    new WorkQueueMessage("queue-1", ACTIVE));

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
            engine.processEvent(new WorkQueueMessage("queue-1", ACTIVE));
            engine.processEvent(new WorkQueueMessage("queue-2", ACTIVE));

            // Deactivate only queue-1
            List<SideEffect> aborted = engine.processEvent(
                    new WorkQueueMessage("queue-1", INACTIVE));

            // queue-2 should still be idempotent
            List<SideEffect> stillActive = engine.processEvent(
                    new WorkQueueMessage("queue-2", ACTIVE));

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
                    new WorkQueueMessage("queue-1", ACTIVE));
            assertEquals(1, step1.size());
            assertInstanceOf(ScheduleCreated.class, step1.get(0));

            // Duplicate Active (idempotent)
            List<SideEffect> step2 = engine.processEvent(
                    new WorkQueueMessage("queue-1", ACTIVE));
            assertTrue(step2.isEmpty());

            // Abort
            List<SideEffect> step3 = engine.processEvent(
                    new WorkQueueMessage("queue-1", INACTIVE));
            assertEquals(1, step3.size());
            assertInstanceOf(ScheduleAborted.class, step3.get(0));

            // Duplicate Inactive (no effect)
            List<SideEffect> step4 = engine.processEvent(
                    new WorkQueueMessage("queue-1", INACTIVE));
            assertTrue(step4.isEmpty());

            // Recreate
            List<SideEffect> step5 = engine.processEvent(
                    new WorkQueueMessage("queue-1", ACTIVE));
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
                    new WorkQueueMessage("queue-1", null));

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
                    new WorkQueueMessage("queue-1", ACTIVE));
            assertEquals(1, sideEffects1.size(),
                    "F-2 Requirement: First Active should create schedule");
            assertInstanceOf(ScheduleCreated.class, sideEffects1.get(0));
            assertEquals("queue-1", ((ScheduleCreated) sideEffects1.get(0)).workQueueId());

            // Step 2: Duplicate Active message is idempotent
            List<SideEffect> sideEffects2 = engine.processEvent(
                    new WorkQueueMessage("queue-1", ACTIVE));
            assertTrue(sideEffects2.isEmpty(),
                    "F-2 Requirement: Duplicate Active should be idempotent");

            // Step 3: Inactive message aborts schedule
            List<SideEffect> sideEffects3 = engine.processEvent(
                    new WorkQueueMessage("queue-1", INACTIVE));
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
                        new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null));

                assertTrue(sideEffects.isEmpty(),
                        "WorkInstructionEvent should not produce side effects");
            }

            @Test
            @DisplayName("Should store multiple work instructions for same work queue")
            void multipleWorkInstructions_sameQueue_allStored() {
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null));
                engine.processEvent(new WorkInstructionEvent("wi-2", "queue-1", "CHE-002", PENDING, null));
                engine.processEvent(new WorkInstructionEvent("wi-3", "queue-1", "CHE-003", IN_PROGRESS, null));

                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE));

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
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null));
                engine.processEvent(new WorkInstructionEvent("wi-2", "queue-1", "CHE-002", IN_PROGRESS, null));

                // Activate work queue
                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE));

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
                        new WorkQueueMessage("queue-1", ACTIVE));

                assertEquals(1, sideEffects.size());
                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);
                assertTrue(created.takts().isEmpty(),
                        "Takts list should be empty when no work instructions registered");
            }

            @Test
            @DisplayName("Should only generate takts for the activated work queue")
            void activeMessage_onlyGeneratesTaktsForMatchingWorkInstructions() {
                // Register work instructions for different queues
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null));
                engine.processEvent(new WorkInstructionEvent("wi-2", "queue-1", "CHE-002", PENDING, null));
                engine.processEvent(new WorkInstructionEvent("wi-3", "queue-2", "CHE-003", PENDING, null));

                // Activate queue-1
                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE));

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
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null));
                engine.processEvent(new WorkInstructionEvent("wi-2", "queue-1", "CHE-002", PENDING, null));

                // Activate and then abort
                engine.processEvent(new WorkQueueMessage("queue-1", ACTIVE));
                engine.processEvent(new WorkQueueMessage("queue-1", INACTIVE));

                // Reactivate
                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE));

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
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null));

                // Activate and abort
                engine.processEvent(new WorkQueueMessage("queue-1", ACTIVE));
                engine.processEvent(new WorkQueueMessage("queue-1", INACTIVE));

                // Add new work instruction
                engine.processEvent(new WorkInstructionEvent("wi-2", "queue-1", "CHE-002", PENDING, null));

                // Reactivate
                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE));

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
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null));
                engine.processEvent(new WorkInstructionEvent("wi-2", "queue-1", "CHE-002", IN_PROGRESS, null));
                engine.processEvent(new WorkInstructionEvent("wi-3", "queue-1", "CHE-003", COMPLETED, null));
                engine.processEvent(new WorkInstructionEvent("wi-4", "queue-1", "CHE-004", CANCELLED, null));

                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE));

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
                        new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null));
                assertTrue(effects1.isEmpty(),
                        "F-4 Requirement: WorkInstructionEvent should not produce side effects");

                List<SideEffect> effects2 = engine.processEvent(
                        new WorkInstructionEvent("wi-2", "queue-1", "CHE-002", PENDING, null));
                assertTrue(effects2.isEmpty(),
                        "F-4 Requirement: WorkInstructionEvent should not produce side effects");

                List<SideEffect> effects3 = engine.processEvent(
                        new WorkInstructionEvent("wi-3", "queue-2", "CHE-003", PENDING, null));
                assertTrue(effects3.isEmpty(),
                        "F-4 Requirement: WorkInstructionEvent should not produce side effects");

                // Step 2: Activate queue-1 - should generate takts for wi-1 and wi-2 but not wi-3
                List<SideEffect> effects4 = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE));

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
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null));
                engine.processEvent(new WorkInstructionEvent("wi-2", "queue-1", "CHE-002", PENDING, null));

                // Activate
                List<SideEffect> created1 = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE));
                // 2 containers with multi-device workflow: 2 + 3 (offset) = 5 takts
                assertEquals(5, ((ScheduleCreated) created1.get(0)).takts().size());

                // Abort
                engine.processEvent(new WorkQueueMessage("queue-1", INACTIVE));

                // Add new instruction
                engine.processEvent(new WorkInstructionEvent("wi-4", "queue-1", "CHE-004", PENDING, null));

                // Reactivate - should include all 3 instructions as takts
                List<SideEffect> created2 = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE));

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
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null));

                // Activate queue
                engine.processEvent(new WorkQueueMessage("queue-1", ACTIVE));

                // Add another work instruction
                engine.processEvent(new WorkInstructionEvent("wi-2", "queue-1", "CHE-002", PENDING, null));

                // Step back - should remove wi-2
                boolean success = engine.stepBack();
                assertTrue(success, "Step back should succeed");

                // Abort and reactivate to see current takts
                engine.processEvent(new WorkQueueMessage("queue-1", INACTIVE));
                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE));

                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);
                // 1 container with multi-device workflow: 1 + 3 (offset) = 4 takts
                assertEquals(4, created.takts().size(),
                        "Only 4 takts should be present after step back (1 container)");
            }
        }

        @Nested
        @DisplayName("F-4.7: Work instruction queue changes")
        class WorkInstructionQueueChanges {

            @Test
            @DisplayName("Should move work instruction when workQueueId changes")
            void workInstructionMovedToNewQueue() {
                // Register work instruction to queue-1
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null));

                // Move work instruction to queue-2
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-2", "CHE-001", PENDING, null));

                // Activate both queues and verify
                List<SideEffect> effects1 = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE));
                List<SideEffect> effects2 = engine.processEvent(
                        new WorkQueueMessage("queue-2", ACTIVE));

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
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null));

                // Update the same work instruction with new properties
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-002", IN_PROGRESS, null));

                // Activate and verify only one container's worth of takts
                List<SideEffect> effects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE));

                ScheduleCreated created = (ScheduleCreated) effects.get(0);
                // 1 container with multi-device workflow: 1 + 3 (offset) = 4 takts
                assertEquals(4, created.takts().size(),
                        "Should have only 4 takts (1 container, not duplicated)");
            }

            @Test
            @DisplayName("Should correctly track instruction after multiple queue moves")
            void workInstructionMultipleMoves() {
                // Register to queue-1
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null));

                // Move to queue-2
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-2", "CHE-001", PENDING, null));

                // Move to queue-3
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-3", "CHE-001", PENDING, null));

                // Move back to queue-1
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null));

                // Activate all queues
                List<SideEffect> effects1 = engine.processEvent(new WorkQueueMessage("queue-1", ACTIVE));
                List<SideEffect> effects2 = engine.processEvent(new WorkQueueMessage("queue-2", ACTIVE));
                List<SideEffect> effects3 = engine.processEvent(new WorkQueueMessage("queue-3", ACTIVE));

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
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null));
                engine.processEvent(new WorkInstructionEvent("wi-2", "queue-1", "CHE-002", PENDING, null));
                engine.processEvent(new WorkInstructionEvent("wi-3", "queue-2", "CHE-003", PENDING, null));

                // Move wi-1 to queue-2
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-2", "CHE-001", PENDING, null));

                // Activate both queues
                List<SideEffect> effects1 = engine.processEvent(new WorkQueueMessage("queue-1", ACTIVE));
                List<SideEffect> effects2 = engine.processEvent(new WorkQueueMessage("queue-2", ACTIVE));

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
            @DisplayName("Should name takts sequentially starting from TAKT100")
            void taktsNamedSequentially() {
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null));
                engine.processEvent(new WorkInstructionEvent("wi-2", "queue-1", "CHE-002", PENDING, null));
                engine.processEvent(new WorkInstructionEvent("wi-3", "queue-1", "CHE-003", PENDING, null));

                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE));

                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);
                List<Takt> takts = created.takts();

                // 3 containers with multi-device workflow: 3 + 3 (offset) = 6 takts
                assertEquals(6, takts.size());
                assertEquals("TAKT100", takts.get(0).name());
                assertEquals("TAKT101", takts.get(1).name());
                assertEquals("TAKT102", takts.get(2).name());
                assertEquals("TAKT103", takts.get(3).name());
                assertEquals("TAKT104", takts.get(4).name());
                assertEquals("TAKT105", takts.get(5).name());
            }

            @Test
            @DisplayName("Single work instruction should create 4 takts (TAKT100-TAKT103)")
            void singleWorkInstruction_createsFourTakts() {
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null));

                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE));

                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);
                // 1 container: RTG at TAKT100, TT-RTG at TAKT101, TT-QC at TAKT102, QC at TAKT103
                assertEquals(4, created.takts().size());
                assertEquals("TAKT100", created.takts().get(0).name());
                assertEquals("TAKT103", created.takts().get(3).name());
            }
        }

        @Nested
        @DisplayName("F-5.2: Multi-device actions")
        class MultiDeviceActions {

            @Test
            @DisplayName("Single container should have 8 actions across 4 takts")
            void singleContainer_hasEightActions() {
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null));

                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE));

                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);
                int totalActions = created.takts().stream()
                        .mapToInt(t -> t.actions().size())
                        .sum();
                assertEquals(8, totalActions, "Single container workflow should have 8 actions total");
            }

            @Test
            @DisplayName("RTG actions should be in TAKT100 (offset -3 from base)")
            void rtgActionsInFirstTakt() {
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null));

                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE));

                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);
                Takt takt100 = created.takts().get(0);

                // TAKT100 should have RTG actions
                List<Action> rtgActions = takt100.actions().stream()
                        .filter(a -> a.deviceType() == DeviceType.RTG)
                        .collect(Collectors.toList());
                assertEquals(2, rtgActions.size());
                assertEquals("lift container from yard", rtgActions.get(0).description());
                assertEquals("place container on truck", rtgActions.get(1).description());
            }

            @Test
            @DisplayName("TT drive/handover at RTG should be in TAKT101 (offset -2 from base)")
            void ttRtgActionsInSecondTakt() {
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null));

                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE));

                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);
                Takt takt101 = created.takts().get(1);

                // TAKT101 should have TT actions at RTG
                List<Action> ttActions = takt101.actions().stream()
                        .filter(a -> a.deviceType() == DeviceType.TT)
                        .collect(Collectors.toList());
                assertEquals(2, ttActions.size());
                assertEquals("drive under RTG", ttActions.get(0).description());
                assertEquals("handover from RTG", ttActions.get(1).description());
            }

            @Test
            @DisplayName("TT drive/handover at QC should be in TAKT102 (offset -1 from base)")
            void ttQcActionsInThirdTakt() {
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null));

                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE));

                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);
                Takt takt102 = created.takts().get(2);

                // TAKT102 should have TT actions at QC
                List<Action> ttActions = takt102.actions().stream()
                        .filter(a -> a.deviceType() == DeviceType.TT)
                        .collect(Collectors.toList());
                assertEquals(2, ttActions.size());
                assertEquals("drive under QC", ttActions.get(0).description());
                assertEquals("handover to QC", ttActions.get(1).description());
            }

            @Test
            @DisplayName("QC actions should be in TAKT103 (base takt, offset 0)")
            void qcActionsInFourthTakt() {
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null));

                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE));

                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);
                Takt takt103 = created.takts().get(3);

                // TAKT103 should have QC actions
                List<Action> qcActions = takt103.actions().stream()
                        .filter(a -> a.deviceType() == DeviceType.QC)
                        .collect(Collectors.toList());
                assertEquals(2, qcActions.size());
                assertEquals("container lifted from truck", qcActions.get(0).description());
                assertEquals("container placed on vessel", qcActions.get(1).description());
            }
        }

        @Nested
        @DisplayName("F-5.3: Multi-container overlapping schedule")
        class MultiContainerOverlap {

            @Test
            @DisplayName("Two containers should have overlapping actions in shared takts")
            void twoContainers_haveOverlappingActions() {
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null));
                engine.processEvent(new WorkInstructionEvent("wi-2", "queue-1", "CHE-002", PENDING, null));

                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE));

                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);
                // 2 containers: 2 + 3 (offset) = 5 takts
                assertEquals(5, created.takts().size());

                // TAKT100: Container 1 RTG only
                assertEquals(2, created.takts().get(0).actions().size());

                // TAKT101: Container 1 TT-RTG + Container 2 RTG = 4 actions
                assertEquals(4, created.takts().get(1).actions().size());

                // TAKT102: Container 1 TT-QC + Container 2 TT-RTG = 4 actions
                assertEquals(4, created.takts().get(2).actions().size());

                // TAKT103: Container 1 QC + Container 2 TT-QC = 4 actions
                assertEquals(4, created.takts().get(3).actions().size());

                // TAKT104: Container 2 QC only
                assertEquals(2, created.takts().get(4).actions().size());
            }
        }

        @Nested
        @DisplayName("F-5.4: Action dependencies")
        class ActionDependencies {

            @Test
            @DisplayName("First action of first container should have no dependencies")
            void firstActionHasNoDependencies() {
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null));

                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE));

                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);
                Action firstAction = created.takts().get(0).actions().get(0);

                assertTrue(firstAction.hasNoDependencies(),
                        "First RTG action should have no dependencies");
            }

            @Test
            @DisplayName("Actions within a container workflow should be sequential")
            void actionsAreSequential() {
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null));

                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE));

                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);

                // Collect all actions across takts (for container 1, they are all from one workflow)
                List<Action> allActions = created.takts().stream()
                        .flatMap(t -> t.actions().stream())
                        .collect(Collectors.toList());

                // Each action (except first) should depend on the previous
                for (int i = 1; i < allActions.size(); i++) {
                    Action current = allActions.get(i);
                    Action previous = allActions.get(i - 1);
                    assertTrue(current.dependsOn().contains(previous.id()),
                            "Action " + i + " should depend on action " + (i - 1));
                }
            }

            @Test
            @DisplayName("Different container workflows should be independent")
            void containerWorkflowsAreIndependent() {
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null));
                engine.processEvent(new WorkInstructionEvent("wi-2", "queue-1", "CHE-002", PENDING, null));

                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE));

                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);

                // TAKT100 has only Container 1's RTG actions (2 actions)
                // TAKT101 has Container 1's TT-RTG (2) + Container 2's RTG (2) = 4 actions
                Takt takt101 = created.takts().get(1);

                // Container 2's first RTG action should have no dependencies (independent workflow)
                List<Action> rtgActions = takt101.actions().stream()
                        .filter(a -> a.deviceType() == DeviceType.RTG)
                        .collect(Collectors.toList());
                assertEquals(2, rtgActions.size());
                assertTrue(rtgActions.get(0).hasNoDependencies(),
                        "Container 2's first action should have no dependencies");
            }
        }
    }
}
