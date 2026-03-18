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
import com.wonderingwizard.sideeffects.ActionActivated;
import com.wonderingwizard.sideeffects.ActionCompleted;
import com.wonderingwizard.sideeffects.ScheduleCreated;
import com.wonderingwizard.sideeffects.TruckAssigned;
import com.wonderingwizard.sideeffects.TruckUnassigned;
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
 * Tests for WI Abandoned event handling.
 */
@DisplayName("WI Abandoned Handler")
class WIAbandonedHandlerTest {

    private EventProcessingEngine baseEngine;
    private EventPropagatingEngine engine;
    private ScheduleRunnerProcessor scheduleRunner;
    private TTStateProcessor ttState;
    private Instant now;

    @BeforeEach
    void setUp() {
        baseEngine = new EventProcessingEngine();
        ttState = new TTStateProcessor();
        scheduleRunner = new ScheduleRunnerProcessor();
        scheduleRunner.registerTTAllocationStrategy(ttState);
        scheduleRunner.registerSubProcessor(new TTUnavailableHandler());
        scheduleRunner.registerSubProcessor(new WIAbandonedHandler());
        baseEngine.register(ttState);
        baseEngine.register(scheduleRunner);
        engine = new EventPropagatingEngine(baseEngine);
        now = Instant.parse("2025-01-01T12:00:00Z");
        engine.processEvent(new TimeEvent(now));
    }

    private static final long WI_ID_1 = 5001L;
    private static final long WI_ID_2 = 5002L;

    private ContainerHandlingEquipmentEvent workingTruck(String name) {
        return new ContainerHandlingEquipmentEvent(
                "CHE Status Changed", 100L, "U", "TERM1", 1L,
                name, CheStatus.WORKING, "TT", 23L, CheJobStepState.IDLE, 0L);
    }

    private WorkInstructionEvent wiEvent(long workInstructionId) {
        return new WorkInstructionEvent(
                EventType.WI_ABANDONED, workInstructionId, 1L, "QC01", "Planned",
                now, 60, 60, "", false, false, false, 0, "", "");
    }

    /**
     * Creates a schedule with a full TT workflow for a single container,
     * where actions have work instructions with the given workInstructionId.
     */
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

    /**
     * Creates a twin schedule with TT workflow for two containers sharing the same truck.
     */
    private ScheduleCreated twinScheduleWithTTWorkflow(long workQueueId) {
        WorkInstructionEvent wi0 = new WorkInstructionEvent(
                WI_ID_1, workQueueId, "QC01", "Planned",
                now, 60, 60);
        WorkInstructionEvent wi1 = new WorkInstructionEvent(
                WI_ID_2, workQueueId, "QC01", "Planned",
                now, 60, 60);

        // Container 0
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

        // Container 1
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
    @DisplayName("Before TT handover from QC activated")
    class BeforeTTHandoverFromQC {

        @Test
        @DisplayName("Should reset TT actions and allocate new truck")
        void resetTTActionsAndAllocateNewTruck() {
            // Arrange: two trucks available
            engine.processEvent(workingTruck("TT01"));
            engine.processEvent(workingTruck("TT02"));
            ScheduleCreated schedule = scheduleWithTTWorkflow(1L, 0, WI_ID_1);
            engine.processEvent(schedule);
            engine.processEvent(new TimeEvent(now));

            // Act: WI Abandoned before TT_HANDOVER_FROM_QC
            List<SideEffect> effects = engine.processEvent(wiEvent(WI_ID_1));

            // Assert: TruckUnassigned should be produced, then new truck assigned
            assertTrue(effects.stream().anyMatch(e -> e instanceof TruckUnassigned),
                    "Should produce TruckUnassigned side effects");
            assertTrue(effects.stream().anyMatch(e -> e instanceof TruckAssigned),
                    "Should allocate a new truck");
            assertFalse(effects.stream().anyMatch(e ->
                    e instanceof ActionCompleted ac && ac.reason() != null),
                    "Should NOT produce force-completed actions");
        }
    }

    @Nested
    @DisplayName("After TT handover from QC activated")
    class AfterTTHandoverFromQC {

        @Test
        @DisplayName("Should complete remaining actions with WI_ABANDONED reason")
        void completeRemainingActions() {
            // Arrange: advance to TT_HANDOVER_FROM_QC active
            engine.processEvent(workingTruck("TT01"));
            ScheduleCreated schedule = scheduleWithTTWorkflow(1L, 0, WI_ID_1);
            engine.processEvent(schedule);
            engine.processEvent(new TimeEvent(now));

            completeFirstActiveAction(1L); // TT_DRIVE_TO_QC_PULL
            completeFirstActiveAction(1L); // TT_DRIVE_TO_QC_STANDBY
            completeFirstActiveAction(1L); // TT_DRIVE_UNDER_QC
            // Now TT_HANDOVER_FROM_QC should be active

            UUID handoverId = findActionByType(schedule, ActionType.TT_HANDOVER_FROM_QC);
            assertEquals(ActionStatus.ACTIVE,
                    scheduleRunner.getActionStatus(1L, handoverId),
                    "TT_HANDOVER_FROM_QC should be ACTIVE");

            // Act: WI Abandoned
            List<SideEffect> effects = engine.processEvent(wiEvent(WI_ID_1));

            // Assert: remaining actions should be completed with reason
            List<ActionCompleted> completedEffects = effects.stream()
                    .filter(e -> e instanceof ActionCompleted)
                    .map(e -> (ActionCompleted) e)
                    .toList();
            assertFalse(completedEffects.isEmpty(), "Should have completed actions");
            assertTrue(completedEffects.stream().allMatch(ac ->
                    ac.reason() == CompletionReason.WI_ABANDONED),
                    "All completed actions should have WI_ABANDONED reason");
        }

        @Test
        @DisplayName("Should complete actions for twin container too")
        void completeTwinContainerActions() {
            // Arrange: truck assigned to twin schedule, advance to TT_HANDOVER_FROM_QC
            engine.processEvent(workingTruck("TT01"));
            ScheduleCreated schedule = twinScheduleWithTTWorkflow(1L);
            engine.processEvent(schedule);
            engine.processEvent(new TimeEvent(now));

            // Complete through to TT_HANDOVER_FROM_QC for one container.
            // Both containers' first TT actions are activated simultaneously.
            completeFirstActiveAction(1L); // container 0: TT_DRIVE_TO_QC_PULL
            completeFirstActiveAction(1L); // container 1: TT_DRIVE_TO_QC_PULL
            completeFirstActiveAction(1L); // container 0: TT_DRIVE_TO_QC_STANDBY
            completeFirstActiveAction(1L); // container 1: TT_DRIVE_TO_QC_STANDBY
            completeFirstActiveAction(1L); // container 0: TT_DRIVE_UNDER_QC
            // Now one container's TT_HANDOVER_FROM_QC should be active

            // Determine which container reached TT_HANDOVER_FROM_QC
            UUID handover0 = findActionByTypeAndContainer(schedule, ActionType.TT_HANDOVER_FROM_QC, 0);
            UUID handover1 = findActionByTypeAndContainer(schedule, ActionType.TT_HANDOVER_FROM_QC, 1);
            boolean container0Handover = scheduleRunner.getActionStatus(1L, handover0) == ActionStatus.ACTIVE
                    || scheduleRunner.getActionStatus(1L, handover0) == ActionStatus.COMPLETED;

            // Send WI Abandoned for the container that reached handover
            long wiId = container0Handover ? WI_ID_1 : WI_ID_2;
            List<SideEffect> effects = engine.processEvent(wiEvent(wiId));

            // Assert: actions for BOTH containers should be completed
            List<ActionCompleted> completedEffects = effects.stream()
                    .filter(e -> e instanceof ActionCompleted)
                    .map(e -> (ActionCompleted) e)
                    .toList();
            assertFalse(completedEffects.isEmpty(),
                    "Should have completed actions for both containers");
            assertTrue(completedEffects.stream().allMatch(ac ->
                    ac.reason() == CompletionReason.WI_ABANDONED),
                    "All completed actions should have WI_ABANDONED reason");
        }

        @Test
        @DisplayName("Should set completion reason on the Action record")
        void completionReasonOnAction() {
            // Arrange: advance to TT_HANDOVER_FROM_QC active
            engine.processEvent(workingTruck("TT01"));
            ScheduleCreated schedule = scheduleWithTTWorkflow(1L, 0, WI_ID_1);
            engine.processEvent(schedule);
            engine.processEvent(new TimeEvent(now));
            completeFirstActiveAction(1L); // TT_DRIVE_TO_QC_PULL
            completeFirstActiveAction(1L); // TT_DRIVE_TO_QC_STANDBY
            completeFirstActiveAction(1L); // TT_DRIVE_UNDER_QC

            // Act
            engine.processEvent(wiEvent(WI_ID_1));

            // Assert: check that force-completed actions have completionReason set
            boolean foundReasonedAction = false;
            for (Action action : schedule.takts().getFirst().actions()) {
                Action current = scheduleRunner.getAction(1L, action.id());
                if (current != null && current.completionReason() != null) {
                    assertEquals(CompletionReason.WI_ABANDONED, current.completionReason());
                    foundReasonedAction = true;
                }
            }
            assertTrue(foundReasonedAction, "At least one action should have completionReason set");
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Non-WI-Abandoned event types should be ignored")
        void nonWIAbandonedIgnored() {
            engine.processEvent(workingTruck("TT01"));
            ScheduleCreated schedule = scheduleWithTTWorkflow(1L, 0, WI_ID_1);
            engine.processEvent(schedule);
            engine.processEvent(new TimeEvent(now));

            // Send a WI Created event (not WI Abandoned)
            WorkInstructionEvent wiCreated = new WorkInstructionEvent(
                    EventType.WI_CREATED, WI_ID_1, 1L, "QC01", "Planned",
                    now, 60, 60, "", false, false, false, 0, "", "");
            List<SideEffect> effects = engine.processEvent(wiCreated);

            assertFalse(effects.stream().anyMatch(e -> e instanceof TruckUnassigned),
                    "Non-WI-Abandoned should not produce TruckUnassigned");
            assertFalse(effects.stream().anyMatch(e ->
                    e instanceof ActionCompleted ac && ac.reason() == CompletionReason.WI_ABANDONED),
                    "Non-WI-Abandoned should not produce WI_ABANDONED completions");
        }

        @Test
        @DisplayName("Unknown workInstructionId should be ignored")
        void unknownWorkInstructionIdIgnored() {
            engine.processEvent(workingTruck("TT01"));
            ScheduleCreated schedule = scheduleWithTTWorkflow(1L, 0, WI_ID_1);
            engine.processEvent(schedule);
            engine.processEvent(new TimeEvent(now));

            // Send WI Abandoned for an unknown work instruction ID
            List<SideEffect> effects = engine.processEvent(wiEvent(9999L));

            assertFalse(effects.stream().anyMatch(e -> e instanceof TruckUnassigned),
                    "Unknown WI ID should not produce TruckUnassigned");
            assertFalse(effects.stream().anyMatch(e ->
                    e instanceof ActionCompleted ac && ac.reason() != null),
                    "Unknown WI ID should not produce force-completed actions");
        }

        @Test
        @DisplayName("WI Abandoned for WQ without schedule should be ignored")
        void wiAbandonedWithoutScheduleIgnored() {
            // Don't create any schedule, just send WI Abandoned
            List<SideEffect> effects = engine.processEvent(wiEvent(WI_ID_1));

            assertTrue(effects.isEmpty() || effects.stream().noneMatch(e ->
                    e instanceof ActionCompleted ac && ac.reason() != null),
                    "WI Abandoned without schedule should produce no force-completed actions");
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

    private UUID findActionByTypeAndContainer(ScheduleCreated schedule, ActionType actionType, int containerIndex) {
        for (var takt : schedule.takts()) {
            for (var action : takt.actions()) {
                if (action.actionType() == actionType && action.containerIndex() == containerIndex) {
                    return action.id();
                }
            }
        }
        throw new IllegalStateException("Action type " + actionType + " not found for container " + containerIndex);
    }

    private void completeActionByTypeAndContainer(ScheduleCreated schedule, long workQueueId,
                                                   ActionType actionType, int containerIndex) {
        UUID actionId = findActionByTypeAndContainer(schedule, actionType, containerIndex);
        assertEquals(ActionStatus.ACTIVE, scheduleRunner.getActionStatus(workQueueId, actionId),
                actionType + " for container " + containerIndex + " should be ACTIVE before completing");
        engine.processEvent(new ActionCompletedEvent(actionId, workQueueId));
    }
}
