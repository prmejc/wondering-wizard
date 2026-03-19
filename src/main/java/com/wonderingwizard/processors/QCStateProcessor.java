package com.wonderingwizard.processors;

import com.wonderingwizard.engine.Event;
import com.wonderingwizard.engine.EventProcessor;
import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.CraneAvailabilityStatus;
import com.wonderingwizard.events.CraneAvailabilityStatusEvent;
import com.wonderingwizard.events.CraneDelayActivityEvent;
import com.wonderingwizard.events.CraneReadinessEvent;
import com.wonderingwizard.events.QuayCraneMappingEvent;
import com.wonderingwizard.sideeffects.QCStateUpdated;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Processor that maintains the current state of quay cranes (QCs).
 * <p>
 * Processes {@link QuayCraneMappingEvent}, {@link CraneReadinessEvent}, and
 * {@link CraneAvailabilityStatusEvent} events. State is keyed by
 * {@code quayCraneShortName}/{@code cheId} — a new event with the same name
 * overrides the previous state. New QCs are created automatically when first seen
 * (from any event type).
 */
public class QCStateProcessor implements EventProcessor {

    private final Map<String, QuayCraneMappingEvent> qcState = new LinkedHashMap<>();
    private final Map<String, CraneReadinessEvent> craneReadiness = new LinkedHashMap<>();
    private final Map<String, CraneAvailabilityStatus> craneAvailability = new LinkedHashMap<>();
    private final Map<String, List<CraneDelayActivityEvent>> craneDelays = new LinkedHashMap<>();

    private static final int MAX_DELAYS_PER_CRANE = 3;

    @Override
    public List<SideEffect> process(Event event) {
        if (event instanceof QuayCraneMappingEvent qcEvent) {
            return handleQCEvent(qcEvent);
        }
        if (event instanceof CraneReadinessEvent readiness) {
            return handleCraneReadiness(readiness);
        }
        if (event instanceof CraneAvailabilityStatusEvent availability) {
            return handleCraneAvailability(availability);
        }
        if (event instanceof CraneDelayActivityEvent delay) {
            return handleCraneDelay(delay);
        }
        return List.of();
    }

    private List<SideEffect> handleQCEvent(QuayCraneMappingEvent event) {
        String name = event.quayCraneShortName();
        if (name == null || name.isBlank()) {
            return List.of();
        }
        qcState.put(name, event);
        return List.of(new QCStateUpdated(name, event));
    }

    private List<SideEffect> handleCraneReadiness(CraneReadinessEvent event) {
        String name = event.qcShortName();
        if (name == null || name.isBlank()) {
            return List.of();
        }
        // Create a minimal QC mapping entry if QC is not yet known
        if (!qcState.containsKey(name)) {
            qcState.put(name, new QuayCraneMappingEvent(
                    name, null, null, null,
                    null, null, null, null, null, "", 0L));
        }
        craneReadiness.put(name, event);
        return List.of(new QCStateUpdated(name, qcState.get(name)));
    }

    private List<SideEffect> handleCraneAvailability(CraneAvailabilityStatusEvent event) {
        String cheId = event.cheId();
        if (cheId == null || cheId.isBlank()) {
            return List.of();
        }
        // Create a minimal QC mapping entry if QC is not yet known
        if (!qcState.containsKey(cheId)) {
            qcState.put(cheId, new QuayCraneMappingEvent(
                    cheId, null, null, null,
                    null, null, null, null, null, event.terminalCode(), event.sourceTsMs()));
        }
        craneAvailability.put(cheId, event.cheStatus());
        return List.of(new QCStateUpdated(cheId, qcState.get(cheId)));
    }

    private List<SideEffect> handleCraneDelay(CraneDelayActivityEvent event) {
        String name = event.cheShortName();
        if (name == null || name.isBlank()) {
            return List.of();
        }
        if (!qcState.containsKey(name)) {
            qcState.put(name, new QuayCraneMappingEvent(
                    name, null, null, null,
                    null, null, null, null, null,
                    event.cdhTerminalCode() != null ? event.cdhTerminalCode() : "", 0L));
        }
        List<CraneDelayActivityEvent> delays = craneDelays.computeIfAbsent(name, k -> new ArrayList<>());
        delays.add(event);
        while (delays.size() > MAX_DELAYS_PER_CRANE) {
            delays.removeFirst();
        }
        return List.of(new QCStateUpdated(name, qcState.get(name)));
    }

    /**
     * Returns the current mapping state of all known quay cranes.
     */
    public Map<String, QuayCraneMappingEvent> getQCState() {
        return Map.copyOf(qcState);
    }

    /**
     * Returns the current mapping state of a specific quay crane.
     */
    public QuayCraneMappingEvent getQCState(String qcShortName) {
        return qcState.get(qcShortName);
    }

    /**
     * Returns the latest crane readiness event per quay crane.
     */
    public Map<String, CraneReadinessEvent> getCraneReadiness() {
        return Map.copyOf(craneReadiness);
    }

    /**
     * Returns the current availability status per quay crane.
     */
    public Map<String, CraneAvailabilityStatus> getCraneAvailability() {
        return Map.copyOf(craneAvailability);
    }

    /**
     * Returns the last {@value MAX_DELAYS_PER_CRANE} crane delay activities per quay crane.
     */
    public Map<String, List<CraneDelayActivityEvent>> getCraneDelays() {
        Map<String, List<CraneDelayActivityEvent>> copy = new LinkedHashMap<>();
        for (var entry : craneDelays.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(copy);
    }

    @Override
    public Object captureState() {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("qcState", new LinkedHashMap<>(qcState));
        snapshot.put("craneReadiness", new LinkedHashMap<>(craneReadiness));
        snapshot.put("craneAvailability", new LinkedHashMap<>(craneAvailability));
        Map<String, List<CraneDelayActivityEvent>> delaysCopy = new LinkedHashMap<>();
        for (var entry : craneDelays.entrySet()) {
            delaysCopy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        snapshot.put("craneDelays", delaysCopy);
        return snapshot;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void restoreState(Object state) {
        if (state instanceof Map<?, ?> raw && raw.containsKey("qcState")) {
            qcState.clear();
            qcState.putAll((Map<String, QuayCraneMappingEvent>) raw.get("qcState"));
            craneReadiness.clear();
            Object readiness = raw.get("craneReadiness");
            if (readiness instanceof Map) {
                craneReadiness.putAll((Map<String, CraneReadinessEvent>) readiness);
            }
            craneAvailability.clear();
            Object availability = raw.get("craneAvailability");
            if (availability instanceof Map) {
                craneAvailability.putAll((Map<String, CraneAvailabilityStatus>) availability);
            }
            craneDelays.clear();
            Object delays = raw.get("craneDelays");
            if (delays instanceof Map) {
                ((Map<String, List<CraneDelayActivityEvent>>) delays).forEach(
                        (k, v) -> craneDelays.put(k, new ArrayList<>(v)));
            }
        } else if (state instanceof Map) {
            // Backwards compatibility: old snapshots only had qcState as the top-level map
            qcState.clear();
            qcState.putAll((Map<String, QuayCraneMappingEvent>) state);
            craneReadiness.clear();
            craneAvailability.clear();
            craneDelays.clear();
        } else {
            throw new IllegalArgumentException("Invalid state for QCStateProcessor");
        }
    }
}
