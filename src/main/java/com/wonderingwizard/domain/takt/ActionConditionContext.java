package com.wonderingwizard.domain.takt;

import java.util.Set;
import java.util.UUID;

/**
 * Context for evaluating {@link ActionCondition}s.
 *
 * @param taktActive whether the action's parent takt is currently active
 * @param completedActionIds the set of action IDs that have been completed
 */
public record ActionConditionContext(boolean taktActive, Set<UUID> completedActionIds) {}
