package com.wonderingwizard.domain.takt;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Provides the context needed to evaluate takt conditions.
 *
 * @param currentTime the current simulation time
 * @param completedActionIds set of all completed action IDs across the schedule
 */
public record ConditionContext(Instant currentTime, Set<UUID> completedActionIds) {
}
