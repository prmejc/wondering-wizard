package com.wonderingwizard.processors;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.DeviceType;
import com.wonderingwizard.domain.takt.Takt;
import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.ActionCompletedEvent;
import com.wonderingwizard.events.TimeEvent;
import com.wonderingwizard.events.WorkInstructionEvent;
import com.wonderingwizard.events.WorkQueueMessage;
import com.wonderingwizard.sideeffects.ActionActivated;
import com.wonderingwizard.sideeffects.ActionCompleted;
import com.wonderingwizard.sideeffects.ScheduleCreated;
import com.wonderingwizard.sideeffects.TaktActivated;
import com.wonderingwizard.sideeffects.TaktCompleted;
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
 * - Takt state machine (Waiting → Active → Completed)
 * - Action state machine (Waiting → Active → Completed)
 * - Action activation based on takt state and dependencies
 * - UUID matching for action completion events
 */
@DisplayName("ScheduleRunnerProcessor Tests")
class ScheduleRunnerProcessorTest {

    private static final Instant EMT = Instant.parse("2024-01-01T10:00:00Z");

    private ScheduleRunnerProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ScheduleRunnerProcessor();
    }

    @Nested
    @DisplayName("Takt Activation on TimeEvent")
    class TaktActivationTests {

        @Test
        @DisplayName("Should activate first takt and its root action when TimeEvent timestamp >= startTime")
        void activatesFirstTaktAndActionWhenTimeReached() {
            List<Takt> takts = createLinkedTakts(2, EMT);
            processor.process(new ScheduleCreated("queue-1", takts, EMT));

            Instant currentTime = Instant.parse("2024-01-01T10:00:01Z");
            List<SideEffect> sideEffects = processor.process(new TimeEvent(currentTime));

            // TaktActivated for TAKT100 + ActionActivated for first action
            assertEquals(2, sideEffects.size());
            assertInstanceOf(TaktActivated.class, sideEffects.get(0));
            assertInstanceOf(ActionActivated.class, sideEffects.get(1));

            TaktActivated taktActivated = (TaktActivated) sideEffects.get(0);
            assertEquals("queue-1", taktActivated.workQueueId());
            assertEquals("TAKT100", taktActivated.taktName());
            assertEquals(currentTime, taktActivated.activatedAt());

            ActionActivated activated = (ActionActivated) sideEffects.get(1);
            assertEquals("queue-1", activated.workQueueId());
            assertEquals("TAKT100", activated.taktName());
            assertEquals("QC lift container from truck", activated.actionDescription());
        }

        @Test
        @DisplayName("Should not activate takt when TimeEvent timestamp < startTime")
        void doesNotActivateBeforeStartTime() {
            List<Takt> takts = createLinkedTakts(1, EMT);
            processor.process(new ScheduleCreated("queue-1", takts, EMT));

            Instant currentTime = Instant.parse("2024-01-01T09:59:59Z");
            List<SideEffect> sideEffects = processor.process(new TimeEvent(currentTime));

            assertTrue(sideEffects.isEmpty());
        }

        @Test
        @DisplayName("Should activate exactly at startTime")
        void activatesExactlyAtStartTime() {
            List<Takt> takts = createLinkedTakts(1, EMT);
            processor.process(new ScheduleCreated("queue-1", takts, EMT));

            List<SideEffect> sideEffects = processor.process(new TimeEvent(EMT));

            assertEquals(2, sideEffects.size());
            assertInstanceOf(TaktActivated.class, sideEffects.get(0));
            assertInstanceOf(ActionActivated.class, sideEffects.get(1));
        }

        @Test
        @DisplayName("Should only activate takt once even with multiple TimeEvents")
        void activatesOnlyOnce() {
            List<Takt> takts = createLinkedTakts(1, EMT);
            processor.process(new ScheduleCreated("queue-1", takts, EMT));

            Instant time1 = Instant.parse("2024-01-01T10:00:01Z");
            processor.process(new TimeEvent(time1));

            Instant time2 = Instant.parse("2024-01-01T10:00:02Z");
            List<SideEffect> sideEffects = processor.process(new TimeEvent(time2));

            assertTrue(sideEffects.isEmpty());
        }

        @Test
        @DisplayName("Should not activate second takt until first takt is completed")
        void doesNotActivateNextTaktUntilPreviousCompleted() {
            List<Takt> takts = createLinkedTakts(2, EMT);
            processor.process(new ScheduleCreated("queue-1", takts, EMT));

            // Activate first takt
            processor.process(new TimeEvent(Instant.parse("2024-01-01T10:00:01Z")));

            // Additional TimeEvents should not activate second takt
            List<SideEffect> sideEffects = processor.process(new TimeEvent(Instant.parse("2024-01-01T10:00:02Z")));
            assertTrue(sideEffects.isEmpty());
        }
    }

    @Nested
    @DisplayName("Action Completion")
    class ActionCompletionTests {

        @Test
        @DisplayName("Should complete action and activate next when ActionCompletedEvent matches active action ID")
        void completesActionAndActivatesNext() {
            List<Takt> takts = createLinkedTakts(1, EMT);
            UUID firstActionId = takts.get(0).actions().get(0).id();
            UUID secondActionId = takts.get(0).actions().get(1).id();

            processor.process(new ScheduleCreated("queue-1", takts, EMT));
            processor.process(new TimeEvent(Instant.parse("2024-01-01T10:00:01Z")));

            List<SideEffect> sideEffects = processor.process(
                    new ActionCompletedEvent(firstActionId, "queue-1"));

            // ActionCompleted + ActionActivated (same takt, no takt state change)
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
            List<Takt> takts = createLinkedTakts(1, EMT);
            processor.process(new ScheduleCreated("queue-1", takts, EMT));
            processor.process(new TimeEvent(Instant.parse("2024-01-01T10:00:01Z")));

            UUID wrongId = UUID.randomUUID();
            List<SideEffect> sideEffects = processor.process(
                    new ActionCompletedEvent(wrongId, "queue-1"));

            assertTrue(sideEffects.isEmpty());
        }

        @Test
        @DisplayName("Should not complete action when work queue doesn't exist")
        void ignoresUnknownWorkQueue() {
            List<Takt> takts = createLinkedTakts(1, EMT);
            UUID firstActionId = takts.get(0).actions().get(0).id();

            processor.process(new ScheduleCreated("queue-1", takts, EMT));
            processor.process(new TimeEvent(Instant.parse("2024-01-01T10:00:01Z")));

            List<SideEffect> sideEffects = processor.process(
                    new ActionCompletedEvent(firstActionId, "queue-2"));

            assertTrue(sideEffects.isEmpty());
        }
    }

    @Nested
    @DisplayName("Cross-Takt Progression with Takt State Machine")
    class CrossTaktProgressionTests {

        @Test
        @DisplayName("Should complete takt and activate next takt when all actions complete")
        void progressesToNextTakt() {
            List<Takt> takts = createLinkedTakts(2, EMT);
            UUID firstTaktAction1 = takts.get(0).actions().get(0).id();
            UUID firstTaktAction2 = takts.get(0).actions().get(1).id();
            UUID secondTaktAction1 = takts.get(1).actions().get(0).id();

            processor.process(new ScheduleCreated("queue-1", takts, EMT));
            processor.process(new TimeEvent(Instant.parse("2024-01-01T10:00:01Z")));

            // Complete first action (same takt, no takt state change)
            processor.process(new ActionCompletedEvent(firstTaktAction1, "queue-1"));

            // Complete second action (last of first takt)
            List<SideEffect> sideEffects = processor.process(
                    new ActionCompletedEvent(firstTaktAction2, "queue-1"));

            // ActionCompleted + TaktCompleted(TAKT100) + TaktActivated(TAKT101) + ActionActivated
            assertEquals(4, sideEffects.size());

            assertInstanceOf(ActionCompleted.class, sideEffects.get(0));
            ActionCompleted completed = (ActionCompleted) sideEffects.get(0);
            assertEquals(firstTaktAction2, completed.actionId());
            assertEquals("TAKT100", completed.taktName());

            assertInstanceOf(TaktCompleted.class, sideEffects.get(1));
            TaktCompleted taktCompleted = (TaktCompleted) sideEffects.get(1);
            assertEquals("TAKT100", taktCompleted.taktName());

            assertInstanceOf(TaktActivated.class, sideEffects.get(2));
            TaktActivated taktActivated = (TaktActivated) sideEffects.get(2);
            assertEquals("TAKT101", taktActivated.taktName());

            assertInstanceOf(ActionActivated.class, sideEffects.get(3));
            ActionActivated activated = (ActionActivated) sideEffects.get(3);
            assertEquals(secondTaktAction1, activated.actionId());
            assertEquals("TAKT101", activated.taktName());
        }

        @Test
        @DisplayName("Should produce TaktCompleted when last action of last takt completes")
        void lastTaktCompletes() {
            List<Takt> takts = createLinkedTakts(1, EMT);
            UUID firstActionId = takts.get(0).actions().get(0).id();
            UUID lastActionId = takts.get(0).actions().get(1).id();

            processor.process(new ScheduleCreated("queue-1", takts, EMT));
            processor.process(new TimeEvent(Instant.parse("2024-01-01T10:00:01Z")));
            processor.process(new ActionCompletedEvent(firstActionId, "queue-1"));

            List<SideEffect> sideEffects = processor.process(
                    new ActionCompletedEvent(lastActionId, "queue-1"));

            // ActionCompleted + TaktCompleted (no next takt to activate)
            assertEquals(2, sideEffects.size());
            assertInstanceOf(ActionCompleted.class, sideEffects.get(0));
            assertInstanceOf(TaktCompleted.class, sideEffects.get(1));

            ActionCompleted completed = (ActionCompleted) sideEffects.get(0);
            assertEquals(lastActionId, completed.actionId());

            TaktCompleted taktCompleted = (TaktCompleted) sideEffects.get(1);
            assertEquals("TAKT100", taktCompleted.taktName());
        }
    }

    @Nested
    @DisplayName("Schedule Deactivation")
    class ScheduleDeactivationTests {

        @Test
        @DisplayName("Should stop processing after schedule is deactivated")
        void stopsProcessingAfterDeactivation() {
            List<Takt> takts = createLinkedTakts(1, EMT);
            UUID firstActionId = takts.get(0).actions().get(0).id();

            processor.process(new ScheduleCreated("queue-1", takts, EMT));
            processor.process(new TimeEvent(Instant.parse("2024-01-01T10:00:01Z")));

            processor.process(new WorkQueueMessage("queue-1", INACTIVE));

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
            List<Takt> takts = createLinkedTakts(1, EMT);
            UUID firstActionId = takts.get(0).actions().get(0).id();

            processor.process(new ScheduleCreated("queue-1", takts, EMT));
            processor.process(new TimeEvent(Instant.parse("2024-01-01T10:00:01Z")));

            Object state = processor.captureState();

            processor.process(new ActionCompletedEvent(firstActionId, "queue-1"));

            processor.restoreState(state);

            List<SideEffect> sideEffects = processor.process(
                    new ActionCompletedEvent(firstActionId, "queue-1"));

            // ActionCompleted + ActionActivated (same takt, restored to active state)
            assertEquals(2, sideEffects.size());
            assertInstanceOf(ActionCompleted.class, sideEffects.get(0));
            assertInstanceOf(ActionActivated.class, sideEffects.get(1));
        }
    }

    @Nested
    @DisplayName("Complete Workflow with Takt State Machine")
    class CompleteWorkflowTests {

        @Test
        @DisplayName("Should execute complete workflow with takt state transitions")
        void completeWorkflow() {
            List<Takt> takts = createLinkedTakts(2, EMT);

            UUID takt1Action1 = takts.get(0).actions().get(0).id();
            UUID takt1Action2 = takts.get(0).actions().get(1).id();
            UUID takt2Action1 = takts.get(1).actions().get(0).id();
            UUID takt2Action2 = takts.get(1).actions().get(1).id();

            processor.process(new ScheduleCreated("queue-1", takts, EMT));

            // Step 1: TimeEvent activates first takt and first action
            List<SideEffect> step1 = processor.process(new TimeEvent(Instant.parse("2024-01-01T10:00:01Z")));
            assertEquals(2, step1.size());
            assertInstanceOf(TaktActivated.class, step1.get(0));
            ActionActivated activated1 = (ActionActivated) step1.get(1);
            assertEquals(takt1Action1, activated1.actionId());

            // Step 2: Complete first action -> activates second (same takt)
            List<SideEffect> step2 = processor.process(new ActionCompletedEvent(takt1Action1, "queue-1"));
            assertEquals(2, step2.size());
            assertInstanceOf(ActionCompleted.class, step2.get(0));
            assertInstanceOf(ActionActivated.class, step2.get(1));

            // Step 3: Complete second action -> takt1 completed, takt2 activated, first action of takt2 activated
            List<SideEffect> step3 = processor.process(new ActionCompletedEvent(takt1Action2, "queue-1"));
            assertEquals(4, step3.size());
            assertInstanceOf(ActionCompleted.class, step3.get(0));
            assertInstanceOf(TaktCompleted.class, step3.get(1));
            assertInstanceOf(TaktActivated.class, step3.get(2));
            assertInstanceOf(ActionActivated.class, step3.get(3));

            TaktCompleted tc = (TaktCompleted) step3.get(1);
            assertEquals("TAKT100", tc.taktName());
            TaktActivated ta = (TaktActivated) step3.get(2);
            assertEquals("TAKT101", ta.taktName());

            // Step 4: Complete first action of second takt
            List<SideEffect> step4 = processor.process(new ActionCompletedEvent(takt2Action1, "queue-1"));
            assertEquals(2, step4.size());
            assertInstanceOf(ActionCompleted.class, step4.get(0));
            assertInstanceOf(ActionActivated.class, step4.get(1));

            // Step 5: Complete last action -> takt completed, no more takts
            List<SideEffect> step5 = processor.process(new ActionCompletedEvent(takt2Action2, "queue-1"));
            assertEquals(2, step5.size());
            assertInstanceOf(ActionCompleted.class, step5.get(0));
            assertInstanceOf(TaktCompleted.class, step5.get(1));
            TaktCompleted lastTc = (TaktCompleted) step5.get(1);
            assertEquals("TAKT101", lastTc.taktName());
        }
    }

    @Nested
    @DisplayName("Multiple Dependencies")
    class MultipleDependenciesTests {

        @Test
        @DisplayName("Should only activate action when ALL dependencies are completed")
        void activatesOnlyWhenAllDependenciesComplete() {
            Action action1 = Action.create("Action 1");
            Action action2 = Action.create("Action 2");
            Action action3 = new Action(UUID.randomUUID(), DeviceType.QC, "Action 3", Set.of(action1.id(), action2.id()));

            Takt takt = new Takt("TAKT100", List.of(action1, action2, action3), EMT, EMT);
            List<Takt> takts = List.of(takt);

            processor.process(new ScheduleCreated("queue-1", takts, EMT));

            // TimeEvent: TaktActivated + ActionActivated(action1) + ActionActivated(action2)
            List<SideEffect> step1 = processor.process(new TimeEvent(Instant.parse("2024-01-01T10:00:01Z")));
            assertEquals(3, step1.size());
            assertInstanceOf(TaktActivated.class, step1.get(0));

            // Complete only action1 - action3 should NOT activate yet
            List<SideEffect> step2 = processor.process(new ActionCompletedEvent(action1.id(), "queue-1"));
            assertEquals(1, step2.size());
            assertInstanceOf(ActionCompleted.class, step2.get(0));

            // Complete action2 - now action3 should activate
            List<SideEffect> step3 = processor.process(new ActionCompletedEvent(action2.id(), "queue-1"));
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
            Action action1 = Action.create("Action 1");
            Action action2 = new Action(UUID.randomUUID(), DeviceType.QC, "Action 2", Set.of(action1.id()));
            Action action3 = new Action(UUID.randomUUID(), DeviceType.QC, "Action 3", Set.of(action1.id()));

            Takt takt = new Takt("TAKT100", List.of(action1, action2, action3), EMT, EMT);
            List<Takt> takts = List.of(takt);

            processor.process(new ScheduleCreated("queue-1", takts, EMT));

            // Start schedule - TaktActivated + only action1 should activate
            processor.process(new TimeEvent(Instant.parse("2024-01-01T10:00:01Z")));

            // Complete action1 -> both action2 and action3 should activate
            List<SideEffect> sideEffects = processor.process(new ActionCompletedEvent(action1.id(), "queue-1"));

            assertEquals(3, sideEffects.size()); // 1 ActionCompleted + 2 ActionActivated
            assertInstanceOf(ActionCompleted.class, sideEffects.get(0));

            long activatedCount = sideEffects.stream()
                    .filter(se -> se instanceof ActionActivated)
                    .count();
            assertEquals(2, activatedCount);
        }
    }

    /**
     * Helper method to create takts with proper action dependencies and start times.
     */
    private List<Takt> createLinkedTakts(int count, Instant startTime) {
        Action[][] allActions = new Action[count][2];
        for (int i = 0; i < count; i++) {
            allActions[i][0] = Action.create("QC lift container from truck");
            allActions[i][1] = Action.create("QC place container on vessel");
        }

        List<Takt> takts = new java.util.ArrayList<>();
        for (int taktIndex = 0; taktIndex < count; taktIndex++) {
            Action action1 = allActions[taktIndex][0];
            Action action2 = allActions[taktIndex][1];

            Set<UUID> action1Deps;
            if (taktIndex == 0) {
                action1Deps = Set.of();
            } else {
                action1Deps = Set.of(allActions[taktIndex - 1][1].id());
            }

            Set<UUID> action2Deps = Set.of(action1.id());

            Action linkedAction1 = action1.withDependencies(action1Deps);
            Action linkedAction2 = action2.withDependencies(action2Deps);

            String taktName = Takt.createTaktName(taktIndex, 0);
            takts.add(new Takt(taktName, List.of(linkedAction1, linkedAction2), startTime, startTime));
        }

        return takts;
    }
}
