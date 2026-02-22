package com.wonderingwizard.domain.takt;

/**
 * Represents the execution state of an action within a schedule.
 */
public enum ActionState {
    /**
     * Action has not yet started - waiting for dependencies or schedule start.
     */
    PENDING,

    /**
     * Action is currently being executed.
     */
    ACTIVE,

    /**
     * Action has been completed.
     */
    COMPLETED
}
