package com.wonderingwizard.processors;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.ActionStatus;
import com.wonderingwizard.domain.takt.ActionType;
import com.wonderingwizard.domain.takt.CompletionReason;
import com.wonderingwizard.domain.takt.DeviceType;
import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.ContainerMoveStateEvent;
import com.wonderingwizard.events.WorkInstructionEvent;
import com.wonderingwizard.sideeffects.TruckUnassigned;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ContainerMoveStoppedHandler")
class ContainerMoveStoppedHandlerTest {

    private ContainerMoveStoppedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ContainerMoveStoppedHandler();
    }

    private ContainerMoveStateEvent stoppedEvent(String cheName, long wiId) {
        return new ContainerMoveStateEvent(
                "STOPPED", "ERROR", "TT_ASSIGNED",
                cheName, wiId, "DSCH", "MSKU123", "MAPTM", "error", 0L);
    }

    private ContainerMoveStateEvent nonMatchingEvent(String cheName, long wiId) {
        return new ContainerMoveStateEvent(
                "STARTED", "SUCCESS", "TT_ASSIGNED",
                cheName, wiId, "", "", "", "", 0L);
    }

    @Test
    @DisplayName("Should ignore non-matching events")
    void ignoreNonMatching() {
        var context = new StubScheduleContext();
        List<SideEffect> effects = handler.process(nonMatchingEvent("TG04", 1), context);
        assertTrue(effects.isEmpty());
    }

    @Test
    @DisplayName("Should ignore when no matching truck/WI found")
    void ignoreNoMatch() {
        var context = new StubScheduleContext();
        context.workQueueIds = Set.of(100L);

        UUID actionId = UUID.randomUUID();
        WorkInstructionEvent wi = new WorkInstructionEvent(
                "", 999L, 100L, "", "Planned", null, 0, 0, "", false, false, false, 0, "", "");
        Action action = new Action(actionId, DeviceType.TT, ActionType.TT_DRIVE_TO_QC_PULL,
                "Drive", Set.of(), 0, 60, 0, List.of(wi));
        action = action.withTruckAssignment(1L, "TG04");
        context.actions.put(100L, Map.of(actionId, action));
        context.actionStatuses.put(actionId, ActionStatus.ACTIVE);

        // Different WI ID
        List<SideEffect> effects = handler.process(stoppedEvent("TG04", 123L), context);
        assertTrue(effects.isEmpty());
    }

    @Test
    @DisplayName("Should reset TT actions before pivot")
    void resetBeforePivot() {
        var context = new StubScheduleContext();
        context.workQueueIds = Set.of(100L);

        UUID driveAction = UUID.randomUUID();
        WorkInstructionEvent wi = new WorkInstructionEvent(
                "", 42L, 100L, "", "Planned", null, 0, 0, "", false, false, false, 0, "", "");
        Action action = new Action(driveAction, DeviceType.TT, ActionType.TT_DRIVE_TO_QC_PULL,
                "Drive", Set.of(), 0, 60, 0, List.of(wi));
        action = action.withTruckAssignment(1L, "TG04");

        UUID handoverAction = UUID.randomUUID();
        Action handover = new Action(handoverAction, DeviceType.TT, ActionType.TT_HANDOVER_FROM_QC,
                "Handover", Set.of(), 0, 30, 0, List.of());
        handover = handover.withTruckAssignment(1L, "TG04");

        context.actions.put(100L, Map.of(driveAction, action, handoverAction, handover));
        context.actionStatuses.put(driveAction, ActionStatus.ACTIVE);
        context.actionStatuses.put(handoverAction, ActionStatus.PENDING);

        List<SideEffect> effects = handler.process(stoppedEvent("TG04", 42L), context);

        assertTrue(context.resetActions.contains(driveAction));
        assertTrue(context.resetActions.contains(handoverAction));
        assertTrue(context.activateCalled.contains(100L));
    }

    @Test
    @DisplayName("Should ignore after pivot (handover started)")
    void ignoreAfterPivot() {
        var context = new StubScheduleContext();
        context.workQueueIds = Set.of(100L);

        UUID driveAction = UUID.randomUUID();
        WorkInstructionEvent wi = new WorkInstructionEvent(
                "", 42L, 100L, "", "Planned", null, 0, 0, "", false, false, false, 0, "", "");
        Action action = new Action(driveAction, DeviceType.TT, ActionType.TT_DRIVE_TO_QC_PULL,
                "Drive", Set.of(), 0, 60, 0, List.of(wi));
        action = action.withTruckAssignment(1L, "TG04");

        UUID handoverAction = UUID.randomUUID();
        Action handover = new Action(handoverAction, DeviceType.TT, ActionType.TT_HANDOVER_FROM_QC,
                "Handover", Set.of(), 0, 30, 0, List.of());
        handover = handover.withTruckAssignment(1L, "TG04");

        context.actions.put(100L, Map.of(driveAction, action, handoverAction, handover));
        context.actionStatuses.put(driveAction, ActionStatus.COMPLETED);
        context.actionStatuses.put(handoverAction, ActionStatus.ACTIVE); // past pivot

        List<SideEffect> effects = handler.process(stoppedEvent("TG04", 42L), context);

        assertTrue(context.resetActions.isEmpty());
        assertTrue(effects.isEmpty());
    }

    // Stub context for testing
    private static class StubScheduleContext implements ScheduleContext {
        Set<Long> workQueueIds = Set.of();
        Map<Long, Map<UUID, Action>> actions = new LinkedHashMap<>();
        Map<UUID, ActionStatus> actionStatuses = new LinkedHashMap<>();
        Set<UUID> resetActions = new java.util.HashSet<>();
        Set<Long> activateCalled = new java.util.HashSet<>();

        @Override public Set<Long> getScheduleWorkQueueIds() { return workQueueIds; }
        @Override public Map<UUID, Action> getActions(long wqId) {
            return actions.getOrDefault(wqId, Map.of());
        }
        @Override public String getTaktName(long wqId, UUID actionId) { return "takt1"; }
        @Override public ActionStatus getActionStatus(long wqId, UUID actionId) {
            return actionStatuses.getOrDefault(actionId, ActionStatus.PENDING);
        }
        @Override public List<SideEffect> completeActionWithReason(long wqId, UUID actionId, CompletionReason r) {
            return List.of();
        }
        @Override public List<SideEffect> resetTTAction(long wqId, UUID actionId) {
            resetActions.add(actionId);
            return List.of(new TruckUnassigned(actionId, wqId, "test"));
        }
        @Override public List<SideEffect> cascadeTaktCompletion(long wqId) { return List.of(); }
        @Override public List<SideEffect> tryActivateEligibleActions(long wqId) {
            activateCalled.add(wqId);
            return List.of();
        }
        @Override public java.time.Instant getCurrentTime() { return java.time.Instant.now(); }
    }
}
