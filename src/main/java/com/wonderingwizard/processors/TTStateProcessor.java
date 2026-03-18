package com.wonderingwizard.processors;

import com.wonderingwizard.engine.Event;
import com.wonderingwizard.engine.EventProcessor;
import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.CheStatus;
import com.wonderingwizard.events.ContainerHandlingEquipmentEvent;
import com.wonderingwizard.sideeffects.TTStateUpdated;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Processor that maintains the current state of terminal trucks (TTs)
 * and implements TT allocation strategy.
 * <p>
 * Processes {@link ContainerHandlingEquipmentEvent} events where cheKind is "TT".
 * State is keyed by {@code cheShortName} — a new event with the same cheShortName
 * overrides the previous state.
 * <p>
 * A truck is considered free when its status is {@link CheStatus#WORKING}
 * and its pool ID matches the configured FES pool ID, and it is not already
 * assigned to any action in an active schedule.
 */
public class TTStateProcessor implements EventProcessor, TTAllocationStrategy {

    private static final String CHE_KIND_TT = "TT";
    private static final long FES_POOL_ID = 23L;

    private final long fesPoolId;
    private final Map<String, ContainerHandlingEquipmentEvent> truckState = new LinkedHashMap<>();

    public TTStateProcessor() {
        this(FES_POOL_ID);
    }

    public TTStateProcessor(long fesPoolId) {
        this.fesPoolId = fesPoolId;
    }

    @Override
    public List<SideEffect> process(Event event) {
        if (event instanceof ContainerHandlingEquipmentEvent cheEvent
                && CHE_KIND_TT.equals(cheEvent.cheKind())) {
            return handleTTEvent(cheEvent);
        }
        return List.of();
    }

    private List<SideEffect> handleTTEvent(ContainerHandlingEquipmentEvent event) {
        String name = event.cheShortName();
        if (name == null || name.isBlank()) {
            return List.of();
        }
        truckState.put(name, event);
        return List.of(new TTStateUpdated(name, event));
    }

    @Override
    public Optional<String> allocateFreeTruck(Set<String> currentlyAssignedTrucks) {
        for (Map.Entry<String, ContainerHandlingEquipmentEvent> entry : truckState.entrySet()) {
            String name = entry.getKey();
            ContainerHandlingEquipmentEvent event = entry.getValue();
            if (event.cheStatus() == CheStatus.WORKING
                    && event.chePoolId() != null
                    && event.chePoolId() == fesPoolId
                    && !currentlyAssignedTrucks.contains(name)) {
                return Optional.of(name);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the current state of all known trucks.
     *
     * @return unmodifiable map of cheShortName to latest CHE event
     */
    public Map<String, ContainerHandlingEquipmentEvent> getTruckState() {
        return Map.copyOf(truckState);
    }

    /**
     * Returns the current state of a specific truck.
     *
     * @param cheShortName the truck's short name
     * @return the latest CHE event for this truck, or null if unknown
     */
    public ContainerHandlingEquipmentEvent getTruckState(String cheShortName) {
        return truckState.get(cheShortName);
    }

    @Override
    public Object captureState() {
        return new HashMap<>(truckState);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void restoreState(Object state) {
        if (!(state instanceof Map)) {
            throw new IllegalArgumentException("Invalid state for TTStateProcessor");
        }
        truckState.clear();
        truckState.putAll((Map<String, ContainerHandlingEquipmentEvent>) state);
    }
}
