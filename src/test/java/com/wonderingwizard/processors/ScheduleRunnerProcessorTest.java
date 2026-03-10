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
            processor.process(new ScheduleCreated(1L, takts, EMT));

            Instant currentTime = Instant.parse("2024-01-01T10:00:01Z");
            List<SideEffect> sideEffects = processor.process(new TimeEvent(currentTime));

            // TaktActivated for TAKT100 + ActionActivated for first action
            assertEquals(2, sideEffects.size());
            assertInstanceOf(TaktActivated.class, sideEffects.get(0));
            assertInstanceOf(ActionActivated.class, sideEffects.get(1));

            TaktActivated taktActivated = (TaktActivated) sideEffects.get(0);
            assertEquals(1L, taktActivated.workQueueId());
            assertEquals("TAKT100", taktActivated.taktName());
            assertEquals(currentTime, taktActivated.activatedAt());

            ActionActivated activated = (ActionActivated) sideEffects.get(1);
            assertEquals(1L, activated.workQueueId());
            assertEquals("TAKT100", activated.taktName());
            assertEquals("QC lift container from truck", activated.actionDescription());
        }

        @Test
        @DisplayName("Should not activate takt when TimeEvent timestamp < startTime")
        void doesNotActivateBeforeStartTime() {
            List<Takt> takts = createLinkedTakts(1, EMT);
            processor.process(new ScheduleCreated(1L, takts, EMT));

            Instant currentTime = Instant.parse("2024-01-01T09:59:59Z");
            List<SideEffect> sideEffects = processor.process(new TimeEvent(currentTime));

            assertTrue(sideEffects.isEmpty());
        }

        @Test
        @DisplayName("Should activate exactly at startTime")
        void activatesExactlyAtStartTime() {
            List<Takt> takts = createLinkedTakts(1, EMT);
            processor.process(new ScheduleCreated(1L, takts, EMT));

            List<SideEffect> sideEffects = processor.process(new TimeEvent(EMT));

            assertEquals(2, sideEffects.size());
            assertInstanceOf(TaktActivated.class, sideEffects.get(0));
            assertInstanceOf(ActionActivated.class, sideEffects.get(1));
        }

        @Test
        @DisplayName("Should only activate takt once even with multiple TimeEvents")
        void activatesOnlyOnce() {
            List<Takt> takts = createLinkedTakts(1, EMT);
            processor.process(new ScheduleCreated(1L, takts, EMT));

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
            processor.process(new ScheduleCreated(1L, takts, EMT));

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

            processor.process(new ScheduleCreated(1L, takts, EMT));
            processor.process(new TimeEvent(Instant.parse("2024-01-01T10:00:01Z")));

            List<SideEffect> sideEffects = processor.process(
                    new ActionCompletedEvent(firstActionId, 1L));

            // ActionCompleted + ActionActivated (same takt, no takt state change)
            assertEquals(2, sideEffects.size());

            ActionCompleted completed = (ActionCompleted) sideEffects.get(0);
            assertEquals(firstActionId, completed.actionId());
            assertEquals(1L, completed.workQueueId());
            assertEquals("TAKT100", completed.taktName());
            assertEquals("QC lift container from truck", completed.actionDescription());

            ActionActivated activated = (ActionActivated) sideEffects.get(1);
            assertEquals(secondActionId, activated.actionId());
            assertEquals(1L, activated.workQueueId());
            assertEquals("TAKT100", activated.taktName());
            assertEquals("QC place container on vessel", activated.actionDescription());
        }

        @Test
        @DisplayName("Should not complete action when ActionCompletedEvent has wrong action ID")
        void ignoresWrongActionId() {
            List<Takt> takts = createLinkedTakts(1, EMT);
            processor.process(new ScheduleCreated(1L, takts, EMT));
            processor.process(new TimeEvent(Instant.parse("2024-01-01T10:00:01Z")));

            UUID wrongId = UUID.randomUUID();
            List<SideEffect> sideEffects = processor.process(
                    new ActionCompletedEvent(wrongId, 1L));

            assertTrue(sideEffects.isEmpty());
        }

        @Test
        @DisplayName("Should not complete action when work queue doesn't exist")
        void ignoresUnknownWorkQueue() {
            List<Takt> takts = createLinkedTakts(1, EMT);
            UUID firstActionId = takts.get(0).actions().get(0).id();

            processor.process(new ScheduleCreated(1L, takts, EMT));
            processor.process(new TimeEvent(Instant.parse("2024-01-01T10:00:01Z")));

            List<SideEffect> sideEffects = processor.process(
                    new ActionCompletedEvent(firstActionId, 2L));

            assertTrue(sideEffects.isEmpty());
        }
    }

    @Nested
    @DisplayName("Cross-Takt Progression with Takt State Machine")
    class CrossTaktProgressionTests {

        @Test
        @DisplayName("Should complete takt and activate next takt when all actions complete and time is reached")
        void progressesToNextTakt() {
            List<Takt> takts = createLinkedTakts(2, EMT);
            UUID firstTaktAction1 = takts.get(0).actions().get(0).id();
            UUID firstTaktAction2 = takts.get(0).actions().get(1).id();
            UUID secondTaktAction1 = takts.get(1).actions().get(0).id();

            processor.process(new ScheduleCreated(1L, takts, EMT));
            processor.process(new TimeEvent(Instant.parse("2024-01-01T10:00:01Z")));

            // Complete first action (same takt, no takt state change)
            processor.process(new ActionCompletedEvent(firstTaktAction1, 1L));

            // Complete second action (last of first takt) — takt completes
            List<SideEffect> completeEffects = processor.process(
                    new ActionCompletedEvent(firstTaktAction2, 1L));

            // ActionCompleted + TaktCompleted(TAKT100) — TAKT101 not yet time-eligible
            assertEquals(2, completeEffects.size());
            assertInstanceOf(ActionCompleted.class, completeEffects.get(0));
            assertInstanceOf(TaktCompleted.class, completeEffects.get(1));
            assertEquals("TAKT100", ((TaktCompleted) completeEffects.get(1)).taktName());

            // Advance time to TAKT101's start time — now it activates
            List<SideEffect> timeEffects = processor.process(new TimeEvent(EMT.plusSeconds(121)));

            assertTrue(timeEffects.stream().anyMatch(se ->
                    se instanceof TaktActivated ta && ta.taktName().equals("TAKT101")));
            assertTrue(timeEffects.stream().anyMatch(se ->
                    se instanceof ActionActivated aa && aa.actionId().equals(secondTaktAction1)));
        }

        @Test
        @DisplayName("Should produce TaktCompleted when last action of last takt completes")
        void lastTaktCompletes() {
            List<Takt> takts = createLinkedTakts(1, EMT);
            UUID firstActionId = takts.get(0).actions().get(0).id();
            UUID lastActionId = takts.get(0).actions().get(1).id();

            processor.process(new ScheduleCreated(1L, takts, EMT));
            processor.process(new TimeEvent(Instant.parse("2024-01-01T10:00:01Z")));
            processor.process(new ActionCompletedEvent(firstActionId, 1L));

            List<SideEffect> sideEffects = processor.process(
                    new ActionCompletedEvent(lastActionId, 1L));

            // ActionCompleted + TaktCompleted (no next takt to activate)
            assertEquals(2, sideEffects.size());
            assertInstanceOf(ActionCompleted.class, sideEffects.get(0));
            assertInstanceOf(TaktCompleted.class, sideEffects.get(1));

            ActionCompleted completed = (ActionCompleted) sideEffects.get(0);
            assertEquals(lastActionId, completed.actionId());

            TaktCompleted taktCompleted = (TaktCompleted) sideEffects.get(1);
            assertEquals("TAKT100", taktCompleted.taktName());
        }

        @Test
        @DisplayName("Empty takt should be activated and completed immediately on first TimeEvent")
        void emptyTakt_completesImmediately() {
            // Create 3 takts: first with actions, second empty, third with action depending on first
            Action a1 = Action.create(DeviceType.QC, "action 1");
            Action a2 = Action.create(DeviceType.QC, "action 2");
            Instant t0 = EMT;
            Instant t1 = EMT.plusSeconds(120);
            Instant t2 = EMT.plusSeconds(240);
            Takt takt0 = new Takt(0, List.of(a1.withDependencies(Set.of())), t0, t0, 120);
            Takt takt1 = new Takt(1, List.of(), t1, t1, 120);
            Takt takt2 = new Takt(2, List.of(a2.withDependencies(Set.of(a1.id()))), t2, t2, 120);

            processor.process(new ScheduleCreated(1L, List.of(takt0, takt1, takt2), EMT));

            // TimeEvent at t0+1 triggers takt0 activation
            List<SideEffect> tickEffects = processor.process(new TimeEvent(t0.plusSeconds(1)));
            assertTrue(tickEffects.stream().anyMatch(se ->
                            se instanceof TaktActivated ta && ta.taktName().equals("TAKT100")),
                    "TAKT100 should be activated");

            // TimeEvent at t1+1 triggers empty takt1 activation and immediate completion
            List<SideEffect> tick2Effects = processor.process(new TimeEvent(t1.plusSeconds(1)));
            assertTrue(tick2Effects.stream().anyMatch(se ->
                            se instanceof TaktActivated ta && ta.taktName().equals("TAKT101")),
                    "Empty takt TAKT101 should be activated");
            assertTrue(tick2Effects.stream().anyMatch(se ->
                            se instanceof TaktCompleted tc && tc.taktName().equals("TAKT101")),
                    "Empty takt TAKT101 should be completed immediately");

            // Complete a1, then advance time to t2 — takt2 should activate and a2 should activate
            processor.process(new ActionCompletedEvent(a1.id(), 1L));
            List<SideEffect> tick3Effects = processor.process(new TimeEvent(t2.plusSeconds(1)));

            assertTrue(tick3Effects.stream().anyMatch(se ->
                            se instanceof TaktActivated ta && ta.taktName().equals("TAKT102")),
                    "TAKT102 should be activated after time reaches t2");
            assertTrue(tick3Effects.stream().anyMatch(se ->
                            se instanceof ActionActivated aa && aa.actionId().equals(a2.id())),
                    "Action in TAKT102 should be activated");
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

            processor.process(new ScheduleCreated(1L, takts, EMT));
            processor.process(new TimeEvent(Instant.parse("2024-01-01T10:00:01Z")));

            processor.process(new WorkQueueMessage(1L, INACTIVE, 0, null));

            List<SideEffect> sideEffects = processor.process(
                    new ActionCompletedEvent(firstActionId, 1L));
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

            processor.process(new ScheduleCreated(1L, takts, EMT));
            processor.process(new TimeEvent(Instant.parse("2024-01-01T10:00:01Z")));

            Object state = processor.captureState();

            processor.process(new ActionCompletedEvent(firstActionId, 1L));

            processor.restoreState(state);

            List<SideEffect> sideEffects = processor.process(
                    new ActionCompletedEvent(firstActionId, 1L));

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

            processor.process(new ScheduleCreated(1L, takts, EMT));

            // Step 1: TimeEvent activates first takt and first action
            List<SideEffect> step1 = processor.process(new TimeEvent(Instant.parse("2024-01-01T10:00:01Z")));
            assertEquals(2, step1.size());
            assertInstanceOf(TaktActivated.class, step1.get(0));
            ActionActivated activated1 = (ActionActivated) step1.get(1);
            assertEquals(takt1Action1, activated1.actionId());

            // Step 2: Complete first action -> activates second (same takt)
            List<SideEffect> step2 = processor.process(new ActionCompletedEvent(takt1Action1, 1L));
            assertEquals(2, step2.size());
            assertInstanceOf(ActionCompleted.class, step2.get(0));
            assertInstanceOf(ActionActivated.class, step2.get(1));

            // Step 3: Complete second action -> takt1 completed; TAKT101 not yet time-eligible
            List<SideEffect> step3 = processor.process(new ActionCompletedEvent(takt1Action2, 1L));
            assertEquals(2, step3.size());
            assertInstanceOf(ActionCompleted.class, step3.get(0));
            assertInstanceOf(TaktCompleted.class, step3.get(1));
            assertEquals("TAKT100", ((TaktCompleted) step3.get(1)).taktName());

            // Step 3b: Advance time to activate TAKT101
            List<SideEffect> step3b = processor.process(new TimeEvent(EMT.plusSeconds(121)));
            assertTrue(step3b.stream().anyMatch(se ->
                    se instanceof TaktActivated ta && ta.taktName().equals("TAKT101")));

            // Step 4: Complete first action of second takt
            List<SideEffect> step4 = processor.process(new ActionCompletedEvent(takt2Action1, 1L));
            assertEquals(2, step4.size());
            assertInstanceOf(ActionCompleted.class, step4.get(0));
            assertInstanceOf(ActionActivated.class, step4.get(1));

            // Step 5: Complete last action -> takt completed, no more takts
            List<SideEffect> step5 = processor.process(new ActionCompletedEvent(takt2Action2, 1L));
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

            Takt takt = new Takt(0, List.of(action1, action2, action3), EMT, EMT, 120);
            List<Takt> takts = List.of(takt);

            processor.process(new ScheduleCreated(1L, takts, EMT));

            // TimeEvent: TaktActivated + ActionActivated(action1) + ActionActivated(action2)
            List<SideEffect> step1 = processor.process(new TimeEvent(Instant.parse("2024-01-01T10:00:01Z")));
            assertEquals(3, step1.size());
            assertInstanceOf(TaktActivated.class, step1.get(0));

            // Complete only action1 - action3 should NOT activate yet
            List<SideEffect> step2 = processor.process(new ActionCompletedEvent(action1.id(), 1L));
            assertEquals(1, step2.size());
            assertInstanceOf(ActionCompleted.class, step2.get(0));

            // Complete action2 - now action3 should activate
            List<SideEffect> step3 = processor.process(new ActionCompletedEvent(action2.id(), 1L));
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

            Takt takt = new Takt(0, List.of(action1, action2, action3), EMT, EMT, 120);
            List<Takt> takts = List.of(takt);

            processor.process(new ScheduleCreated(1L, takts, EMT));

            // Start schedule - TaktActivated + only action1 should activate
            processor.process(new TimeEvent(Instant.parse("2024-01-01T10:00:01Z")));

            // Complete action1 -> both action2 and action3 should activate
            List<SideEffect> sideEffects = processor.process(new ActionCompletedEvent(action1.id(), 1L));

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

            Instant taktStart = startTime.plusSeconds((long) taktIndex * 120);
            takts.add(new Takt(taktIndex, List.of(linkedAction1, linkedAction2), taktStart, taktStart, 120));
        }

        return takts;
    }
}
