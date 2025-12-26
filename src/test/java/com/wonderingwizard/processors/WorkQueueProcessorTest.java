package com.wonderingwizard.processors;

import com.wonderingwizard.engine.EventProcessingEngine;
import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.WorkInstructionEvent;
import com.wonderingwizard.events.WorkInstructionStatus;
import com.wonderingwizard.events.WorkQueueMessage;
import com.wonderingwizard.events.WorkQueueStatus;
import com.wonderingwizard.sideeffects.ScheduleAborted;
import com.wonderingwizard.sideeffects.ScheduleCreated;
import com.wonderingwizard.sideeffects.WorkInstruction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.wonderingwizard.events.WorkInstructionStatus.*;
import static com.wonderingwizard.events.WorkQueueStatus.ACTIVE;
import static com.wonderingwizard.events.WorkQueueStatus.INACTIVE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for F-2: Schedule Creation and F-4: Work Instruction Event features.
 *
 * @see <a href="docs/requirements.md">F-2 and F-4 Requirements</a>
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
                        new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING));

                assertTrue(sideEffects.isEmpty(),
                        "WorkInstructionEvent should not produce side effects");
            }

            @Test
            @DisplayName("Should store multiple work instructions for same work queue")
            void multipleWorkInstructions_sameQueue_allStored() {
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING));
                engine.processEvent(new WorkInstructionEvent("wi-2", "queue-1", "CHE-002", PENDING));
                engine.processEvent(new WorkInstructionEvent("wi-3", "queue-1", "CHE-003", IN_PROGRESS));

                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE));

                assertEquals(1, sideEffects.size());
                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);
                assertEquals(3, created.workInstructions().size(),
                        "All three work instructions should be included");
            }
        }

        @Nested
        @DisplayName("F-4.2: Work instructions in ScheduleCreated")
        class WorkInstructionsInScheduleCreated {

            @Test
            @DisplayName("Should include work instructions in ScheduleCreated side effect")
            void activeMessage_includesWorkInstructions() {
                // Register work instructions
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING));
                engine.processEvent(new WorkInstructionEvent("wi-2", "queue-1", "CHE-002", IN_PROGRESS));

                // Activate work queue
                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE));

                assertEquals(1, sideEffects.size());
                assertInstanceOf(ScheduleCreated.class, sideEffects.get(0));

                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);
                assertEquals("queue-1", created.workQueueId());
                assertEquals(2, created.workInstructions().size());

                // Verify first work instruction
                WorkInstruction wi1 = created.workInstructions().get(0);
                assertEquals("wi-1", wi1.workInstructionId());
                assertEquals("queue-1", wi1.workQueueId());
                assertEquals("CHE-001", wi1.fetchChe());
                assertEquals(PENDING, wi1.status());

                // Verify second work instruction
                WorkInstruction wi2 = created.workInstructions().get(1);
                assertEquals("wi-2", wi2.workInstructionId());
                assertEquals("queue-1", wi2.workQueueId());
                assertEquals("CHE-002", wi2.fetchChe());
                assertEquals(IN_PROGRESS, wi2.status());
            }

            @Test
            @DisplayName("Should return empty list when no work instructions registered")
            void activeMessage_noWorkInstructions_returnsEmptyList() {
                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE));

                assertEquals(1, sideEffects.size());
                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);
                assertTrue(created.workInstructions().isEmpty(),
                        "Work instructions list should be empty when none registered");
            }

            @Test
            @DisplayName("Should only include work instructions for the activated work queue")
            void activeMessage_onlyIncludesMatchingWorkInstructions() {
                // Register work instructions for different queues
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING));
                engine.processEvent(new WorkInstructionEvent("wi-2", "queue-1", "CHE-002", PENDING));
                engine.processEvent(new WorkInstructionEvent("wi-3", "queue-2", "CHE-003", PENDING));

                // Activate queue-1
                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE));

                assertEquals(1, sideEffects.size());
                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);
                assertEquals(2, created.workInstructions().size(),
                        "Only work instructions for queue-1 should be included");

                // Verify only queue-1 instructions are present
                assertTrue(created.workInstructions().stream()
                                .allMatch(wi -> "queue-1".equals(wi.workQueueId())),
                        "All work instructions should belong to queue-1");
            }
        }

        @Nested
        @DisplayName("F-4.3: Work instructions persistence after abort")
        class WorkInstructionsPersistence {

            @Test
            @DisplayName("Should retain work instructions after schedule abort")
            void abort_retainsWorkInstructions() {
                // Register work instructions
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING));
                engine.processEvent(new WorkInstructionEvent("wi-2", "queue-1", "CHE-002", PENDING));

                // Activate and then abort
                engine.processEvent(new WorkQueueMessage("queue-1", ACTIVE));
                engine.processEvent(new WorkQueueMessage("queue-1", INACTIVE));

                // Reactivate
                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE));

                assertEquals(1, sideEffects.size());
                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);
                assertEquals(2, created.workInstructions().size(),
                        "Work instructions should be retained after abort");
            }

            @Test
            @DisplayName("Should include new work instructions added after abort on reactivation")
            void abort_newInstructionsIncludedOnReactivation() {
                // Register initial work instructions
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING));

                // Activate and abort
                engine.processEvent(new WorkQueueMessage("queue-1", ACTIVE));
                engine.processEvent(new WorkQueueMessage("queue-1", INACTIVE));

                // Add new work instruction
                engine.processEvent(new WorkInstructionEvent("wi-2", "queue-1", "CHE-002", PENDING));

                // Reactivate
                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE));

                assertEquals(1, sideEffects.size());
                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);
                assertEquals(2, created.workInstructions().size(),
                        "Both original and new work instructions should be included");
            }
        }

        @Nested
        @DisplayName("F-4.4: Work instruction status values")
        class WorkInstructionStatusValues {

            @Test
            @DisplayName("Should handle all work instruction status values")
            void allStatusValues_handled() {
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING));
                engine.processEvent(new WorkInstructionEvent("wi-2", "queue-1", "CHE-002", IN_PROGRESS));
                engine.processEvent(new WorkInstructionEvent("wi-3", "queue-1", "CHE-003", COMPLETED));
                engine.processEvent(new WorkInstructionEvent("wi-4", "queue-1", "CHE-004", CANCELLED));

                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE));

                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);
                assertEquals(4, created.workInstructions().size());

                assertEquals(PENDING, created.workInstructions().get(0).status());
                assertEquals(IN_PROGRESS, created.workInstructions().get(1).status());
                assertEquals(COMPLETED, created.workInstructions().get(2).status());
                assertEquals(CANCELLED, created.workInstructions().get(3).status());
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
                        new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING));
                assertTrue(effects1.isEmpty(),
                        "F-4 Requirement: WorkInstructionEvent should not produce side effects");

                List<SideEffect> effects2 = engine.processEvent(
                        new WorkInstructionEvent("wi-2", "queue-1", "CHE-002", PENDING));
                assertTrue(effects2.isEmpty(),
                        "F-4 Requirement: WorkInstructionEvent should not produce side effects");

                List<SideEffect> effects3 = engine.processEvent(
                        new WorkInstructionEvent("wi-3", "queue-2", "CHE-003", PENDING));
                assertTrue(effects3.isEmpty(),
                        "F-4 Requirement: WorkInstructionEvent should not produce side effects");

                // Step 2: Activate queue-1 - should include wi-1 and wi-2 but not wi-3
                List<SideEffect> effects4 = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE));

                assertEquals(1, effects4.size(),
                        "F-4 Requirement: Active should produce ScheduleCreated");
                assertInstanceOf(ScheduleCreated.class, effects4.get(0));

                ScheduleCreated created = (ScheduleCreated) effects4.get(0);
                assertEquals("queue-1", created.workQueueId());
                assertEquals(2, created.workInstructions().size(),
                        "F-4 Requirement: Only queue-1 work instructions should be included");

                List<String> instructionIds = created.workInstructions().stream()
                        .map(WorkInstruction::workInstructionId)
                        .toList();
                assertTrue(instructionIds.contains("wi-1"),
                        "F-4 Requirement: wi-1 should be included");
                assertTrue(instructionIds.contains("wi-2"),
                        "F-4 Requirement: wi-2 should be included");
                assertFalse(instructionIds.contains("wi-3"),
                        "F-4 Requirement: wi-3 should NOT be included (belongs to queue-2)");
            }

            @Test
            @DisplayName("Should include work instructions on reactivation after abort")
            void reactivationIncludesAllInstructions() {
                // Register initial instructions
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING));
                engine.processEvent(new WorkInstructionEvent("wi-2", "queue-1", "CHE-002", PENDING));

                // Activate
                List<SideEffect> created1 = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE));
                assertEquals(2, ((ScheduleCreated) created1.get(0)).workInstructions().size());

                // Abort
                engine.processEvent(new WorkQueueMessage("queue-1", INACTIVE));

                // Add new instruction
                engine.processEvent(new WorkInstructionEvent("wi-4", "queue-1", "CHE-004", PENDING));

                // Reactivate - should include all 3 instructions
                List<SideEffect> created2 = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE));

                ScheduleCreated reactivated = (ScheduleCreated) created2.get(0);
                assertEquals(3, reactivated.workInstructions().size(),
                        "F-4 Requirement: All work instructions including new ones should be included");

                List<String> instructionIds = reactivated.workInstructions().stream()
                        .map(WorkInstruction::workInstructionId)
                        .toList();
                assertTrue(instructionIds.contains("wi-1"));
                assertTrue(instructionIds.contains("wi-2"));
                assertTrue(instructionIds.contains("wi-4"));
            }
        }

        @Nested
        @DisplayName("F-4.6: Step back with work instructions")
        class StepBackWithWorkInstructions {

            @Test
            @DisplayName("Should restore work instructions state on step back")
            void stepBack_restoresWorkInstructionsState() {
                // Register work instruction
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING));

                // Activate queue
                engine.processEvent(new WorkQueueMessage("queue-1", ACTIVE));

                // Add another work instruction
                engine.processEvent(new WorkInstructionEvent("wi-2", "queue-1", "CHE-002", PENDING));

                // Step back - should remove wi-2
                boolean success = engine.stepBack();
                assertTrue(success, "Step back should succeed");

                // Abort and reactivate to see current work instructions
                engine.processEvent(new WorkQueueMessage("queue-1", INACTIVE));
                List<SideEffect> sideEffects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE));

                ScheduleCreated created = (ScheduleCreated) sideEffects.get(0);
                assertEquals(1, created.workInstructions().size(),
                        "Only wi-1 should be present after step back");
                assertEquals("wi-1", created.workInstructions().get(0).workInstructionId());
            }
        }

        @Nested
        @DisplayName("F-4.7: Work instruction queue changes")
        class WorkInstructionQueueChanges {

            @Test
            @DisplayName("Should move work instruction when workQueueId changes")
            void workInstructionMovedToNewQueue() {
                // Register work instruction to queue-1
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING));

                // Move work instruction to queue-2
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-2", "CHE-001", PENDING));

                // Activate both queues and verify
                List<SideEffect> effects1 = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE));
                List<SideEffect> effects2 = engine.processEvent(
                        new WorkQueueMessage("queue-2", ACTIVE));

                ScheduleCreated created1 = (ScheduleCreated) effects1.get(0);
                ScheduleCreated created2 = (ScheduleCreated) effects2.get(0);

                assertTrue(created1.workInstructions().isEmpty(),
                        "queue-1 should have no work instructions after move");
                assertEquals(1, created2.workInstructions().size(),
                        "queue-2 should have the moved work instruction");
                assertEquals("wi-1", created2.workInstructions().get(0).workInstructionId());
            }

            @Test
            @DisplayName("Should update work instruction properties when same ID is processed again")
            void workInstructionUpdatedInSameQueue() {
                // Register work instruction
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING));

                // Update the same work instruction with new properties
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-002", IN_PROGRESS));

                // Activate and verify only one instruction with updated values
                List<SideEffect> effects = engine.processEvent(
                        new WorkQueueMessage("queue-1", ACTIVE));

                ScheduleCreated created = (ScheduleCreated) effects.get(0);
                assertEquals(1, created.workInstructions().size(),
                        "Should have only one work instruction (not duplicated)");

                WorkInstruction wi = created.workInstructions().get(0);
                assertEquals("wi-1", wi.workInstructionId());
                assertEquals("CHE-002", wi.fetchChe(),
                        "fetchChe should be updated");
                assertEquals(IN_PROGRESS, wi.status(),
                        "status should be updated");
            }

            @Test
            @DisplayName("Should correctly track instruction after multiple queue moves")
            void workInstructionMultipleMoves() {
                // Register to queue-1
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING));

                // Move to queue-2
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-2", "CHE-001", PENDING));

                // Move to queue-3
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-3", "CHE-001", PENDING));

                // Move back to queue-1
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING));

                // Activate all queues
                List<SideEffect> effects1 = engine.processEvent(new WorkQueueMessage("queue-1", ACTIVE));
                List<SideEffect> effects2 = engine.processEvent(new WorkQueueMessage("queue-2", ACTIVE));
                List<SideEffect> effects3 = engine.processEvent(new WorkQueueMessage("queue-3", ACTIVE));

                ScheduleCreated created1 = (ScheduleCreated) effects1.get(0);
                ScheduleCreated created2 = (ScheduleCreated) effects2.get(0);
                ScheduleCreated created3 = (ScheduleCreated) effects3.get(0);

                assertEquals(1, created1.workInstructions().size(),
                        "queue-1 should have the work instruction");
                assertTrue(created2.workInstructions().isEmpty(),
                        "queue-2 should have no work instructions");
                assertTrue(created3.workInstructions().isEmpty(),
                        "queue-3 should have no work instructions");
            }

            @Test
            @DisplayName("Should not affect other work instructions when one is moved")
            void moveDoesNotAffectOtherInstructions() {
                // Register multiple instructions
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING));
                engine.processEvent(new WorkInstructionEvent("wi-2", "queue-1", "CHE-002", PENDING));
                engine.processEvent(new WorkInstructionEvent("wi-3", "queue-2", "CHE-003", PENDING));

                // Move wi-1 to queue-2
                engine.processEvent(new WorkInstructionEvent("wi-1", "queue-2", "CHE-001", PENDING));

                // Activate both queues
                List<SideEffect> effects1 = engine.processEvent(new WorkQueueMessage("queue-1", ACTIVE));
                List<SideEffect> effects2 = engine.processEvent(new WorkQueueMessage("queue-2", ACTIVE));

                ScheduleCreated created1 = (ScheduleCreated) effects1.get(0);
                ScheduleCreated created2 = (ScheduleCreated) effects2.get(0);

                // queue-1 should only have wi-2
                assertEquals(1, created1.workInstructions().size());
                assertEquals("wi-2", created1.workInstructions().get(0).workInstructionId());

                // queue-2 should have wi-1 and wi-3
                assertEquals(2, created2.workInstructions().size());
                List<String> queue2Ids = created2.workInstructions().stream()
                        .map(WorkInstruction::workInstructionId)
                        .toList();
                assertTrue(queue2Ids.contains("wi-1"));
                assertTrue(queue2Ids.contains("wi-3"));
            }
        }
    }
}
