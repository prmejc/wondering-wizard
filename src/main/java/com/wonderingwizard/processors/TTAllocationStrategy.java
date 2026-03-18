package com.wonderingwizard.processors;

import java.util.Optional;
import java.util.Set;

/**
 * Strategy for allocating a free terminal truck (TT) to an action.
 * <p>
 * Implementations determine which trucks are available based on their
 * current state and which trucks are already assigned to active actions.
 */
public interface TTAllocationStrategy {

    /**
     * Finds and returns a free truck that can be assigned to a TT action.
     *
     * @param currentlyAssignedTrucks set of cheShortNames already assigned to actions in active schedules
     * @return the cheShortName of a free truck, or empty if none available
     */
    Optional<String> allocateFreeTruck(Set<String> currentlyAssignedTrucks);
}
