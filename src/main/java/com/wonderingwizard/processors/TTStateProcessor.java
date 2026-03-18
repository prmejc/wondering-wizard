package com.wonderingwizard.processors;

import com.wonderingwizard.engine.Event;
import com.wonderingwizard.engine.EventProcessor;
import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.CheLogicalPositionEvent;
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
    private final Map<String, CheLogicalPositionEvent> truckPositions = new LinkedHashMap<>();

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
        if (event instanceof CheLogicalPositionEvent posEvent) {
            return handlePositionEvent(posEvent);
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

    private List<SideEffect> handlePositionEvent(CheLogicalPositionEvent event) {
        String name = event.cheShortName();
        if (name == null || name.isBlank()) {
            return List.of();
        }
        // Only store position for trucks we already know about
        if (!truckState.containsKey(name)) {
            return List.of();
        }
        truckPositions.put(name, event);
        return List.of();
    }

    @Override
    public Optional<TruckAssignment> allocateFreeTruck(Set<String> currentlyAssignedTrucks) {
        for (Map.Entry<String, ContainerHandlingEquipmentEvent> entry : truckState.entrySet()) {
            String name = entry.getKey();
            ContainerHandlingEquipmentEvent event = entry.getValue();
            if (event.cheStatus() == CheStatus.WORKING
                    && event.chePoolId() != null
                    && event.chePoolId() == fesPoolId
                    && !currentlyAssignedTrucks.contains(name)) {
                return Optional.of(new TruckAssignment(name, event.cheId()));
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

    /**
     * Returns the current position data for all known trucks.
     */
    public Map<String, CheLogicalPositionEvent> getTruckPositions() {
        return Map.copyOf(truckPositions);
    }

    @Override
    public Object captureState() {
        var state = new HashMap<String, Object>();
        state.put("truckState", new HashMap<>(truckState));
        state.put("truckPositions", new HashMap<>(truckPositions));
        return state;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void restoreState(Object state) {
        if (state instanceof Map<?,?> stateMap && stateMap.containsKey("truckState")) {
            truckState.clear();
            truckState.putAll((Map<String, ContainerHandlingEquipmentEvent>) stateMap.get("truckState"));
            truckPositions.clear();
            Object posState = stateMap.get("truckPositions");
            if (posState instanceof Map) {
                truckPositions.putAll((Map<String, CheLogicalPositionEvent>) posState);
            }
        } else if (state instanceof Map) {
            // Backwards compatibility: old state format was just the truckState map
            truckState.clear();
            truckState.putAll((Map<String, ContainerHandlingEquipmentEvent>) state);
            truckPositions.clear();
        } else {
            throw new IllegalArgumentException("Invalid state for TTStateProcessor");
        }
    }
}
