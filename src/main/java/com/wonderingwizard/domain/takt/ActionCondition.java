package com.wonderingwizard.domain.takt;

/**
 * Represents a condition that must be satisfied before an action can start.
 * Follows the same pattern as {@link TaktCondition}.
 */
public sealed interface ActionCondition permits TaktActivationCondition, ActionDependencyCondition, EventGateCondition {

    String id();

    boolean evaluate(ActionConditionContext context);

    String explanation(ActionConditionContext context);

    String type();
}
