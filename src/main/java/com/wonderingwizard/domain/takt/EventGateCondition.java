package com.wonderingwizard.domain.takt;

/**
 * Condition that requires an external event (e.g., a WorkInstructionEvent with a specific eventType)
 * to be received before this action can activate.
 *
 * <p>The gate follows a state machine: UNARMED → ARMED → SATISFIED.
 * <ul>
 *   <li>The gate is <b>armed</b> when the source action (identified by sourceDeviceType + sourceActionType)
 *       becomes active.</li>
 *   <li>The gate is <b>satisfied</b> when a matching event (identified by requiredEventType) arrives
 *       while the gate is armed.</li>
 * </ul>
 *
 * @param id unique condition ID for override support (e.g., "event-gate:QC Discharged Container")
 * @param sourceDeviceType the device type of the action that arms this gate
 * @param sourceActionType the action type that arms this gate when activated
 * @param requiredEventType the WorkInstructionEvent eventType that satisfies this gate
 */
public record EventGateCondition(String id, DeviceType sourceDeviceType, ActionType sourceActionType,
                                  String requiredEventType) implements ActionCondition {

    public EventGateCondition(DeviceType sourceDeviceType, ActionType sourceActionType, String requiredEventType) {
        this("event-gate:" + requiredEventType, sourceDeviceType, sourceActionType, requiredEventType);
    }

    @Override
    public boolean evaluate(ActionConditionContext context) {
        return context.satisfiedEventGateIds().contains(id);
    }

    @Override
    public String explanation(ActionConditionContext context) {
        return "Waiting for event '" + requiredEventType + "'";
    }

    @Override
    public String type() {
        return "EVENT_GATE";
    }
}
