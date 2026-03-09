package com.wonderingwizard.domain.takt;

/**
 * Represents a condition that must be satisfied before a takt can start.
 * Conditions can be evaluated against current state, provide human-readable explanations,
 * and can be overridden manually.
 */
public sealed interface TaktCondition permits TimeCondition, DependencyCondition {

    /**
     * Unique identifier for this condition within a takt, used for override targeting.
     */
    String id();

    /**
     * Evaluates whether this condition is currently satisfied.
     *
     * @param context the current evaluation context
     * @return true if the condition is met
     */
    boolean evaluate(ConditionContext context);

    /**
     * Returns a human-readable explanation of what this condition is waiting for.
     * Called when the condition is NOT yet satisfied to explain the blocker.
     *
     * @param context the current evaluation context
     * @return explanation string describing what is needed
     */
    String explanation(ConditionContext context);

    /**
     * Short label for this condition type, used in the UI.
     */
    String type();
}
