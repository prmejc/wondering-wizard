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
     * The result of a truck allocation, containing the truck's identity.
     *
     * @param cheShortName the short name of the truck (e.g., "TT01")
     * @param cheId the CHE identifier of the truck (nullable)
     */
    record TruckAssignment(String cheShortName, Long cheId) {}

    /**
     * Finds and returns a free truck that can be assigned to a TT action.
     *
     * @param currentlyAssignedTrucks set of cheShortNames already assigned to actions in active schedules
     * @return the assignment with cheShortName and cheId, or empty if none available
     */
    Optional<TruckAssignment> allocateFreeTruck(Set<String> currentlyAssignedTrucks);
}
