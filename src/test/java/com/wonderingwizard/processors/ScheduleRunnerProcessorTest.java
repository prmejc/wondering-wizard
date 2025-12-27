package com.wonderingwizard.processors;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.Takt;
import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.ActionCompletedEvent;
import com.wonderingwizard.events.TimeEvent;
import com.wonderingwizard.events.WorkInstructionEvent;
import com.wonderingwizard.events.WorkQueueMessage;
import com.wonderingwizard.sideeffects.ActionActivated;
import com.wonderingwizard.sideeffects.ActionCompleted;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.wonderingwizard.events.WorkInstructionStatus.PENDING;
import static com.wonderingwizard.events.WorkQueueStatus.ACTIVE;
import static com.wonderingwizard.events.WorkQueueStatus.INACTIVE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ScheduleRunnerProcessor.
 * <p>
 * Tests the schedule execution logic including:
 * - Action activation based on estimatedMoveTime
 * - Action completion and progression to next action
 * - UUID matching for action completion events
 */
@DisplayName("ScheduleRunnerProcessor Tests")
class ScheduleRunnerProcessorTest {

    private ScheduleRunnerProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ScheduleRunnerProcessor();
    }

    @Nested
    @DisplayName("Action Activation on TimeEvent")
    class ActionActivationTests {

        @Test
        @DisplayName("Should activate first action when TimeEvent timestamp >= estimatedMoveTime")
        void activatesFirstActionWhenTimeReached() {
            // Given: A schedule with estimatedMoveTime in the past
            Instant estimatedMoveTime = Instant.parse("2024-01-01T10:00:00Z");
            List<Takt> takts = createLinkedTakts(2);

            processor.initializeSchedule("queue-1", takts, estimatedMoveTime);

            // When: TimeEvent with timestamp after estimatedMoveTime
            Instant currentTime = Instant.parse("2024-01-01T10:00:01Z");
            List<SideEffect> sideEffects = processor.process(new TimeEvent(currentTime));

            // Then: First action should be activated
            assertEquals(1, sideEffects.size());
            assertInstanceOf(ActionActivated.class, sideEffects.get(0));

            ActionActivated activated = (ActionActivated) sideEffects.get(0);
            assertEquals("queue-1", activated.workQueueId());
            assertEquals("TAKT100", activated.taktName());
            assertEquals("QC lift container from truck", activated.actionDescription());
            assertEquals(currentTime, activated.activatedAt());
        }

        @Test
        @DisplayName("Should not activate action when TimeEvent timestamp < estimatedMoveTime")
        void doesNotActivateBeforeEstimatedMoveTime() {
            // Given: A schedule with estimatedMoveTime in the future
            Instant estimatedMoveTime = Instant.parse("2024-01-01T10:00:00Z");
            List<Takt> takts = createLinkedTakts(1);

            processor.initializeSchedule("queue-1", takts, estimatedMoveTime);

            // When: TimeEvent with timestamp before estimatedMoveTime
            Instant currentTime = Instant.parse("2024-01-01T09:59:59Z");
            List<SideEffect> sideEffects = processor.process(new TimeEvent(currentTime));

            // Then: No action should be activated
            assertTrue(sideEffects.isEmpty());
        }

        @Test
        @DisplayName("Should activate exactly at estimatedMoveTime")
        void activatesExactlyAtEstimatedMoveTime() {
            // Given: A schedule with specific estimatedMoveTime
            Instant estimatedMoveTime = Instant.parse("2024-01-01T10:00:00Z");
            List<Takt> takts = createLinkedTakts(1);

            processor.initializeSchedule("queue-1", takts, estimatedMoveTime);

            // When: TimeEvent exactly at estimatedMoveTime
            List<SideEffect> sideEffects = processor.process(new TimeEvent(estimatedMoveTime));

            // Then: First action should be activated
            assertEquals(1, sideEffects.size());
            assertInstanceOf(ActionActivated.class, sideEffects.get(0));
        }

        @Test
        @DisplayName("Should only activate once even with multiple TimeEvents after estimatedMoveTime")
        void activatesOnlyOnce() {
            // Given: A schedule that has already been activated
            Instant estimatedMoveTime = Instant.parse("2024-01-01T10:00:00Z");
            List<Takt> takts = createLinkedTakts(1);

            processor.initializeSchedule("queue-1", takts, estimatedMoveTime);

            // First TimeEvent triggers activation
            Instant time1 = Instant.parse("2024-01-01T10:00:01Z");
            processor.process(new TimeEvent(time1));

            // When: Second TimeEvent
            Instant time2 = Instant.parse("2024-01-01T10:00:02Z");
            List<SideEffect> sideEffects = processor.process(new TimeEvent(time2));

            // Then: Should not produce additional activation
            assertTrue(sideEffects.isEmpty());
        }
    }

    @Nested
    @DisplayName("Action Completion")
    class ActionCompletionTests {

        @Test
        @DisplayName("Should complete action and activate next when ActionCompletedEvent matches active action ID")
        void completesActionAndActivatesNext() {
            // Given: A schedule with first action active
            Instant estimatedMoveTime = Instant.parse("2024-01-01T10:00:00Z");
            List<Takt> takts = createLinkedTakts(1);
            UUID firstActionId = takts.get(0).actions().get(0).id();
            UUID secondActionId = takts.get(0).actions().get(1).id();

            processor.initializeSchedule("queue-1", takts, estimatedMoveTime);
            processor.process(new TimeEvent(Instant.parse("2024-01-01T10:00:01Z")));

            // When: ActionCompletedEvent for the active action
            List<SideEffect> sideEffects = processor.process(
                    new ActionCompletedEvent(firstActionId, "queue-1"));

            // Then: Should produce ActionCompleted and ActionActivated
            assertEquals(2, sideEffects.size());

            ActionCompleted completed = (ActionCompleted) sideEffects.get(0);
            assertEquals(firstActionId, completed.actionId());
            assertEquals("queue-1", completed.workQueueId());
            assertEquals("TAKT100", completed.taktName());
            assertEquals("QC lift container from truck", completed.actionDescription());

            ActionActivated activated = (ActionActivated) sideEffects.get(1);
            assertEquals(secondActionId, activated.actionId());
            assertEquals("queue-1", activated.workQueueId());
            assertEquals("TAKT100", activated.taktName());
            assertEquals("QC place container on vessel", activated.actionDescription());
        }

        @Test
        @DisplayName("Should not complete action when ActionCompletedEvent has wrong action ID")
        void ignoresWrongActionId() {
            // Given: A schedule with first action active
            Instant estimatedMoveTime = Instant.parse("2024-01-01T10:00:00Z");
            List<Takt> takts = createLinkedTakts(1);

            processor.initializeSchedule("queue-1", takts, estimatedMoveTime);
            processor.process(new TimeEvent(Instant.parse("2024-01-01T10:00:01Z")));

            // When: ActionCompletedEvent with wrong UUID
            UUID wrongId = UUID.randomUUID();
            List<SideEffect> sideEffects = processor.process(
                    new ActionCompletedEvent(wrongId, "queue-1"));

            // Then: Should not produce any side effects
            assertTrue(sideEffects.isEmpty());
        }

        @Test
        @DisplayName("Should not complete action when work queue doesn't exist")
        void ignoresUnknownWorkQueue() {
            // Given: A schedule for queue-1
            Instant estimatedMoveTime = Instant.parse("2024-01-01T10:00:00Z");
            List<Takt> takts = createLinkedTakts(1);
            UUID firstActionId = takts.get(0).actions().get(0).id();

            processor.initializeSchedule("queue-1", takts, estimatedMoveTime);
            processor.process(new TimeEvent(Instant.parse("2024-01-01T10:00:01Z")));

            // When: ActionCompletedEvent for different queue
            List<SideEffect> sideEffects = processor.process(
                    new ActionCompletedEvent(firstActionId, "queue-2"));

            // Then: Should not produce any side effects
            assertTrue(sideEffects.isEmpty());
        }
    }

    @Nested
    @DisplayName("Cross-Takt Action Progression")
    class CrossTaktProgressionTests {

        @Test
        @DisplayName("Should activate first action of next takt when last action of current takt completes")
        void progressesToNextTakt() {
            // Given: A schedule with 2 takts, last action of first takt active
            Instant estimatedMoveTime = Instant.parse("2024-01-01T10:00:00Z");
            List<Takt> takts = createLinkedTakts(2);
            UUID firstTaktAction1 = takts.get(0).actions().get(0).id();
            UUID firstTaktAction2 = takts.get(0).actions().get(1).id();
            UUID secondTaktAction1 = takts.get(1).actions().get(0).id();

            processor.initializeSchedule("queue-1", takts, estimatedMoveTime);
            processor.process(new TimeEvent(Instant.parse("2024-01-01T10:00:01Z")));

            // Complete first action
            processor.process(new ActionCompletedEvent(firstTaktAction1, "queue-1"));

            // When: Complete second action (last of first takt)
            List<SideEffect> sideEffects = processor.process(
                    new ActionCompletedEvent(firstTaktAction2, "queue-1"));

            // Then: Should complete action and activate first action of next takt
            assertEquals(2, sideEffects.size());

            ActionCompleted completed = (ActionCompleted) sideEffects.get(0);
            assertEquals(firstTaktAction2, completed.actionId());
            assertEquals("TAKT100", completed.taktName());
            assertEquals("QC place container on vessel", completed.actionDescription());

            ActionActivated activated = (ActionActivated) sideEffects.get(1);
            assertEquals(secondTaktAction1, activated.actionId());
            assertEquals("TAKT101", activated.taktName());
            assertEquals("QC lift container from truck", activated.actionDescription());
        }

        @Test
        @DisplayName("Should only produce ActionCompleted when last action of last takt completes")
        void noNextActionAfterLastTakt() {
            // Given: A schedule with 1 takt, last action active
            Instant estimatedMoveTime = Instant.parse("2024-01-01T10:00:00Z");
            List<Takt> takts = createLinkedTakts(1);
            UUID firstActionId = takts.get(0).actions().get(0).id();
            UUID lastActionId = takts.get(0).actions().get(1).id();

            processor.initializeSchedule("queue-1", takts, estimatedMoveTime);
            processor.process(new TimeEvent(Instant.parse("2024-01-01T10:00:01Z")));
            processor.process(new ActionCompletedEvent(firstActionId, "queue-1"));

            // When: Complete last action
            List<SideEffect> sideEffects = processor.process(
                    new ActionCompletedEvent(lastActionId, "queue-1"));

            // Then: Should only produce ActionCompleted (no next action)
            assertEquals(1, sideEffects.size());
            assertInstanceOf(ActionCompleted.class, sideEffects.get(0));
            ActionCompleted completed = (ActionCompleted) sideEffects.get(0);
            assertEquals(lastActionId, completed.actionId());
        }
    }

    @Nested
    @DisplayName("Schedule Deactivation")
    class ScheduleDeactivationTests {

        @Test
        @DisplayName("Should stop processing after schedule is deactivated")
        void stopsProcessingAfterDeactivation() {
            // Given: An active schedule
            Instant estimatedMoveTime = Instant.parse("2024-01-01T10:00:00Z");
            List<Takt> takts = createLinkedTakts(1);
            UUID firstActionId = takts.get(0).actions().get(0).id();

            processor.initializeSchedule("queue-1", takts, estimatedMoveTime);
            processor.process(new TimeEvent(Instant.parse("2024-01-01T10:00:01Z")));

            // When: Schedule is deactivated
            processor.process(new WorkQueueMessage("queue-1", INACTIVE));

            // Then: ActionCompletedEvent should be ignored
            List<SideEffect> sideEffects = processor.process(
                    new ActionCompletedEvent(firstActionId, "queue-1"));
            assertTrue(sideEffects.isEmpty());
        }
    }

    @Nested
    @DisplayName("State Management")
    class StateManagementTests {

        @Test
        @DisplayName("Should capture and restore state correctly")
        void capturesAndRestoresState() {
            // Given: A schedule with first action activated
            Instant estimatedMoveTime = Instant.parse("2024-01-01T10:00:00Z");
            List<Takt> takts = createLinkedTakts(1);
            UUID firstActionId = takts.get(0).actions().get(0).id();

            processor.initializeSchedule("queue-1", takts, estimatedMoveTime);
            processor.process(new TimeEvent(Instant.parse("2024-01-01T10:00:01Z")));

            // Capture state
            Object state = processor.captureState();

            // Complete the action
            processor.process(new ActionCompletedEvent(firstActionId, "queue-1"));

            // When: Restore state
            processor.restoreState(state);

            // Then: Should be able to complete the first action again
            List<SideEffect> sideEffects = processor.process(
                    new ActionCompletedEvent(firstActionId, "queue-1"));

            assertEquals(2, sideEffects.size());
            assertInstanceOf(ActionCompleted.class, sideEffects.get(0));
            assertInstanceOf(ActionActivated.class, sideEffects.get(1));
        }
    }

    @Nested
    @DisplayName("Complete Workflow")
    class CompleteWorkflowTests {

        @Test
        @DisplayName("Should execute complete workflow: activate -> complete -> next -> complete")
        void completeWorkflow() {
            // Given: A schedule with 2 takts
            Instant estimatedMoveTime = Instant.parse("2024-01-01T10:00:00Z");
            List<Takt> takts = createLinkedTakts(2);

            UUID takt1Action1 = takts.get(0).actions().get(0).id();
            UUID takt1Action2 = takts.get(0).actions().get(1).id();
            UUID takt2Action1 = takts.get(1).actions().get(0).id();
            UUID takt2Action2 = takts.get(1).actions().get(1).id();

            processor.initializeSchedule("queue-1", takts, estimatedMoveTime);

            // Step 1: TimeEvent activates first action
            List<SideEffect> step1 = processor.process(new TimeEvent(Instant.parse("2024-01-01T10:00:01Z")));
            assertEquals(1, step1.size());
            ActionActivated activated1 = (ActionActivated) step1.get(0);
            assertEquals(takt1Action1, activated1.actionId());
            assertEquals("QC lift container from truck", activated1.actionDescription());

            // Step 2: Complete first action -> activates second
            List<SideEffect> step2 = processor.process(new ActionCompletedEvent(takt1Action1, "queue-1"));
            assertEquals(2, step2.size());
            ActionCompleted completed1 = (ActionCompleted) step2.get(0);
            ActionActivated activated2 = (ActionActivated) step2.get(1);
            assertEquals("QC lift container from truck", completed1.actionDescription());
            assertEquals("QC place container on vessel", activated2.actionDescription());

            // Step 3: Complete second action -> activates first of next takt
            List<SideEffect> step3 = processor.process(new ActionCompletedEvent(takt1Action2, "queue-1"));
            assertEquals(2, step3.size());
            ActionCompleted completed2 = (ActionCompleted) step3.get(0);
            ActionActivated activated3 = (ActionActivated) step3.get(1);
            assertEquals("TAKT100", completed2.taktName());
            assertEquals("TAKT101", activated3.taktName());
            assertEquals("QC lift container from truck", activated3.actionDescription());

            // Step 4: Complete first action of second takt
            List<SideEffect> step4 = processor.process(new ActionCompletedEvent(takt2Action1, "queue-1"));
            assertEquals(2, step4.size());

            // Step 5: Complete last action -> no more actions
            List<SideEffect> step5 = processor.process(new ActionCompletedEvent(takt2Action2, "queue-1"));
            assertEquals(1, step5.size());
            assertInstanceOf(ActionCompleted.class, step5.get(0));
        }
    }

    @Nested
    @DisplayName("Multiple Dependencies")
    class MultipleDependenciesTests {

        @Test
        @DisplayName("Should only activate action when ALL dependencies are completed")
        void activatesOnlyWhenAllDependenciesComplete() {
            // Given: An action that depends on two other actions
            Action action1 = Action.create("Action 1");
            Action action2 = Action.create("Action 2");
            // action3 depends on both action1 and action2
            Action action3 = new Action(UUID.randomUUID(), "Action 3", Set.of(action1.id(), action2.id()));

            Takt takt = new Takt("TAKT100", List.of(action1, action2, action3));
            List<Takt> takts = List.of(takt);

            Instant estimatedMoveTime = Instant.parse("2024-01-01T10:00:00Z");
            processor.initializeSchedule("queue-1", takts, estimatedMoveTime);

            // When: TimeEvent starts the schedule
            List<SideEffect> step1 = processor.process(new TimeEvent(Instant.parse("2024-01-01T10:00:01Z")));

            // Then: Both actions with no dependencies should activate
            assertEquals(2, step1.size());

            // When: Complete only action1
            List<SideEffect> step2 = processor.process(new ActionCompletedEvent(action1.id(), "queue-1"));

            // Then: action3 should NOT activate yet (still waiting for action2)
            assertEquals(1, step2.size()); // Only ActionCompleted, no ActionActivated
            assertInstanceOf(ActionCompleted.class, step2.get(0));

            // When: Complete action2
            List<SideEffect> step3 = processor.process(new ActionCompletedEvent(action2.id(), "queue-1"));

            // Then: action3 should now activate (all dependencies satisfied)
            assertEquals(2, step3.size());
            assertInstanceOf(ActionCompleted.class, step3.get(0));
            assertInstanceOf(ActionActivated.class, step3.get(1));

            ActionActivated activated = (ActionActivated) step3.get(1);
            assertEquals(action3.id(), activated.actionId());
            assertEquals("Action 3", activated.actionDescription());
        }

        @Test
        @DisplayName("Should activate multiple actions simultaneously when their dependencies are satisfied")
        void activatesMultipleActionsSimultaneously() {
            // Given: Two actions that both depend on a single action
            Action action1 = Action.create("Action 1");
            Action action2 = new Action(UUID.randomUUID(), "Action 2", Set.of(action1.id()));
            Action action3 = new Action(UUID.randomUUID(), "Action 3", Set.of(action1.id()));

            Takt takt = new Takt("TAKT100", List.of(action1, action2, action3));
            List<Takt> takts = List.of(takt);

            Instant estimatedMoveTime = Instant.parse("2024-01-01T10:00:00Z");
            processor.initializeSchedule("queue-1", takts, estimatedMoveTime);

            // Start schedule - only action1 should activate
            processor.process(new TimeEvent(Instant.parse("2024-01-01T10:00:01Z")));

            // When: Complete action1
            List<SideEffect> sideEffects = processor.process(new ActionCompletedEvent(action1.id(), "queue-1"));

            // Then: Both action2 and action3 should activate simultaneously
            assertEquals(3, sideEffects.size()); // 1 ActionCompleted + 2 ActionActivated
            assertInstanceOf(ActionCompleted.class, sideEffects.get(0));

            long activatedCount = sideEffects.stream()
                    .filter(se -> se instanceof ActionActivated)
                    .count();
            assertEquals(2, activatedCount);
        }
    }

    /**
     * Helper method to create takts with proper action dependencies.
     * Uses sequential dependencies: action2 depends on action1, next takt's action1 depends on previous takt's action2.
     */
    private List<Takt> createLinkedTakts(int count) {
        // First pass: create all actions (without dependencies)
        Action[][] allActions = new Action[count][2];
        for (int i = 0; i < count; i++) {
            allActions[i][0] = Action.create("QC lift container from truck");
            allActions[i][1] = Action.create("QC place container on vessel");
        }

        // Second pass: set up dependencies
        List<Takt> takts = new java.util.ArrayList<>();
        for (int taktIndex = 0; taktIndex < count; taktIndex++) {
            Action action1 = allActions[taktIndex][0];
            Action action2 = allActions[taktIndex][1];

            Set<UUID> action1Deps;
            if (taktIndex == 0) {
                // First action of first takt: no dependencies
                action1Deps = Set.of();
            } else {
                // First action of subsequent takts: depends on last action of previous takt
                action1Deps = Set.of(allActions[taktIndex - 1][1].id());
            }

            // Second action depends on first action of same takt
            Set<UUID> action2Deps = Set.of(action1.id());

            Action linkedAction1 = action1.withDependencies(action1Deps);
            Action linkedAction2 = action2.withDependencies(action2Deps);

            String taktName = Takt.createTaktName(taktIndex);
            takts.add(new Takt(taktName, List.of(linkedAction1, linkedAction2)));
        }

        return takts;
    }
}
