package com.wonderingwizard.domain.takt;

/**
 * Determines how a condition affects action activation.
 */
public enum ConditionMode {
    /** Action can proceed when this condition is satisfied. */
    ACTIVATE,
    /** Action should be skipped (deferred) when this condition is satisfied. */
    SKIP
}
