package com.wonderingwizard.processors;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.ActionStatus;
import com.wonderingwizard.domain.takt.ActionType;
import com.wonderingwizard.domain.takt.CompletionCondition;
import com.wonderingwizard.domain.takt.DeviceType;
import com.wonderingwizard.events.TimeEvent;
import com.wonderingwizard.events.WorkInstructionEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ActionCompletedEvaluator Tests")
class ActionCompletedEvaluatorTest {

    private static final Instant BASE_TIME = Instant.parse("2024-01-01T10:00:00Z");
    private static final String CONTAINER_ID = "CONT123";

    private final ActionCompletedEvaluator evaluator = new ActionCompletedEvaluator();
    private final TimeEvent dummyEvent = new TimeEvent(BASE_TIME);

    private WorkInstructionEvent wiWithContainer(String containerId) {
        return new WorkInstructionEvent("", 1L, 100L, "QCZ1", "Planned",
                BASE_TIME, 120, 60, "RTZ01", false, false, false, 0, "", containerId);
    }

    private Action createAction(ActionType actionType, DeviceType deviceType, ActionStatus status,
                                String containerId, String triggerActionType) {
        List<WorkInstructionEvent> wis = List.of(wiWithContainer(containerId));
        List<CompletionCondition> conditions = triggerActionType != null
                ? List.of(new CompletionCondition("test-condition", ActionCompletedEvaluator.CONDITION_TYPE, triggerActionType))
                : List.of();
        return new Action(UUID.randomUUID(), deviceType, actionType, actionType.displayName(), Set.of(),
                0, 30, 0, wis, List.of(), false, null, null, null, null, status, List.of(), conditions, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("Satisfies condition when TT_DRIVE_TO_RTG_UNDER is COMPLETED for same container")
    void satisfiesConditionWhenTTDriveCompleted() {
        Action ttAction = createAction(ActionType.TT_DRIVE_TO_RTG_UNDER, DeviceType.TT,
                ActionStatus.COMPLETED, CONTAINER_ID, null);
        Action rtgWait = createAction(ActionType.RTG_WAIT_FOR_TRUCK, DeviceType.RTG,
                ActionStatus.ACTIVE, CONTAINER_ID, "TT_DRIVE_TO_RTG_UNDER");

        Map<UUID, Action> allActions = new HashMap<>();
        allActions.put(ttAction.id(), ttAction);
        allActions.put(rtgWait.id(), rtgWait);

        Map<UUID, List<String>> result = evaluator.evaluateSatisfied(dummyEvent, allActions);

        assertEquals(1, result.size());
        assertTrue(result.containsKey(rtgWait.id()));
        assertEquals(List.of("test-condition"), result.get(rtgWait.id()));
    }

    @Test
    @DisplayName("Satisfies condition when RTG_LIFT_FROM_TT is COMPLETED for same container")
    void satisfiesConditionWhenRTGLiftCompleted() {
        Action rtgLift = createAction(ActionType.RTG_LIFT_FROM_TT, DeviceType.RTG,
                ActionStatus.COMPLETED, CONTAINER_ID, null);
        Action ttHandover = createAction(ActionType.TT_HANDOVER_TO_RTG, DeviceType.TT,
                ActionStatus.ACTIVE, CONTAINER_ID, "RTG_LIFT_FROM_TT");

        Map<UUID, Action> allActions = new HashMap<>();
        allActions.put(rtgLift.id(), rtgLift);
        allActions.put(ttHandover.id(), ttHandover);

        Map<UUID, List<String>> result = evaluator.evaluateSatisfied(dummyEvent, allActions);

        assertEquals(1, result.size());
        assertTrue(result.containsKey(ttHandover.id()));
        assertEquals(List.of("test-condition"), result.get(ttHandover.id()));
    }

    @Test
    @DisplayName("Returns empty when trigger action is still ACTIVE")
    void emptyWhenTriggerStillActive() {
        Action ttAction = createAction(ActionType.TT_DRIVE_TO_RTG_UNDER, DeviceType.TT,
                ActionStatus.ACTIVE, CONTAINER_ID, null);
        Action rtgWait = createAction(ActionType.RTG_WAIT_FOR_TRUCK, DeviceType.RTG,
                ActionStatus.ACTIVE, CONTAINER_ID, "TT_DRIVE_TO_RTG_UNDER");

        Map<UUID, Action> allActions = new HashMap<>();
        allActions.put(ttAction.id(), ttAction);
        allActions.put(rtgWait.id(), rtgWait);

        Map<UUID, List<String>> result = evaluator.evaluateSatisfied(dummyEvent, allActions);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Returns empty when containers differ")
    void emptyWhenContainersDiffer() {
        Action ttAction = createAction(ActionType.TT_DRIVE_TO_RTG_UNDER, DeviceType.TT,
                ActionStatus.COMPLETED, "OTHER_CONTAINER", null);
        Action rtgWait = createAction(ActionType.RTG_WAIT_FOR_TRUCK, DeviceType.RTG,
                ActionStatus.ACTIVE, CONTAINER_ID, "TT_DRIVE_TO_RTG_UNDER");

        Map<UUID, Action> allActions = new HashMap<>();
        allActions.put(ttAction.id(), ttAction);
        allActions.put(rtgWait.id(), rtgWait);

        Map<UUID, List<String>> result = evaluator.evaluateSatisfied(dummyEvent, allActions);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Returns empty when no matching trigger action type exists")
    void emptyWhenNoMatchingTriggerType() {
        Action rtgWait = createAction(ActionType.RTG_WAIT_FOR_TRUCK, DeviceType.RTG,
                ActionStatus.ACTIVE, CONTAINER_ID, "TT_DRIVE_TO_RTG_UNDER");

        Map<UUID, Action> allActions = new HashMap<>();
        allActions.put(rtgWait.id(), rtgWait);

        Map<UUID, List<String>> result = evaluator.evaluateSatisfied(dummyEvent, allActions);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Does not match when trigger action type in description differs")
    void emptyWhenDescriptionDoesNotMatchTriggerType() {
        Action rtgLift = createAction(ActionType.RTG_LIFT_FROM_TT, DeviceType.RTG,
                ActionStatus.COMPLETED, CONTAINER_ID, null);
        // Condition expects TT_DRIVE_TO_RTG_UNDER, not RTG_LIFT_FROM_TT
        Action rtgWait = createAction(ActionType.RTG_WAIT_FOR_TRUCK, DeviceType.RTG,
                ActionStatus.ACTIVE, CONTAINER_ID, "TT_DRIVE_TO_RTG_UNDER");

        Map<UUID, Action> allActions = new HashMap<>();
        allActions.put(rtgLift.id(), rtgLift);
        allActions.put(rtgWait.id(), rtgWait);

        Map<UUID, List<String>> result = evaluator.evaluateSatisfied(dummyEvent, allActions);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Ignores actions without ACTION_COMPLETED condition type")
    void ignoresActionsWithoutActionCompletedCondition() {
        Action ttAction = createAction(ActionType.TT_DRIVE_TO_RTG_UNDER, DeviceType.TT,
                ActionStatus.COMPLETED, CONTAINER_ID, null);
        Action rtgAction = new Action(UUID.randomUUID(), DeviceType.RTG, ActionType.RTG_DRIVE,
                "rtg drive", Set.of(), 0, 30, 0,
                List.of(wiWithContainer(CONTAINER_ID)), List.of(), false,
                null, null, null, null, ActionStatus.ACTIVE, List.of(),
                List.of(new CompletionCondition("rtg-job-accepted", RTGJobOperationEvaluator.CONDITION_TYPE, "A")), null, null, null, null, null, null);

        Map<UUID, Action> allActions = new HashMap<>();
        allActions.put(ttAction.id(), ttAction);
        allActions.put(rtgAction.id(), rtgAction);

        Map<UUID, List<String>> result = evaluator.evaluateSatisfied(dummyEvent, allActions);

        assertTrue(result.isEmpty());
    }
}
