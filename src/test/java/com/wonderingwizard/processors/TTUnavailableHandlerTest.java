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
import com.wonderingwizard.events.TimeEvent;
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
 * Tests for TT Unavailable event handling.
 */
@DisplayName("TT Unavailable Handler")
class TTUnavailableHandlerTest {

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

    private ContainerHandlingEquipmentEvent unavailableTruck(String name) {
        return new ContainerHandlingEquipmentEvent(
                "CHE Status Changed", 101L, "U", "TERM1", 1L,
                name, CheStatus.UNAVAILABLE, "TT", 23L, CheJobStepState.IDLE, 0L);
    }

    /**
     * Creates a schedule with a full TT workflow for a single container:
     * TT_DRIVE_TO_QC_PULL -> TT_DRIVE_TO_QC_STANDBY -> TT_DRIVE_UNDER_QC -> TT_HANDOVER_FROM_QC
     */
    private ScheduleCreated scheduleWithTTWorkflow(long workQueueId, int containerIndex) {
        Action drive = Action.create(DeviceType.TT, ActionType.TT_DRIVE_TO_QC_PULL, containerIndex, 30);
        Action standby = new Action(UUID.randomUUID(), DeviceType.TT, ActionType.TT_DRIVE_TO_QC_STANDBY,
                "drive to QC standby", Set.of(drive.id()), containerIndex, 20);
        Action underQC = new Action(UUID.randomUUID(), DeviceType.TT, ActionType.TT_DRIVE_UNDER_QC,
                "drive under QC", Set.of(standby.id()), containerIndex, 15);
        Action handover = new Action(UUID.randomUUID(), DeviceType.TT, ActionType.TT_HANDOVER_FROM_QC,
                "handover to QC", Set.of(underQC.id()), containerIndex, 20);
        // Add a QC action that depends on TT handover
        Action qcLift = new Action(UUID.randomUUID(), DeviceType.QC, ActionType.QC_LIFT,
                "QC Lift", Set.of(handover.id()), containerIndex, 30);

        Takt takt = new Takt(0, List.of(drive, standby, underQC, handover, qcLift), now, now, 300);
        return new ScheduleCreated(workQueueId, List.of(takt), now);
    }

    /**
     * Creates a twin schedule with TT workflow for two containers sharing the same truck.
     */
    private ScheduleCreated twinScheduleWithTTWorkflow(long workQueueId) {
        // Container 0
        Action drive0 = Action.create(DeviceType.TT, ActionType.TT_DRIVE_TO_QC_PULL, 0, 30);
        Action standby0 = new Action(UUID.randomUUID(), DeviceType.TT, ActionType.TT_DRIVE_TO_QC_STANDBY,
                "drive to QC standby", Set.of(drive0.id()), 0, 20);
        Action underQC0 = new Action(UUID.randomUUID(), DeviceType.TT, ActionType.TT_DRIVE_UNDER_QC,
                "drive under QC", Set.of(standby0.id()), 0, 15);
        Action handover0 = new Action(UUID.randomUUID(), DeviceType.TT, ActionType.TT_HANDOVER_FROM_QC,
                "handover to QC", Set.of(underQC0.id()), 0, 20);
        Action qcLift0 = new Action(UUID.randomUUID(), DeviceType.QC, ActionType.QC_LIFT,
                "QC Lift1", Set.of(handover0.id()), 0, 30);

        // Container 1
        Action drive1 = Action.create(DeviceType.TT, ActionType.TT_DRIVE_TO_QC_PULL, 1, 30);
        Action standby1 = new Action(UUID.randomUUID(), DeviceType.TT, ActionType.TT_DRIVE_TO_QC_STANDBY,
                "drive to QC standby", Set.of(drive1.id()), 1, 20);
        Action underQC1 = new Action(UUID.randomUUID(), DeviceType.TT, ActionType.TT_DRIVE_UNDER_QC,
                "drive under QC", Set.of(standby1.id()), 1, 15);
        Action handover1 = new Action(UUID.randomUUID(), DeviceType.TT, ActionType.TT_HANDOVER_FROM_QC,
                "handover to QC", Set.of(underQC1.id()), 1, 20);
        Action qcLift1 = new Action(UUID.randomUUID(), DeviceType.QC, ActionType.QC_LIFT,
                "QC Lift2", Set.of(handover1.id()), 1, 30);

        Takt takt = new Takt(0, List.of(
                drive0, standby0, underQC0, handover0, qcLift0,
                drive1, standby1, underQC1, handover1, qcLift1
        ), now, now, 600);
        return new ScheduleCreated(workQueueId, List.of(takt), now);
    }

    @Nested
    @DisplayName("Before TT handover to QC activated")
    class BeforeTTHandoverToQC {

        @Test
        @DisplayName("Should reset TT actions to pending and unassign truck")
        void resetTTActions() {
            // Arrange: truck is working and assigned
            engine.processEvent(workingTruck("TT01"));
            ScheduleCreated schedule = scheduleWithTTWorkflow(1L, 0);
            engine.processEvent(schedule);

            // Verify truck was assigned and first action activated
            assertTrue(engine.processEvent(new TimeEvent(now)).stream()
                    .anyMatch(e -> e instanceof TruckAssigned ta && "TT01".equals(ta.cheShortName()))
                    || hasTruckAssigned("TT01"),
                    "TT01 should be assigned");

            // Act: truck becomes unavailable before TT_HANDOVER_FROM_QC is activated
            List<SideEffect> effects = engine.processEvent(unavailableTruck("TT01"));

            // Assert: TruckUnassigned side effects should be produced
            assertTrue(effects.stream().anyMatch(e -> e instanceof TruckUnassigned tu
                    && "TT01".equals(tu.cheShortName())),
                    "Should produce TruckUnassigned side effects");
        }

        @Test
        @DisplayName("Should allocate new truck after reset")
        void allocateNewTruck() {
            // Arrange: two trucks available
            engine.processEvent(workingTruck("TT01"));
            engine.processEvent(workingTruck("TT02"));
            ScheduleCreated schedule = scheduleWithTTWorkflow(1L, 0);
            engine.processEvent(schedule);
            // Force time tick to allocate TT01
            engine.processEvent(new TimeEvent(now));

            // Act: TT01 becomes unavailable
            List<SideEffect> effects = engine.processEvent(unavailableTruck("TT01"));

            // Assert: TT02 should be assigned as replacement
            boolean hasTT02Assigned = effects.stream().anyMatch(e ->
                    e instanceof TruckAssigned ta && "TT02".equals(ta.cheShortName()));
            assertTrue(hasTT02Assigned, "TT02 should be assigned as replacement");
        }

        @Test
        @DisplayName("Should still reset when TT_DRIVE_UNDER_QC is active")
        void resetWhenDriveUnderQCActive() {
            // Arrange: advance to TT_DRIVE_UNDER_QC active
            engine.processEvent(workingTruck("TT01"));
            engine.processEvent(workingTruck("TT02"));
            ScheduleCreated schedule = scheduleWithTTWorkflow(1L, 0);
            engine.processEvent(schedule);
            engine.processEvent(new TimeEvent(now));
            completeFirstActiveAction(1L); // TT_DRIVE_TO_QC_PULL
            completeFirstActiveAction(1L); // TT_DRIVE_TO_QC_STANDBY
            // Now TT_DRIVE_UNDER_QC should be active

            UUID underQCId = findActionByType(schedule, ActionType.TT_DRIVE_UNDER_QC);
            assertEquals(ActionStatus.ACTIVE,
                    scheduleRunner.getActionStatus(1L, underQCId),
                    "TT_DRIVE_UNDER_QC should be ACTIVE");

            // Act: truck becomes unavailable while driving under QC
            List<SideEffect> effects = engine.processEvent(unavailableTruck("TT01"));

            // Assert: should reset (not cancel) — TT02 should be assigned
            assertTrue(effects.stream().anyMatch(e -> e instanceof TruckUnassigned tu
                    && "TT01".equals(tu.cheShortName())),
                    "Should produce TruckUnassigned");
            assertTrue(effects.stream().anyMatch(e ->
                    e instanceof TruckAssigned ta && "TT02".equals(ta.cheShortName())),
                    "TT02 should be assigned as replacement");
            assertFalse(effects.stream().anyMatch(e ->
                    e instanceof ActionCompleted ac && ac.reason() != null),
                    "Should NOT produce force-completed actions");
        }
    }

    @Nested
    @DisplayName("After TT handover to QC activated")
    class AfterTTHandoverToQC {

        @Test
        @DisplayName("Should complete remaining actions with TT_UNAVAILABLE reason")
        void completeRemainingActions() {
            // Arrange: truck assigned, advance through actions to TT_HANDOVER_FROM_QC active
            engine.processEvent(workingTruck("TT01"));
            ScheduleCreated schedule = scheduleWithTTWorkflow(1L, 0);
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

            // Act: truck becomes unavailable while TT_HANDOVER_FROM_QC is active
            List<SideEffect> effects = engine.processEvent(unavailableTruck("TT01"));

            // Assert: remaining actions should be completed with reason
            List<ActionCompleted> completedEffects = effects.stream()
                    .filter(e -> e instanceof ActionCompleted)
                    .map(e -> (ActionCompleted) e)
                    .toList();
            assertFalse(completedEffects.isEmpty(), "Should have completed actions");
            assertTrue(completedEffects.stream().allMatch(ac ->
                    ac.reason() == CompletionReason.TT_UNAVAILABLE),
                    "All completed actions should have TT_UNAVAILABLE reason");
        }

        @Test
        @DisplayName("Should complete actions for twin container too")
        void completeTwinContainerActions() {
            // Arrange: truck assigned to twin schedule, advance to TT_HANDOVER_FROM_QC
            engine.processEvent(workingTruck("TT01"));
            ScheduleCreated schedule = twinScheduleWithTTWorkflow(1L);
            engine.processEvent(schedule);
            engine.processEvent(new TimeEvent(now));

            // Both containers' first TT actions activated simultaneously.
            // Complete through to TT_HANDOVER_FROM_QC for one container.
            completeFirstActiveAction(1L); // container 0: TT_DRIVE_TO_QC_PULL
            completeFirstActiveAction(1L); // container 1: TT_DRIVE_TO_QC_PULL
            completeFirstActiveAction(1L); // container 0: TT_DRIVE_TO_QC_STANDBY
            completeFirstActiveAction(1L); // container 1: TT_DRIVE_TO_QC_STANDBY
            completeFirstActiveAction(1L); // container 0: TT_DRIVE_UNDER_QC
            // Now container 0's TT_HANDOVER_FROM_QC should be active

            // Act: truck becomes unavailable
            List<SideEffect> effects = engine.processEvent(unavailableTruck("TT01"));

            // Assert: actions for BOTH containers should be completed
            List<ActionCompleted> completedEffects = effects.stream()
                    .filter(e -> e instanceof ActionCompleted)
                    .map(e -> (ActionCompleted) e)
                    .toList();
            assertFalse(completedEffects.isEmpty(),
                    "Should have completed actions for both containers");
            assertTrue(completedEffects.stream().allMatch(ac ->
                    ac.reason() == CompletionReason.TT_UNAVAILABLE),
                    "All completed actions should have TT_UNAVAILABLE reason");
        }

        @Test
        @DisplayName("Should set completion reason on the Action record")
        void completionReasonOnAction() {
            // Arrange: advance to TT_HANDOVER_FROM_QC active
            engine.processEvent(workingTruck("TT01"));
            ScheduleCreated schedule = scheduleWithTTWorkflow(1L, 0);
            engine.processEvent(schedule);
            engine.processEvent(new TimeEvent(now));
            completeFirstActiveAction(1L); // TT_DRIVE_TO_QC_PULL
            completeFirstActiveAction(1L); // TT_DRIVE_TO_QC_STANDBY
            completeFirstActiveAction(1L); // TT_DRIVE_UNDER_QC

            // Act
            engine.processEvent(unavailableTruck("TT01"));

            // Assert: check that force-completed actions have completionReason set
            boolean foundReasonedAction = false;
            for (Action action : schedule.takts().getFirst().actions()) {
                Action current = scheduleRunner.getAction(1L, action.id());
                if (current != null && current.completionReason() != null) {
                    assertEquals(CompletionReason.TT_UNAVAILABLE, current.completionReason());
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
        @DisplayName("WORKING status should not trigger any handling")
        void workingStatusNoEffect() {
            engine.processEvent(workingTruck("TT01"));
            ScheduleCreated schedule = scheduleWithTTWorkflow(1L, 0);
            engine.processEvent(schedule);
            engine.processEvent(new TimeEvent(now));

            // Send another WORKING event
            List<SideEffect> effects = engine.processEvent(workingTruck("TT01"));

            // Should not produce TruckUnassigned or ActionCompleted with reason
            assertFalse(effects.stream().anyMatch(e -> e instanceof TruckUnassigned),
                    "WORKING status should not produce TruckUnassigned");
            assertFalse(effects.stream().anyMatch(e ->
                    e instanceof ActionCompleted ac && ac.reason() != null),
                    "WORKING status should not produce ActionCompleted with reason");
        }

        @Test
        @DisplayName("Unknown truck should be ignored")
        void unknownTruckIgnored() {
            engine.processEvent(workingTruck("TT01"));
            ScheduleCreated schedule = scheduleWithTTWorkflow(1L, 0);
            engine.processEvent(schedule);
            engine.processEvent(new TimeEvent(now));

            // Make unknown truck unavailable
            List<SideEffect> effects = engine.processEvent(unavailableTruck("TT99"));

            assertFalse(effects.stream().anyMatch(e -> e instanceof TruckUnassigned),
                    "Unknown truck should not produce TruckUnassigned");
            assertFalse(effects.stream().anyMatch(e ->
                    e instanceof ActionCompleted ac && ac.reason() != null),
                    "Unknown truck should not produce ActionCompleted with reason");
        }

        @Test
        @DisplayName("Non-TT CHE event should be ignored")
        void nonTTEventIgnored() {
            ContainerHandlingEquipmentEvent rtgEvent = new ContainerHandlingEquipmentEvent(
                    "CHE Status Changed", 200L, "U", "TERM1", 1L,
                    "RTG01", CheStatus.UNAVAILABLE, "RTG", 10L, CheJobStepState.IDLE, 0L);

            List<SideEffect> effects = engine.processEvent(rtgEvent);

            assertFalse(effects.stream().anyMatch(e -> e instanceof TruckUnassigned),
                    "Non-TT CHE event should not produce TruckUnassigned");
        }
    }

    private boolean hasTruckAssigned(String cheShortName) {
        for (var takt : scheduleRunner.getScheduleTakts(1L)) {
            for (var action : takt.actions()) {
                Action current = scheduleRunner.getAction(1L, action.id());
                if (current != null && cheShortName.equals(current.cheShortName())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Completes exactly one active action (the first found) and returns.
     * This is important because completing an action may immediately activate the next one.
     */
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
