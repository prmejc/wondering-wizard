package com.wonderingwizard.domain.takt;

/**
 * Condition that requires the parent takt to be active before the action can start.
 * Applies to first actions in a takt (those with no intra-takt dependencies).
 */
public record TaktActivationCondition(String id, String taktName) implements ActionCondition {

    public TaktActivationCondition(String taktName) {
        this("takt-activation", taktName);
    }

    @Override
    public boolean evaluate(ActionConditionContext context) {
        return context.taktActive();
    }

    @Override
    public String explanation(ActionConditionContext context) {
        return "Waiting for takt '" + taktName + "' to become active";
    }

    @Override
    public String type() {
        return "TAKT_ACTIVATION";
    }
}
