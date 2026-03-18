package com.wonderingwizard.processors;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.ActionStatus;
import com.wonderingwizard.domain.takt.ActionType;
import com.wonderingwizard.domain.takt.CompletionReason;
import com.wonderingwizard.domain.takt.DeviceType;
import com.wonderingwizard.domain.takt.Takt;
import com.wonderingwizard.engine.EventProcessingEngine;
import com.wonderingwizard.engine.EventPropagatingEngine;
import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.ActionCompletedEvent;
import com.wonderingwizard.events.CheJobStepState;
import com.wonderingwizard.events.CheStatus;
import com.wonderingwizard.events.ContainerHandlingEquipmentEvent;
import com.wonderingwizard.events.EventType;
import com.wonderingwizard.events.TimeEvent;
import com.wonderingwizard.events.WorkInstructionEvent;
import com.wonderingwizard.sideeffects.ActionCompleted;
import com.wonderingwizard.sideeffects.ScheduleCreated;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for WI Reverted event handling.
 */
@DisplayName("WI Revert Handler")
class WIRevertHandlerTest {

    private EventProcessingEngine baseEngine;
    private EventPropagatingEngine engine;
    private ScheduleRunnerProcessor scheduleRunner;
    private TTStateProcessor ttState;
    private Instant now;

    private static final long WI_ID_1 = 7001L;
    private static final long WI_ID_2 = 7002L;

    @BeforeEach
    void setUp() {
        baseEngine = new EventProcessingEngine();
        ttState = new TTStateProcessor();
        scheduleRunner = new ScheduleRunnerProcessor();
        scheduleRunner.registerTTAllocationStrategy(ttState);
        scheduleRunner.registerSubProcessor(new WIRevertHandler());
        baseEngine.register(ttState);
        baseEngine.register(scheduleRunner);
        engine = new EventPropagatingEngine(baseEngine);
        now = Instant.parse("2025-01-01T12:00:00Z");
        engine.processEvent(new TimeEvent(now));
    }

    private ContainerHandlingEquipmentEvent workingTruck(String name) {
        return new ContainerHandlingEquipmentEvent(
                "CHE Status Changed", 100L, "U", "TERM1", 1L,
                name, CheStatus.WORKING, "TT", 23L, CheJobStepState.IDLE, 0L);
    }

    private WorkInstructionEvent wiRevertEvent(long workInstructionId) {
        return new WorkInstructionEvent(
                EventType.WI_REVERTED, workInstructionId, 1L, "QC01", "Planned",
                now, 60, 60, "", false, false, false, 0, "", "");
    }

    private ScheduleCreated scheduleWithTTWorkflow(long workQueueId, int containerIndex, long workInstructionId) {
        WorkInstructionEvent wi = new WorkInstructionEvent(
                workInstructionId, workQueueId, "QC01", "Planned",
                now, 60, 60);

        Action drive = new Action(UUID.randomUUID(), DeviceType.TT, ActionType.TT_DRIVE_TO_QC_PULL,
                "drive to QC pull", Set.of(), containerIndex, 30, 0, List.of(wi), List.of());
        Action standby = new Action(UUID.randomUUID(), DeviceType.TT, ActionType.TT_DRIVE_TO_QC_STANDBY,
                "drive to QC standby", Set.of(drive.id()), containerIndex, 20);
        Action underQC = new Action(UUID.randomUUID(), DeviceType.TT, ActionType.TT_DRIVE_UNDER_QC,
                "drive under QC", Set.of(standby.id()), containerIndex, 15);
        Action handover = new Action(UUID.randomUUID(), DeviceType.TT, ActionType.TT_HANDOVER_FROM_QC,
                "handover from QC", Set.of(underQC.id()), containerIndex, 20);
        Action qcLift = new Action(UUID.randomUUID(), DeviceType.QC, ActionType.QC_LIFT,
                "QC Lift", Set.of(handover.id()), containerIndex, 30);

        Takt takt = new Takt(0, List.of(drive, standby, underQC, handover, qcLift), now, now, 300);
        return new ScheduleCreated(workQueueId, List.of(takt), now);
    }

    private ScheduleCreated twinScheduleWithTTWorkflow(long workQueueId) {
        WorkInstructionEvent wi0 = new WorkInstructionEvent(
                WI_ID_1, workQueueId, "QC01", "Planned",
                now, 60, 60);
        WorkInstructionEvent wi1 = new WorkInstructionEvent(
                WI_ID_2, workQueueId, "QC01", "Planned",
                now, 60, 60);

        Action drive0 = new Action(UUID.randomUUID(), DeviceType.TT, ActionType.TT_DRIVE_TO_QC_PULL,
                "drive to QC pull", Set.of(), 0, 30, 0, List.of(wi0), List.of());
        Action standby0 = new Action(UUID.randomUUID(), DeviceType.TT, ActionType.TT_DRIVE_TO_QC_STANDBY,
                "drive to QC standby", Set.of(drive0.id()), 0, 20);
        Action underQC0 = new Action(UUID.randomUUID(), DeviceType.TT, ActionType.TT_DRIVE_UNDER_QC,
                "drive under QC", Set.of(standby0.id()), 0, 15);
        Action handover0 = new Action(UUID.randomUUID(), DeviceType.TT, ActionType.TT_HANDOVER_FROM_QC,
                "handover from QC", Set.of(underQC0.id()), 0, 20);
        Action qcLift0 = new Action(UUID.randomUUID(), DeviceType.QC, ActionType.QC_LIFT,
                "QC Lift1", Set.of(handover0.id()), 0, 30);

        Action drive1 = new Action(UUID.randomUUID(), DeviceType.TT, ActionType.TT_DRIVE_TO_QC_PULL,
                "drive to QC pull", Set.of(), 1, 30, 0, List.of(wi1), List.of());
        Action standby1 = new Action(UUID.randomUUID(), DeviceType.TT, ActionType.TT_DRIVE_TO_QC_STANDBY,
                "drive to QC standby", Set.of(drive1.id()), 1, 20);
        Action underQC1 = new Action(UUID.randomUUID(), DeviceType.TT, ActionType.TT_DRIVE_UNDER_QC,
                "drive under QC", Set.of(standby1.id()), 1, 15);
        Action handover1 = new Action(UUID.randomUUID(), DeviceType.TT, ActionType.TT_HANDOVER_FROM_QC,
                "handover from QC", Set.of(underQC1.id()), 1, 20);
        Action qcLift1 = new Action(UUID.randomUUID(), DeviceType.QC, ActionType.QC_LIFT,
                "QC Lift2", Set.of(handover1.id()), 1, 30);

        Takt takt = new Takt(0, List.of(
                drive0, standby0, underQC0, handover0, qcLift0,
                drive1, standby1, underQC1, handover1, qcLift1
        ), now, now, 600);
        return new ScheduleCreated(workQueueId, List.of(takt), now);
    }

    @Nested
    @DisplayName("Before TT_DRIVE_UNDER_QC completed")
    class BeforeDriveUnderQCCompleted {

        @Test
        @DisplayName("Should cancel all actions when first action is active")
        void cancelAtFirstAction() {
            engine.processEvent(workingTruck("TT01"));
            ScheduleCreated schedule = scheduleWithTTWorkflow(1L, 0, WI_ID_1);
            engine.processEvent(schedule);
            engine.processEvent(new TimeEvent(now));

            List<SideEffect> effects = engine.processEvent(wiRevertEvent(WI_ID_1));

            List<ActionCompleted> completedEffects = effects.stream()
                    .filter(e -> e instanceof ActionCompleted)
                    .map(e -> (ActionCompleted) e)
                    .toList();
            assertFalse(completedEffects.isEmpty(), "Should have completed actions");
            assertTrue(completedEffects.stream().allMatch(ac ->
                    ac.reason() == CompletionReason.WI_REVERTED),
                    "All completed actions should have WI_REVERTED reason");
        }

        @Test
        @DisplayName("Should cancel all actions when TT_DRIVE_UNDER_QC is active (not yet completed)")
        void cancelWhenDriveUnderQCActive() {
            engine.processEvent(workingTruck("TT01"));
            ScheduleCreated schedule = scheduleWithTTWorkflow(1L, 0, WI_ID_1);
            engine.processEvent(schedule);
            engine.processEvent(new TimeEvent(now));

            completeFirstActiveAction(1L); // TT_DRIVE_TO_QC_PULL
            completeFirstActiveAction(1L); // TT_DRIVE_TO_QC_STANDBY
            // TT_DRIVE_UNDER_QC is now ACTIVE

            UUID underQCId = findActionByType(schedule, ActionType.TT_DRIVE_UNDER_QC);
            assertEquals(ActionStatus.ACTIVE,
                    scheduleRunner.getActionStatus(1L, underQCId),
                    "TT_DRIVE_UNDER_QC should be ACTIVE");

            List<SideEffect> effects = engine.processEvent(wiRevertEvent(WI_ID_1));

            List<ActionCompleted> completedEffects = effects.stream()
                    .filter(e -> e instanceof ActionCompleted)
                    .map(e -> (ActionCompleted) e)
                    .toList();
            assertFalse(completedEffects.isEmpty(), "Should have completed actions");
            assertTrue(completedEffects.stream().allMatch(ac ->
                    ac.reason() == CompletionReason.WI_REVERTED),
                    "All completed actions should have WI_REVERTED reason");
        }

        @Test
        @DisplayName("Should cancel actions for twin container too")
        void cancelTwinContainerActions() {
            engine.processEvent(workingTruck("TT01"));
            ScheduleCreated schedule = twinScheduleWithTTWorkflow(1L);
            engine.processEvent(schedule);
            engine.processEvent(new TimeEvent(now));

            List<SideEffect> effects = engine.processEvent(wiRevertEvent(WI_ID_1));

            List<ActionCompleted> completedEffects = effects.stream()
                    .filter(e -> e instanceof ActionCompleted)
                    .map(e -> (ActionCompleted) e)
                    .toList();
            assertFalse(completedEffects.isEmpty(),
                    "Should have completed actions for both containers");
            assertTrue(completedEffects.stream().allMatch(ac ->
                    ac.reason() == CompletionReason.WI_REVERTED),
                    "All completed actions should have WI_REVERTED reason");
        }

        @Test
        @DisplayName("Should set completion reason on the Action record")
        void completionReasonOnAction() {
            engine.processEvent(workingTruck("TT01"));
            ScheduleCreated schedule = scheduleWithTTWorkflow(1L, 0, WI_ID_1);
            engine.processEvent(schedule);
            engine.processEvent(new TimeEvent(now));

            engine.processEvent(wiRevertEvent(WI_ID_1));

            boolean foundReasonedAction = false;
            for (Action action : schedule.takts().getFirst().actions()) {
                Action current = scheduleRunner.getAction(1L, action.id());
                if (current != null && current.completionReason() != null) {
                    assertEquals(CompletionReason.WI_REVERTED, current.completionReason());
                    foundReasonedAction = true;
                }
            }
            assertTrue(foundReasonedAction, "At least one action should have completionReason set");
        }
    }

    @Nested
    @DisplayName("After TT_DRIVE_UNDER_QC completed")
    class AfterDriveUnderQCCompleted {

        @Test
        @DisplayName("Should ignore WI Reverted when TT_DRIVE_UNDER_QC is completed")
        void ignoreAfterDriveUnderQCCompleted() {
            engine.processEvent(workingTruck("TT01"));
            ScheduleCreated schedule = scheduleWithTTWorkflow(1L, 0, WI_ID_1);
            engine.processEvent(schedule);
            engine.processEvent(new TimeEvent(now));

            completeFirstActiveAction(1L); // TT_DRIVE_TO_QC_PULL
            completeFirstActiveAction(1L); // TT_DRIVE_TO_QC_STANDBY
            completeFirstActiveAction(1L); // TT_DRIVE_UNDER_QC — now completed

            UUID underQCId = findActionByType(schedule, ActionType.TT_DRIVE_UNDER_QC);
            assertEquals(ActionStatus.COMPLETED,
                    scheduleRunner.getActionStatus(1L, underQCId),
                    "TT_DRIVE_UNDER_QC should be COMPLETED");

            List<SideEffect> effects = engine.processEvent(wiRevertEvent(WI_ID_1));

            assertFalse(effects.stream().anyMatch(e ->
                    e instanceof ActionCompleted ac && ac.reason() == CompletionReason.WI_REVERTED),
                    "Should NOT produce WI_REVERTED completions after boundary");
        }

        @Test
        @DisplayName("Should ignore WI Reverted when TT_HANDOVER_FROM_QC is active")
        void ignoreWhenHandoverActive() {
            engine.processEvent(workingTruck("TT01"));
            ScheduleCreated schedule = scheduleWithTTWorkflow(1L, 0, WI_ID_1);
            engine.processEvent(schedule);
            engine.processEvent(new TimeEvent(now));

            completeFirstActiveAction(1L); // TT_DRIVE_TO_QC_PULL
            completeFirstActiveAction(1L); // TT_DRIVE_TO_QC_STANDBY
            completeFirstActiveAction(1L); // TT_DRIVE_UNDER_QC

            UUID handoverId = findActionByType(schedule, ActionType.TT_HANDOVER_FROM_QC);
            assertEquals(ActionStatus.ACTIVE,
                    scheduleRunner.getActionStatus(1L, handoverId),
                    "TT_HANDOVER_FROM_QC should be ACTIVE");

            List<SideEffect> effects = engine.processEvent(wiRevertEvent(WI_ID_1));

            assertFalse(effects.stream().anyMatch(e ->
                    e instanceof ActionCompleted ac && ac.reason() == CompletionReason.WI_REVERTED),
                    "Should NOT produce WI_REVERTED completions after boundary");
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Non-WI-Reverted event types should be ignored")
        void nonWIRevertedIgnored() {
            engine.processEvent(workingTruck("TT01"));
            engine.processEvent(scheduleWithTTWorkflow(1L, 0, WI_ID_1));
            engine.processEvent(new TimeEvent(now));

            WorkInstructionEvent wiCreated = new WorkInstructionEvent(
                    EventType.WI_CREATED, WI_ID_1, 1L, "QC01", "Planned",
                    now, 60, 60, "", false, false, false, 0, "", "");
            List<SideEffect> effects = engine.processEvent(wiCreated);

            assertFalse(effects.stream().anyMatch(e ->
                    e instanceof ActionCompleted ac && ac.reason() == CompletionReason.WI_REVERTED),
                    "Non-WI-Reverted should not produce WI_REVERTED completions");
        }

        @Test
        @DisplayName("Unknown workInstructionId should be ignored")
        void unknownWorkInstructionIdIgnored() {
            engine.processEvent(workingTruck("TT01"));
            engine.processEvent(scheduleWithTTWorkflow(1L, 0, WI_ID_1));
            engine.processEvent(new TimeEvent(now));

            List<SideEffect> effects = engine.processEvent(wiRevertEvent(9999L));

            assertFalse(effects.stream().anyMatch(e ->
                    e instanceof ActionCompleted ac && ac.reason() != null),
                    "Unknown WI ID should not produce force-completed actions");
        }

        @Test
        @DisplayName("WI Reverted for WQ without schedule should be ignored")
        void wiRevertWithoutScheduleIgnored() {
            List<SideEffect> effects = engine.processEvent(wiRevertEvent(WI_ID_1));

            assertTrue(effects.isEmpty() || effects.stream().noneMatch(e ->
                    e instanceof ActionCompleted ac && ac.reason() != null),
                    "WI Reverted without schedule should produce no force-completed actions");
        }
    }

    private void completeFirstActiveAction(long workQueueId) {
        var takts = scheduleRunner.getScheduleTakts(workQueueId);
        for (var takt : takts) {
            for (var action : takt.actions()) {
                if (scheduleRunner.getActionStatus(workQueueId, action.id())
                        == ActionStatus.ACTIVE) {
                    engine.processEvent(new ActionCompletedEvent(action.id(), workQueueId));
                    return;
                }
            }
        }
    }

    private UUID findActionByType(ScheduleCreated schedule, ActionType actionType) {
        for (var takt : schedule.takts()) {
            for (var action : takt.actions()) {
                if (action.actionType() == actionType) {
                    return action.id();
                }
            }
        }
        throw new IllegalStateException("Action type not found: " + actionType);
    }
}
