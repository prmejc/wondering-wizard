package com.wonderingwizard.domain.takt;

/**
 * Represents a condition that affects action activation.
 * <ul>
 *   <li>{@link ConditionMode#ACTIVATE} — action can proceed when satisfied (default)</li>
 *   <li>{@link ConditionMode#SKIP} — action should be deferred while satisfied</li>
 * </ul>
 */
public sealed interface ActionCondition
        permits TaktActivationCondition, ActionDependencyCondition, EventGateCondition, LocationFreeCondition {

    String id();

    boolean evaluate(ActionConditionContext context);

    String explanation(ActionConditionContext context);

    String type();

    default ConditionMode mode() {
        return ConditionMode.ACTIVATE;
    }
}
