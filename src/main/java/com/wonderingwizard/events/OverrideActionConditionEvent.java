package com.wonderingwizard.events;

import com.wonderingwizard.engine.Event;

import java.util.UUID;

/**
 * Event to manually override (satisfy) a specific condition on an action,
 * allowing the action to start even if that condition is not naturally met.
 *
 * @param workQueueId the work queue the action belongs to
 * @param actionId the unique identifier of the action
 * @param conditionId the condition identifier to override (e.g., "action-dependencies")
 */
public record OverrideActionConditionEvent(
        String workQueueId,
        UUID actionId,
        String conditionId
) implements Event {}
