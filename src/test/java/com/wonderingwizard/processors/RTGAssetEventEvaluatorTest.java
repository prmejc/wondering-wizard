package com.wonderingwizard.processors;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.ActionStatus;
import com.wonderingwizard.domain.takt.ActionType;
import com.wonderingwizard.domain.takt.CompletionCondition;
import com.wonderingwizard.domain.takt.DeviceType;
import com.wonderingwizard.events.AssetEvent;
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

@DisplayName("RTGAssetEventEvaluator Tests")
class RTGAssetEventEvaluatorTest {

    private static final Instant BASE_TIME = Instant.parse("2024-01-01T10:00:00Z");
    private static final String RTG_NAME = "RTZ03";

    private final RTGAssetEventEvaluator evaluator = new RTGAssetEventEvaluator();

    private WorkInstructionEvent wiWithPutChe(String putChe) {
        return new WorkInstructionEvent("", 1L, 100L, "QCZ1", "Planned",
                BASE_TIME, 120, 60, putChe, false, false, false, 0, "", "CONT123");
    }

    private Action createRtgLiftAction(ActionStatus status, String putChe) {
        return new Action(UUID.randomUUID(), DeviceType.RTG, ActionType.RTG_LIFT_FROM_TT,
                "lift from tt", Set.of(), 0, 30, 0,
                List.of(wiWithPutChe(putChe)), List.of(), false,
                null, null, null, null, status, List.of(),
                List.of(new CompletionCondition("rtg-lifted-from-truck",
                        RTGAssetEventEvaluator.CONDITION_TYPE, "RTGliftedContainerfromTruck")));
    }

    @Test
    @DisplayName("Satisfies condition when RTG asset event matches cheId and operationalEvent")
    void satisfiesConditionOnMatch() {
        AssetEvent event = new AssetEvent("DSCH", "RTGliftedContainerfromTruck", RTG_NAME, "", BASE_TIME);
        Action action = createRtgLiftAction(ActionStatus.ACTIVE, RTG_NAME);

        Map<UUID, Action> allActions = new HashMap<>();
        allActions.put(action.id(), action);

        Map<UUID, List<String>> result = evaluator.evaluateSatisfied(event, allActions);

        assertEquals(1, result.size());
        assertTrue(result.containsKey(action.id()));
        assertEquals(List.of("rtg-lifted-from-truck"), result.get(action.id()));
    }

    @Test
    @DisplayName("Returns empty when cheId does not match")
    void emptyWhenCheIdDiffers() {
        AssetEvent event = new AssetEvent("DSCH", "RTGliftedContainerfromTruck", "RTZ99", "", BASE_TIME);
        Action action = createRtgLiftAction(ActionStatus.ACTIVE, RTG_NAME);

        Map<UUID, Action> allActions = new HashMap<>();
        allActions.put(action.id(), action);

        Map<UUID, List<String>> result = evaluator.evaluateSatisfied(event, allActions);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Returns empty when operationalEvent does not match")
    void emptyWhenOperationalEventDiffers() {
        AssetEvent event = new AssetEvent("DSCH", "RTGplacedContainerOnYard", RTG_NAME, "", BASE_TIME);
        Action action = createRtgLiftAction(ActionStatus.ACTIVE, RTG_NAME);

        Map<UUID, Action> allActions = new HashMap<>();
        allActions.put(action.id(), action);

        Map<UUID, List<String>> result = evaluator.evaluateSatisfied(event, allActions);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Returns empty for non-AssetEvent events")
    void emptyForNonAssetEvent() {
        TimeEvent event = new TimeEvent(BASE_TIME);
        Action action = createRtgLiftAction(ActionStatus.ACTIVE, RTG_NAME);

        Map<UUID, Action> allActions = new HashMap<>();
        allActions.put(action.id(), action);

        Map<UUID, List<String>> result = evaluator.evaluateSatisfied(event, allActions);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Returns empty when action is not ACTIVE")
    void emptyWhenActionNotActive() {
        AssetEvent event = new AssetEvent("DSCH", "RTGliftedContainerfromTruck", RTG_NAME, "", BASE_TIME);
        Action action = createRtgLiftAction(ActionStatus.PENDING, RTG_NAME);

        Map<UUID, Action> allActions = new HashMap<>();
        allActions.put(action.id(), action);

        Map<UUID, List<String>> result = evaluator.evaluateSatisfied(event, allActions);

        assertTrue(result.isEmpty());
    }
}
