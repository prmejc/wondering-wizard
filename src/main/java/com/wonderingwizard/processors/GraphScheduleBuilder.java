package com.wonderingwizard.processors;

import com.wonderingwizard.domain.YardLocation;
import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.ActionType;
import com.wonderingwizard.domain.takt.DeviceType;
import com.wonderingwizard.domain.takt.EventGateCondition;
import com.wonderingwizard.domain.takt.Takt;
import com.wonderingwizard.events.EventType;
import com.wonderingwizard.events.LoadMode;
import com.wonderingwizard.events.WorkInstructionEvent;

import java.time.Instant;
import java.util.*;
import java.util.function.IntSupplier;

import static com.wonderingwizard.domain.takt.ActionType.*;
import static com.wonderingwizard.domain.takt.DeviceType.*;

/**
 * Graph-based schedule builder that constructs takts from a declarative action blueprint.
 *
 * <p>The blueprint defines all actions for a container as a flat list per resource chain.
 * Each action can declare:
 * <ul>
 *   <li>{@code firstInTakt} — this action starts a new takt boundary within its resource chain</li>
 *   <li>{@code anchor} — pins this action's takt to the container index (exactly one per container)</li>
 *   <li>{@code syncWith} — this action must be placed in the same takt as a referenced action from another resource</li>
 *   <li>{@code onlyOnePerTakt} — no other action with the same name may exist in the same takt</li>
 * </ul>
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Build the blueprint for each container (dynamic durations from work instruction)</li>
 *   <li>Split each resource chain into segments at {@code firstInTakt} boundaries</li>
 *   <li>Place the anchor segment at takt index = containerIndex</li>
 *   <li>Place remaining segments relative to the anchor by walking sync references and adjacency</li>
 *   <li>Create Action objects into takts (without dependencies)</li>
 *   <li>Wire dependencies as a post-processing step based on blueprint execution order</li>
 * </ol>
 */
public class GraphScheduleBuilder {

    private static final int TT_DRIVE_TO_BUFFER_SECONDS = 1;
    private static final int RTG_DRIVE_SECONDS = 1;
    private static final int DRIVE_TIME_MIN_SECONDS = 30;
    private static final int DRIVE_TIME_MAX_SECONDS = 300;
    private static final int DEFAULT_TAKT_DURATION = 120;

    private final IntSupplier driveTimeSupplier;
    private final IntSupplier qcDriveTimeOffsetSupplier;

    public GraphScheduleBuilder(IntSupplier driveTimeSupplier, IntSupplier qcDriveTimeOffsetSupplier) {
        this.driveTimeSupplier = driveTimeSupplier;
        this.qcDriveTimeOffsetSupplier = qcDriveTimeOffsetSupplier;
    }

    // ── Action Template ────────────────────────────────────────────────

    public record ActionTemplate(
            ActionType actionType,
            String name,
            DeviceType deviceType,
            int durationSeconds,
            boolean firstInTakt,
            boolean isAnchor,
            SyncRef syncWith,
            boolean onlyOnePerTakt,
            int deviceIndex,
            boolean independentAcrossContainers,
            int containerSuffix,
            SyncRef dependsOn,
            List<EventGate> eventGates,
            boolean skipWhenGatesSatisfied
    ) {
        static ActionTemplate of(ActionType actionType, DeviceType type, int duration) {
            return new ActionTemplate(actionType, actionType.displayName(), type, duration, false, false, null, false, 0, false, 0, null, List.of(), false);
        }

        static ActionTemplate of(ActionType actionType, int containerSuffix, DeviceType type, int duration) {
            return new ActionTemplate(actionType, actionType.displayName(containerSuffix), type, duration, false, false, null, false, 0, false, containerSuffix, null, List.of(), false);
        }

        ActionTemplate withFirstInTakt() {
            return new ActionTemplate(actionType, name, deviceType, durationSeconds, true, isAnchor, syncWith, onlyOnePerTakt, deviceIndex, independentAcrossContainers, containerSuffix, dependsOn, eventGates, skipWhenGatesSatisfied);
        }

        ActionTemplate withAnchor() {
            return new ActionTemplate(actionType, name, deviceType, durationSeconds, firstInTakt, true, syncWith, onlyOnePerTakt, deviceIndex, independentAcrossContainers, containerSuffix, dependsOn, eventGates, skipWhenGatesSatisfied);
        }

        ActionTemplate withSyncWith(DeviceType type, ActionType refActionType) {
            return new ActionTemplate(actionType, name, deviceType, durationSeconds, firstInTakt, isAnchor, new SyncRef(type, refActionType), onlyOnePerTakt, deviceIndex, independentAcrossContainers, containerSuffix, dependsOn, eventGates, skipWhenGatesSatisfied);
        }

        ActionTemplate withOnlyOnePerTakt() {
            return new ActionTemplate(actionType, name, deviceType, durationSeconds, firstInTakt, isAnchor, syncWith, true, deviceIndex, independentAcrossContainers, containerSuffix, dependsOn, eventGates, skipWhenGatesSatisfied);
        }

        ActionTemplate withDeviceIndex(int deviceIndex) {
            return new ActionTemplate(actionType, name, deviceType, durationSeconds, firstInTakt, isAnchor, syncWith, onlyOnePerTakt, deviceIndex, independentAcrossContainers, containerSuffix, dependsOn, eventGates, skipWhenGatesSatisfied);
        }

        ActionTemplate withIndependentAcrossContainers() {
            return new ActionTemplate(actionType, name, deviceType, durationSeconds, firstInTakt, isAnchor, syncWith, onlyOnePerTakt, deviceIndex, true, containerSuffix, dependsOn, eventGates, skipWhenGatesSatisfied);
        }

        /**
         * Creates a copy of this template with a different duration.
         */
        public ActionTemplate withDuration(int newDurationSeconds) {
            return new ActionTemplate(actionType, name, deviceType, newDurationSeconds, firstInTakt, isAnchor, syncWith, onlyOnePerTakt, deviceIndex, independentAcrossContainers, containerSuffix, dependsOn, eventGates, skipWhenGatesSatisfied);
        }

        ActionTemplate withDependsOn(DeviceType type, ActionType refActionType) {
            return new ActionTemplate(actionType, name, deviceType, durationSeconds, firstInTakt, isAnchor, syncWith, onlyOnePerTakt, deviceIndex, independentAcrossContainers, containerSuffix, new SyncRef(type, refActionType), eventGates, skipWhenGatesSatisfied);
        }

        ActionTemplate withEventGate(DeviceType sourceDeviceType, ActionType sourceActionType, String requiredEventType) {
            var gate = new EventGate(sourceDeviceType, sourceActionType, requiredEventType, 0);
            var newGates = new java.util.ArrayList<>(eventGates);
            newGates.add(gate);
            return new ActionTemplate(actionType, name, deviceType, durationSeconds, firstInTakt, isAnchor, syncWith, onlyOnePerTakt, deviceIndex, independentAcrossContainers, containerSuffix, dependsOn, List.copyOf(newGates), skipWhenGatesSatisfied);
        }

        /**
         * Adds an event gate scoped to a specific container suffix (1-based).
         * When containerSuffix > 0, the gate is only satisfied by events from that specific WI.
         */
        ActionTemplate withEventGate(DeviceType sourceDeviceType, ActionType sourceActionType, String requiredEventType, int gateContainerSuffix) {
            var gate = new EventGate(sourceDeviceType, sourceActionType, requiredEventType, gateContainerSuffix);
            var newGates = new java.util.ArrayList<>(eventGates);
            newGates.add(gate);
            return new ActionTemplate(actionType, name, deviceType, durationSeconds, firstInTakt, isAnchor, syncWith, onlyOnePerTakt, deviceIndex, independentAcrossContainers, containerSuffix, dependsOn, List.copyOf(newGates), skipWhenGatesSatisfied);
        }

        /**
         * Marks this action to be auto-completed (skipped) when all its event gates are already
         * satisfied at activation time. When gates are NOT yet satisfied, the action activates
         * normally. This enables conditional actions: the action only executes when the gates
         * have not been satisfied yet (e.g., drive to buffer only if QC discharge hasn't completed).
         */
        ActionTemplate withSkipWhenGatesSatisfied() {
            return new ActionTemplate(actionType, name, deviceType, durationSeconds, firstInTakt, isAnchor, syncWith, onlyOnePerTakt, deviceIndex, independentAcrossContainers, containerSuffix, dependsOn, eventGates, true);
        }
    }

    public record SyncRef(DeviceType deviceType, ActionType actionType) {}

    /**
     * @param containerSuffix 0 = any WI satisfies, 1+ = only the Nth WI satisfies this gate
     */
    public record EventGate(DeviceType sourceDeviceType, ActionType sourceActionType, String requiredEventType, int containerSuffix) {}

    // ── Segment: group of actions that go into the same takt ───────────

    record Segment(
            DeviceType deviceType,
            List<ActionTemplate> templates,
            boolean isAnchor,
            SyncRef syncRef,
            int deviceIndex
    ) {}

    // ── Placed action: tracks the mapping from template to created Action ──

    private record PlacedAction(ActionTemplate template, Action action, int containerIndex, int blueprintOrder) {}

    // ── Blueprint ──────────────────────────────────────────────────────

    List<ActionTemplate> buildContainerBlueprint(WorkInstructionEvent wi, HashMap<Long, WorkInstructionEvent> workInstructionHashMap, int qcMudaSeconds, LoadMode loadMode) {
        int qcLiftDuration = 20;
        int rtgPlaceDuration = 20;
        int driveToUnderRtg = 30;
        int driveToRtgPull = 30;//driveTimeSupplier.getAsInt();
        int driveToQcPull = 170;Math.clamp(
                driveToRtgPull + qcDriveTimeOffsetSupplier.getAsInt(),
                DRIVE_TIME_MIN_SECONDS, DRIVE_TIME_MAX_SECONDS);

        return switch (loadMode) {
            case LOAD -> getLoadSingleTemplate(wi, qcLiftDuration, driveToRtgPull, driveToUnderRtg, rtgPlaceDuration, driveToQcPull);
            case DSCH -> {
                if (!wi.isTwinCarry()){
                    yield getDischargeSingleTemplate(wi, qcLiftDuration, driveToRtgPull, driveToUnderRtg, rtgPlaceDuration, driveToQcPull);
                }
                // Twin companion not in this work queue — fall back to discharge twin (lift twin, put twin)
                else if (!hasCompanionInQueue(wi, workInstructionHashMap)) {
                    yield getDischargeTwinTemplate(wi, qcLiftDuration, driveToRtgPull, driveToUnderRtg, rtgPlaceDuration, driveToQcPull);
                }
                //pick as twin, drop as singles in the same bay
                else if (wi.isTwinFetch() && !wi.isTwinPut() && !isDifferentBay(wi, workInstructionHashMap)) {
                    yield getDischargeLiftTwinsDropSinglesSameBay(wi, qcLiftDuration, driveToRtgPull, driveToUnderRtg, rtgPlaceDuration, driveToQcPull);
                }
                //pick as twin, drop as singles in different bay
                else if (wi.isTwinFetch() && !wi.isTwinPut() && isDifferentBay(wi, workInstructionHashMap)) {
                    yield getDischargeLiftTwinsDropSinglesDifferentBay(wi, qcLiftDuration, driveToRtgPull, driveToUnderRtg, rtgPlaceDuration, driveToQcPull);
                }
                //pick as twin, drop as twin
                else if (wi.isTwinFetch() && wi.isTwinPut()) {
                    yield getDischargeTwinTemplate(wi, qcLiftDuration, driveToRtgPull, driveToUnderRtg, rtgPlaceDuration, driveToQcPull);
                }
                //pick as singles, drop as singles in the same bay
                else if (!wi.isTwinFetch() && !wi.isTwinPut() && !isDifferentBay(wi, workInstructionHashMap)) {
                    yield getDischargeLiftSinglesDropSinglesSameBay(wi, qcLiftDuration, driveToRtgPull, driveToUnderRtg, rtgPlaceDuration, driveToQcPull);
                }
                //pick as singles, drop as singles in a different bay
                else if (!wi.isTwinFetch() && !wi.isTwinPut() && isDifferentBay(wi, workInstructionHashMap)) {
                    yield getDischargeLiftSinglesDropSinglesDifferentBay(wi, qcLiftDuration, driveToRtgPull, driveToUnderRtg, rtgPlaceDuration, driveToQcPull);
                }
                //pick as singles, drop as twin
                else if (!wi.isTwinFetch() && wi.isTwinPut()) {
                    yield getDischargeLiftSinglesDropTwin(wi, qcLiftDuration, driveToRtgPull, driveToUnderRtg, rtgPlaceDuration, driveToQcPull);
                }
                else {
                    throw new RuntimeException("Unsupported twin fetch");
                }
            }
        };
    }

    private List<ActionTemplate> getDischargeLiftSinglesDropTwin(WorkInstructionEvent wi, int qcLiftDuration, int driveToRtgPull, int driveToUnderRtg, int rtgPlaceDuration, int driveToQcPull) {
        return List.of(
                // ── QC chain (forward from anchor) ──
                ActionTemplate.of(QC_LIFT, 1, QC, wi.estimatedCycleTimeSeconds() - qcLiftDuration),
                ActionTemplate.of(QC_PLACE, 1, QC, qcLiftDuration).withFirstInTakt().withAnchor(),
                ActionTemplate.of(QC_LIFT, 2, QC, wi.estimatedCycleTimeSeconds() - qcLiftDuration),
                ActionTemplate.of(QC_PLACE, 2, QC, qcLiftDuration).withFirstInTakt(),

                // ── TT chain (backward from sync point) ──
                ActionTemplate.of(TT_DRIVE_TO_QC_PULL, TT, 30).withIndependentAcrossContainers(),
                ActionTemplate.of(TT_DRIVE_TO_QC_STANDBY, TT, driveToQcPull),
                ActionTemplate.of(TT_DRIVE_UNDER_QC, TT, 30),
                ActionTemplate.of(TT_HANDOVER_FROM_QC, 1, TT, qcLiftDuration)
                        .withFirstInTakt().withSyncWith(QC, QC_PLACE),
                ActionTemplate.of(TT_HANDOVER_FROM_QC, 2, TT, qcLiftDuration)
                        .withFirstInTakt().withSyncWith(QC, QC_PLACE),

                ActionTemplate.of(TT_DRIVE_TO_BUFFER, TT, TT_DRIVE_TO_BUFFER_SECONDS)
                        .withEventGate(QC, QC_LIFT, EventType.QC_DISCHARGED_CONTAINER)
                        .withSkipWhenGatesSatisfied(),

                ActionTemplate.of(TT_DRIVE_TO_RTG_PULL, TT, driveToRtgPull),
                ActionTemplate.of(TT_DRIVE_TO_RTG_STANDBY, TT, 240),
                ActionTemplate.of(TT_DRIVE_TO_RTG_UNDER, TT, driveToUnderRtg),
                ActionTemplate.of(TT_HANDOVER_TO_RTG, TT, rtgPlaceDuration),
                ActionTemplate.of(TT_DRIVE_TO_BUFFER, TT, TT_DRIVE_TO_BUFFER_SECONDS)
                        .withSkipWhenGatesSatisfied(),

                // ── RTG chain (backward from sync point) ──
                ActionTemplate.of(RTG_DRIVE, RTG, RTG_DRIVE_SECONDS)
                        .withFirstInTakt()
                        .withSyncWith(TT, TT_DRIVE_TO_RTG_PULL)
                        .withDependsOn(QC, QC_PLACE)
                        .withEventGate(QC, QC_LIFT, EventType.QC_DISCHARGED_CONTAINER),
                ActionTemplate.of(RTG_WAIT_FOR_TRUCK,  RTG, 0),
                ActionTemplate.of(RTG_LIFT_FROM_TT,  RTG, rtgPlaceDuration),
                ActionTemplate.of(RTG_PLACE_ON_YARD,  RTG, driveToUnderRtg + rtgPlaceDuration)
        );
    }

    private List<ActionTemplate> getDischargeLiftSinglesDropSinglesDifferentBay(WorkInstructionEvent wi, int qcLiftDuration, int driveToRtgPull, int driveToUnderRtg, int rtgPlaceDuration, int driveToQcPull) {
        return List.of(
                // ── QC chain (forward from anchor) ──
                ActionTemplate.of(QC_LIFT, 1, QC, wi.estimatedCycleTimeSeconds() - qcLiftDuration),
                ActionTemplate.of(QC_PLACE, 1, QC, qcLiftDuration).withFirstInTakt().withAnchor(),
                ActionTemplate.of(QC_LIFT, 2, QC, wi.estimatedCycleTimeSeconds() - qcLiftDuration),
                ActionTemplate.of(QC_PLACE, 2, QC, qcLiftDuration).withFirstInTakt(),


                // ── TT chain (backward from sync point) ──
                ActionTemplate.of(TT_DRIVE_TO_QC_PULL, TT, 30).withIndependentAcrossContainers(),
                ActionTemplate.of(TT_DRIVE_TO_QC_STANDBY, TT, driveToQcPull),
                ActionTemplate.of(TT_DRIVE_UNDER_QC, TT, 30),
                ActionTemplate.of(TT_HANDOVER_FROM_QC, 1, TT, qcLiftDuration)
                        .withFirstInTakt().withSyncWith(QC, QC_PLACE),
                ActionTemplate.of(TT_HANDOVER_FROM_QC, 2, TT, qcLiftDuration)
                        .withFirstInTakt().withSyncWith(QC, QC_PLACE),

                ActionTemplate.of(TT_DRIVE_TO_BUFFER, TT, TT_DRIVE_TO_BUFFER_SECONDS)
                        .withEventGate(QC, QC_LIFT, EventType.QC_DISCHARGED_CONTAINER)
                        .withSkipWhenGatesSatisfied(),

                ActionTemplate.of(TT_DRIVE_TO_RTG_PULL, 1, TT, driveToRtgPull),
                ActionTemplate.of(TT_DRIVE_TO_RTG_STANDBY, 1, TT, 240),
                ActionTemplate.of(TT_DRIVE_TO_RTG_UNDER, 1, TT, driveToUnderRtg),
                ActionTemplate.of(TT_HANDOVER_TO_RTG, 1, TT, rtgPlaceDuration),
                ActionTemplate.of(TT_DRIVE_TO_RTG_PULL, 2,TT, 30),
                ActionTemplate.of(TT_DRIVE_TO_RTG_STANDBY, 2, TT, 30),
                ActionTemplate.of(TT_DRIVE_TO_RTG_UNDER, 2, TT, 30),
                ActionTemplate.of(TT_HANDOVER_TO_RTG, 2, TT, rtgPlaceDuration),
                ActionTemplate.of(TT_DRIVE_TO_BUFFER, TT, TT_DRIVE_TO_BUFFER_SECONDS)
                        .withSkipWhenGatesSatisfied(),

                // ── RTG chain (backward from sync point) ──
                ActionTemplate.of(RTG_DRIVE, 1, RTG, RTG_DRIVE_SECONDS)
                        .withFirstInTakt()
                        .withSyncWith(TT, TT_DRIVE_TO_RTG_PULL)
                        .withDeviceIndex(1)
                        .withDependsOn(QC, QC_PLACE)
                        .withEventGate(QC, QC_LIFT, EventType.QC_DISCHARGED_CONTAINER),
                ActionTemplate.of(RTG_WAIT_FOR_TRUCK, 1, RTG, 0).withDeviceIndex(1),
                ActionTemplate.of(RTG_LIFT_FROM_TT, 1, RTG, rtgPlaceDuration).withDeviceIndex(1),
                ActionTemplate.of(RTG_PLACE_ON_YARD, 1, RTG, driveToUnderRtg + rtgPlaceDuration).withDeviceIndex(1),
                ActionTemplate.of(RTG_DRIVE, 2, RTG, RTG_DRIVE_SECONDS)
                        .withFirstInTakt()
                        .withSyncWith(TT, TT_DRIVE_TO_RTG_PULL)
                        .withDeviceIndex(2).withDependsOn(QC, QC_PLACE)
                        .withEventGate(QC, QC_LIFT, EventType.QC_DISCHARGED_CONTAINER),
                ActionTemplate.of(RTG_WAIT_FOR_TRUCK, 2, RTG, 0).withDeviceIndex(2),
                ActionTemplate.of(RTG_LIFT_FROM_TT, 2, RTG, rtgPlaceDuration).withDeviceIndex(2),
                ActionTemplate.of(RTG_PLACE_ON_YARD, 2, RTG, driveToUnderRtg + rtgPlaceDuration).withDeviceIndex(2)
        );
    }

    private List<ActionTemplate> getDischargeLiftSinglesDropSinglesSameBay(WorkInstructionEvent wi, int qcLiftDuration, int driveToRtgPull, int driveToUnderRtg, int rtgPlaceDuration, int driveToQcPull) {
        return List.of(
                // ── QC chain (forward from anchor) ──
                ActionTemplate.of(QC_LIFT, 1, QC, wi.estimatedCycleTimeSeconds() - qcLiftDuration),
                ActionTemplate.of(QC_PLACE, 1, QC, qcLiftDuration).withFirstInTakt().withAnchor(),
                ActionTemplate.of(QC_LIFT, 2, QC, wi.estimatedCycleTimeSeconds() - qcLiftDuration),
                ActionTemplate.of(QC_PLACE, 2, QC, qcLiftDuration).withFirstInTakt(),


                // ── TT chain (backward from sync point) ──
                ActionTemplate.of(TT_DRIVE_TO_QC_PULL, TT, 30).withIndependentAcrossContainers(),
                ActionTemplate.of(TT_DRIVE_TO_QC_STANDBY, TT, driveToQcPull),
                ActionTemplate.of(TT_DRIVE_UNDER_QC, TT, 30),
                ActionTemplate.of(TT_HANDOVER_FROM_QC, 1, TT, qcLiftDuration)
                        .withFirstInTakt().withSyncWith(QC, QC_PLACE),
                ActionTemplate.of(TT_HANDOVER_FROM_QC, 2, TT, qcLiftDuration)
                        .withFirstInTakt().withSyncWith(QC, QC_PLACE),

                ActionTemplate.of(TT_DRIVE_TO_BUFFER, TT, TT_DRIVE_TO_BUFFER_SECONDS)
                        .withEventGate(QC, QC_LIFT, EventType.QC_DISCHARGED_CONTAINER)
                        .withSkipWhenGatesSatisfied(),

                ActionTemplate.of(TT_DRIVE_TO_RTG_PULL, TT, driveToRtgPull),
                ActionTemplate.of(TT_DRIVE_TO_RTG_STANDBY, TT, 240),
                ActionTemplate.of(TT_DRIVE_TO_RTG_UNDER, TT, driveToUnderRtg),
                ActionTemplate.of(TT_HANDOVER_TO_RTG, 1, TT, rtgPlaceDuration),
                ActionTemplate.of(TT_HANDOVER_TO_RTG, 2, TT, rtgPlaceDuration),
                ActionTemplate.of(TT_DRIVE_TO_BUFFER, TT, TT_DRIVE_TO_BUFFER_SECONDS)
                        .withSkipWhenGatesSatisfied(),

                // ── RTG chain (backward from sync point) ──
                ActionTemplate.of(RTG_DRIVE, 1, RTG, RTG_DRIVE_SECONDS)
                        .withFirstInTakt()
                        .withSyncWith(TT, TT_DRIVE_TO_RTG_PULL)
                        .withDependsOn(QC, QC_PLACE)
                        .withEventGate(QC, QC_LIFT, EventType.QC_DISCHARGED_CONTAINER),
                ActionTemplate.of(RTG_WAIT_FOR_TRUCK, 1, RTG, 0),
                ActionTemplate.of(RTG_LIFT_FROM_TT, 1, RTG, rtgPlaceDuration),
                ActionTemplate.of(RTG_PLACE_ON_YARD, 1, RTG, driveToUnderRtg + rtgPlaceDuration),
                ActionTemplate.of(RTG_DRIVE, 2, RTG, RTG_DRIVE_SECONDS).withDependsOn(QC, QC_PLACE),
                ActionTemplate.of(RTG_LIFT_FROM_TT, 2, RTG, rtgPlaceDuration),
                ActionTemplate.of(RTG_PLACE_ON_YARD, 2, RTG, driveToUnderRtg + rtgPlaceDuration)
        );
    }

    private List<ActionTemplate> getDischargeLiftTwinsDropSinglesDifferentBay(WorkInstructionEvent wi, int qcLiftDuration, int driveToRtgPull, int driveToUnderRtg, int rtgPlaceDuration, int driveToQcPull) {
        return List.of(
                // ── QC chain (forward from anchor) ──
                ActionTemplate.of(QC_LIFT, QC, wi.estimatedCycleTimeSeconds() - qcLiftDuration),
                ActionTemplate.of(QC_PLACE, QC, qcLiftDuration)
                        .withFirstInTakt().withAnchor(),

                // ── TT chain (backward from sync point) ──
                ActionTemplate.of(TT_DRIVE_TO_QC_PULL, TT, 30).withIndependentAcrossContainers(),
                ActionTemplate.of(TT_DRIVE_TO_QC_STANDBY, TT, driveToQcPull),
                ActionTemplate.of(TT_DRIVE_UNDER_QC, TT, 30),
                ActionTemplate.of(TT_HANDOVER_FROM_QC, TT, qcLiftDuration)
                        .withFirstInTakt().withSyncWith(QC, QC_PLACE),

                ActionTemplate.of(TT_DRIVE_TO_BUFFER, TT, TT_DRIVE_TO_BUFFER_SECONDS)
                        .withEventGate(QC, QC_LIFT, EventType.QC_DISCHARGED_CONTAINER)
                        .withSkipWhenGatesSatisfied(),

                ActionTemplate.of(TT_DRIVE_TO_RTG_PULL, 1, TT, driveToRtgPull),
                ActionTemplate.of(TT_DRIVE_TO_RTG_STANDBY, 1, TT, 240),
                ActionTemplate.of(TT_DRIVE_TO_RTG_UNDER, 1, TT, driveToUnderRtg),
                ActionTemplate.of(TT_HANDOVER_TO_RTG, 1, TT, rtgPlaceDuration),
                ActionTemplate.of(TT_DRIVE_TO_RTG_PULL, 2, TT, driveToRtgPull),
                ActionTemplate.of(TT_DRIVE_TO_RTG_STANDBY, 2, TT, 30),
                ActionTemplate.of(TT_DRIVE_TO_RTG_UNDER, 2, TT, driveToUnderRtg),

                ActionTemplate.of(TT_HANDOVER_TO_RTG, 2, TT, rtgPlaceDuration),
                ActionTemplate.of(TT_DRIVE_TO_BUFFER, TT, TT_DRIVE_TO_BUFFER_SECONDS)
                        .withSkipWhenGatesSatisfied(),

                // ── RTG chain (backward from sync point) ──
                ActionTemplate.of(RTG_DRIVE, 1, RTG, RTG_DRIVE_SECONDS)
                        .withFirstInTakt().
                        withSyncWith(TT, TT_DRIVE_TO_RTG_PULL)
                        .withDeviceIndex(1)
                        .withDependsOn(QC, QC_PLACE)
                        .withEventGate(QC, QC_LIFT, EventType.QC_DISCHARGED_CONTAINER),
                ActionTemplate.of(RTG_WAIT_FOR_TRUCK, 1, RTG, 0).withDeviceIndex(1),
                ActionTemplate.of(RTG_LIFT_FROM_TT, 1, RTG, rtgPlaceDuration).withDeviceIndex(1),
                ActionTemplate.of(RTG_PLACE_ON_YARD, 1, RTG, driveToUnderRtg + rtgPlaceDuration).withDeviceIndex(1),
                ActionTemplate.of(RTG_DRIVE, 2, RTG, RTG_DRIVE_SECONDS)
                        .withFirstInTakt()
                        .withSyncWith(TT, TT_DRIVE_TO_RTG_PULL)
                        .withDeviceIndex(2)
                        .withDependsOn(QC, QC_PLACE)
                        .withEventGate(QC, QC_LIFT, EventType.QC_DISCHARGED_CONTAINER)
                ,
                ActionTemplate.of(RTG_WAIT_FOR_TRUCK, 2, RTG, 0).withDeviceIndex(2),
                ActionTemplate.of(RTG_LIFT_FROM_TT, 2, RTG, rtgPlaceDuration).withDeviceIndex(2),
                ActionTemplate.of(RTG_PLACE_ON_YARD, 2, RTG, driveToUnderRtg + rtgPlaceDuration).withDeviceIndex(2)
        );
    }

    private List<ActionTemplate> getDischargeLiftTwinsDropSinglesSameBay(WorkInstructionEvent wi, int qcLiftDuration, int driveToRtgPull, int driveToUnderRtg, int rtgPlaceDuration, int driveToQcPull) {
        return List.of(
                // ── QC chain (forward from anchor) ──
                ActionTemplate.of(QC_LIFT, QC, wi.estimatedCycleTimeSeconds() - qcLiftDuration),
                ActionTemplate.of(QC_PLACE, QC, qcLiftDuration ).withFirstInTakt().withAnchor(),

                // ── TT chain (backward from sync point) ──
                ActionTemplate.of(TT_DRIVE_TO_QC_PULL, TT, 30).withIndependentAcrossContainers(),
                ActionTemplate.of(TT_DRIVE_TO_QC_STANDBY, TT, driveToQcPull),
                ActionTemplate.of(TT_DRIVE_UNDER_QC, TT, 30),
                ActionTemplate.of(TT_HANDOVER_FROM_QC, TT, qcLiftDuration)
                        .withFirstInTakt().withSyncWith(QC, QC_PLACE),

                ActionTemplate.of(TT_DRIVE_TO_BUFFER, TT, TT_DRIVE_TO_BUFFER_SECONDS)
                        .withEventGate(QC, QC_LIFT, EventType.QC_DISCHARGED_CONTAINER)
                        .withSkipWhenGatesSatisfied(),

                ActionTemplate.of(TT_DRIVE_TO_RTG_PULL, TT, driveToRtgPull),
                ActionTemplate.of(TT_DRIVE_TO_RTG_STANDBY, TT, 240),
                ActionTemplate.of(TT_DRIVE_TO_RTG_UNDER, TT, driveToUnderRtg),
                ActionTemplate.of(TT_HANDOVER_TO_RTG, 1, TT, rtgPlaceDuration),
                ActionTemplate.of(TT_HANDOVER_TO_RTG, 2, TT, rtgPlaceDuration),
                ActionTemplate.of(TT_DRIVE_TO_BUFFER, TT, TT_DRIVE_TO_BUFFER_SECONDS)
                        .withSkipWhenGatesSatisfied(),

                // ── RTG chain (backward from sync point) ──
                ActionTemplate.of(RTG_DRIVE, 1, RTG, RTG_DRIVE_SECONDS)
                        .withFirstInTakt()
                        .withSyncWith(TT, TT_DRIVE_TO_RTG_PULL)
                        .withDependsOn(QC, QC_PLACE)
                        .withEventGate(QC, QC_LIFT, EventType.QC_DISCHARGED_CONTAINER),
                ActionTemplate.of(RTG_WAIT_FOR_TRUCK, 1, RTG, 0),
                ActionTemplate.of(RTG_LIFT_FROM_TT, 1, RTG, rtgPlaceDuration),
                ActionTemplate.of(RTG_PLACE_ON_YARD, 1, RTG, driveToUnderRtg + rtgPlaceDuration),
                ActionTemplate.of(RTG_DRIVE, 2, RTG, RTG_DRIVE_SECONDS)
                        .withEventGate(QC, QC_LIFT, EventType.QC_DISCHARGED_CONTAINER),
                ActionTemplate.of(RTG_LIFT_FROM_TT, 2, RTG, rtgPlaceDuration),
                ActionTemplate.of(RTG_PLACE_ON_YARD, 2, RTG, driveToUnderRtg + rtgPlaceDuration)
        );
    }

    private static boolean hasCompanionInQueue(WorkInstructionEvent wi, HashMap<Long, WorkInstructionEvent> workInstructionHashMap) {
        if (wi.twinCompanionWorkInstruction() < 1) return false;
        return workInstructionHashMap.containsKey(wi.twinCompanionWorkInstruction());
    }

    private boolean isDifferentBay(WorkInstructionEvent wi, HashMap<Long, WorkInstructionEvent> workInstructionHashMap) {
        if (wi.twinCompanionWorkInstruction() < 1) return false;

        WorkInstructionEvent companion = workInstructionHashMap.get(wi.twinCompanionWorkInstruction());
        if (companion == null) return false;

        YardLocation yardLocation = YardLocation.parse(wi.toPosition());
        YardLocation companionYardLocation = YardLocation.parse(companion.toPosition());

        if (yardLocation == null || companionYardLocation == null) return false;

        return !yardLocation.bay().equals(companionYardLocation.bay());
    }

    private static List<ActionTemplate> getDischargeTwinTemplate(WorkInstructionEvent wi, int qcLiftDuration, int driveToRtgPull, int driveToUnderRtg, int rtgPlaceDuration, int driveToQcPull) {
        return List.of(
                // ── QC chain (forward from anchor) ──
                ActionTemplate.of(QC_LIFT, QC, wi.estimatedCycleTimeSeconds() - qcLiftDuration),
                ActionTemplate.of(QC_PLACE, QC,qcLiftDuration ).withFirstInTakt().withAnchor(),

                // ── TT chain (backward from sync point) ──
                ActionTemplate.of(TT_DRIVE_TO_QC_PULL, TT, 30).withIndependentAcrossContainers(),
                ActionTemplate.of(TT_DRIVE_TO_QC_STANDBY, TT, driveToQcPull),
                ActionTemplate.of(TT_DRIVE_UNDER_QC, TT, 30),
                ActionTemplate.of(TT_HANDOVER_FROM_QC, TT, qcLiftDuration)
                        .withFirstInTakt().withSyncWith(QC, QC_PLACE),

                ActionTemplate.of(TT_DRIVE_TO_BUFFER, TT, TT_DRIVE_TO_BUFFER_SECONDS)
                        .withEventGate(QC, QC_LIFT, EventType.QC_DISCHARGED_CONTAINER, 1)
                        .withEventGate(QC, QC_LIFT, EventType.QC_DISCHARGED_CONTAINER, 2)
                        .withSkipWhenGatesSatisfied(),

                ActionTemplate.of(TT_DRIVE_TO_RTG_PULL, TT, driveToRtgPull),
                ActionTemplate.of(TT_DRIVE_TO_RTG_STANDBY, TT, 240),
                ActionTemplate.of(TT_DRIVE_TO_RTG_UNDER, TT, driveToUnderRtg),
                ActionTemplate.of(TT_HANDOVER_TO_RTG, TT, rtgPlaceDuration),
                ActionTemplate.of(TT_DRIVE_TO_BUFFER, TT, TT_DRIVE_TO_BUFFER_SECONDS)
                        .withSkipWhenGatesSatisfied(),

                // ── RTG chain (backward from sync point) ──
                ActionTemplate.of(RTG_DRIVE,  RTG, RTG_DRIVE_SECONDS)
                        .withFirstInTakt()
                        .withSyncWith(TT, TT_DRIVE_TO_RTG_PULL)
                        .withDependsOn(QC, QC_PLACE)
                        .withEventGate(QC, QC_LIFT, EventType.QC_DISCHARGED_CONTAINER, 1)
                        .withEventGate(QC, QC_LIFT, EventType.QC_DISCHARGED_CONTAINER, 2),
                ActionTemplate.of(RTG_WAIT_FOR_TRUCK,  RTG, 0),
                ActionTemplate.of(RTG_LIFT_FROM_TT,  RTG, rtgPlaceDuration),
                ActionTemplate.of(RTG_PLACE_ON_YARD, RTG, driveToUnderRtg + rtgPlaceDuration)
        );
    }

    private static List<ActionTemplate> getDischargeSingleTemplate(WorkInstructionEvent wi, int qcLiftDuration, int driveToRtgPull, int driveToUnderRtg, int rtgPlaceDuration, int driveToQcPull) {
        return List.of(
                // ── QC chain (forward from anchor) ──
                ActionTemplate.of(QC_LIFT, QC, wi.estimatedCycleTimeSeconds() - qcLiftDuration),
                ActionTemplate.of(QC_PLACE, QC, qcLiftDuration).withFirstInTakt().withAnchor(),

                // ── TT chain (backward from sync point) ──
                ActionTemplate.of(TT_DRIVE_TO_QC_PULL, TT, driveToQcPull).withIndependentAcrossContainers(),
                ActionTemplate.of(TT_DRIVE_TO_QC_STANDBY, TT, 30),
                ActionTemplate.of(TT_DRIVE_UNDER_QC, TT, 30),
                ActionTemplate.of(TT_HANDOVER_FROM_QC, TT, qcLiftDuration)
                        .withFirstInTakt().withSyncWith(QC, QC_PLACE),

                ActionTemplate.of(TT_DRIVE_TO_BUFFER, TT, TT_DRIVE_TO_BUFFER_SECONDS)
                        .withEventGate(QC, QC_LIFT, EventType.QC_DISCHARGED_CONTAINER)
                        .withSkipWhenGatesSatisfied(),

                ActionTemplate.of(TT_DRIVE_TO_RTG_PULL, TT, driveToRtgPull),
                ActionTemplate.of(TT_DRIVE_TO_RTG_STANDBY, TT, 240),
                ActionTemplate.of(TT_DRIVE_TO_RTG_UNDER, TT, driveToUnderRtg),
                ActionTemplate.of(TT_HANDOVER_TO_RTG, TT, rtgPlaceDuration),
                ActionTemplate.of(TT_DRIVE_TO_BUFFER, TT, TT_DRIVE_TO_BUFFER_SECONDS)
                        .withSkipWhenGatesSatisfied(),

                // ── RTG chain (backward from sync point) ──
                ActionTemplate.of(RTG_DRIVE,  RTG, RTG_DRIVE_SECONDS)
                        .withFirstInTakt()
                        .withSyncWith(TT, TT_DRIVE_TO_RTG_PULL)
                        .withDependsOn(QC, QC_PLACE)
                        .withEventGate(QC, QC_LIFT, EventType.QC_DISCHARGED_CONTAINER),
                ActionTemplate.of(RTG_WAIT_FOR_TRUCK,  RTG, 0),
                ActionTemplate.of(RTG_LIFT_FROM_TT,  RTG, rtgPlaceDuration),
                ActionTemplate.of(RTG_PLACE_ON_YARD, RTG, driveToUnderRtg + rtgPlaceDuration)
        );
    }

    private static List<ActionTemplate> getLoadSingleTemplate(WorkInstructionEvent wi, int qcLiftDuration, int driveToRtgPull, int driveToUnderRtg, int rtgPlaceDuration, int driveToQcPull) {
        return List.of(
                // ── QC chain (forward from anchor) ──
                ActionTemplate.of(QC_LIFT, QC, wi.estimatedCycleTimeSeconds() - qcLiftDuration)
                        .withFirstInTakt().withAnchor(),
                ActionTemplate.of(QC_PLACE, QC, qcLiftDuration),

                // ── TT chain (backward from sync point) ──
                ActionTemplate.of(TT_DRIVE_TO_RTG_PULL, TT, driveToRtgPull).withIndependentAcrossContainers(),
                ActionTemplate.of(TT_DRIVE_TO_RTG_STANDBY, TT, 240),
                ActionTemplate.of(TT_DRIVE_TO_RTG_UNDER, TT, driveToUnderRtg)
                        .withFirstInTakt().withOnlyOnePerTakt(),
                ActionTemplate.of(TT_HANDOVER_FROM_RTG, TT, rtgPlaceDuration)
                        .withOnlyOnePerTakt(),
                ActionTemplate.of(TT_DRIVE_TO_QC_PULL, TT, driveToQcPull),
                ActionTemplate.of(TT_DRIVE_TO_QC_STANDBY, TT, 30),
                ActionTemplate.of(TT_DRIVE_UNDER_QC, TT, 30),
                ActionTemplate.of(TT_HANDOVER_TO_QC, TT, qcLiftDuration)
                        .withFirstInTakt().withSyncWith(QC, QC_LIFT),
                ActionTemplate.of(TT_DRIVE_TO_BUFFER, TT, TT_DRIVE_TO_BUFFER_SECONDS)
                        .withSkipWhenGatesSatisfied(),

                // ── RTG chain (backward from sync point) ──
                ActionTemplate.of(RTG_DRIVE, RTG, 1),
                ActionTemplate.of(RTG_FETCH, RTG,
                        (wi.estimatedRtgCycleTimeSeconds() - rtgPlaceDuration)),
                ActionTemplate.of(RTG_HANDOVER_TO_TT, RTG, driveToUnderRtg + rtgPlaceDuration)
                        .withFirstInTakt().withSyncWith(TT, TT_HANDOVER_FROM_RTG)
        );
    }



    // ── Public entry point ─────────────────────────────────────────────

    public List<Takt> createTakts(List<WorkInstructionEvent> instructions, Instant estimatedMoveTime, int qcMudaSeconds, LoadMode loadMode) {
        var takts = new HashMap<Integer, Takt>();
        // Ordered list of all placed actions across all containers, in blueprint order per container
        var allPlacedActions = new ArrayList<PlacedAction>();

        // Sort by estimated move time, then deduplicate twin pairs by companion ID
        var sorted = instructions.stream()
                .sorted(Comparator.comparing(WorkInstructionEvent::estimatedMoveTime))
                .toList();

        var processedTwinIds = new HashSet<Long>();
        int containerIdx = 0;

        // Index WIs by ID for twin companion lookup
        var wiById = new HashMap<Long, WorkInstructionEvent>();
        for (var wi : sorted) {
            wiById.put(wi.workInstructionId(), wi);
        }

        for (var wi : sorted) {
            // Skip twin companion that was already processed as part of its pair
            if (isTwinDischarge(wi, loadMode) && processedTwinIds.contains(wi.workInstructionId())) {
                continue;
            }

            // Build the list of WIs for this action — twin pairs include both WIs
            List<WorkInstructionEvent> actionWis;
            if (isTwinDischarge(wi, loadMode) && wi.twinCompanionWorkInstruction() != 0) {
                var companion = wiById.get(wi.twinCompanionWorkInstruction());
                actionWis = companion != null ? List.of(wi, companion) : List.of(wi);
                processedTwinIds.add(wi.twinCompanionWorkInstruction());
            } else {
                actionWis = List.of(wi);
            }

            var blueprint = buildContainerBlueprint(wi, wiById, qcMudaSeconds, loadMode);
            var placed = placeContainerActions(blueprint, containerIdx, actionWis, qcMudaSeconds, takts);
            allPlacedActions.addAll(placed);

            containerIdx++;
        }

        // Wire dependencies as a post-processing step
        wireDependencies(allPlacedActions);

        return takts.values().stream()
                .sorted(Comparator.comparingInt(Takt::sequence))
                .toList();
    }

    /**
     * Creates takts with pipeline steps applied between template creation and takt fitting.
     *
     * <p>Pipeline steps are executed in order for each container's blueprint, allowing
     * external processors (e.g., DigitalMapProcessor) to modify action durations before
     * the placement algorithm fits actions into takts.
     *
     * @param instructions the work instructions
     * @param estimatedMoveTime the earliest estimated move time
     * @param qcMudaSeconds QC muda time in seconds
     * @param loadMode the load mode (LOAD or DSCH)
     * @param workQueueId the work queue ID (passed to pipeline steps)
     * @param pipelineSteps the pipeline steps to apply to each container's blueprint
     * @return the list of takts with actions fitted
     */
    public List<Takt> createTakts(List<WorkInstructionEvent> instructions, Instant estimatedMoveTime,
                                   int qcMudaSeconds, LoadMode loadMode,
                                   long workQueueId, List<SchedulePipelineStep> pipelineSteps) {
        if (pipelineSteps == null || pipelineSteps.isEmpty()) {
            return createTakts(instructions, estimatedMoveTime, qcMudaSeconds, loadMode);
        }

        var takts = new HashMap<Integer, Takt>();
        var allPlacedActions = new ArrayList<PlacedAction>();

        var sorted = instructions.stream()
                .sorted(Comparator.comparing(WorkInstructionEvent::estimatedMoveTime))
                .toList();

        var processedTwinIds = new HashSet<Long>();
        int containerIdx = 0;

        var wiById = new HashMap<Long, WorkInstructionEvent>();
        for (var wi : sorted) {
            wiById.put(wi.workInstructionId(), wi);
        }

        for (var wi : sorted) {
            if (isTwinDischarge(wi, loadMode) && processedTwinIds.contains(wi.workInstructionId())) {
                continue;
            }

            List<WorkInstructionEvent> actionWis;
            if (isTwinDischarge(wi, loadMode) && wi.twinCompanionWorkInstruction() != 0) {
                var companion = wiById.get(wi.twinCompanionWorkInstruction());
                actionWis = companion != null ? List.of(wi, companion) : List.of(wi);
                processedTwinIds.add(wi.twinCompanionWorkInstruction());
            } else {
                actionWis = List.of(wi);
            }

            // Step 1: Build templates
            var blueprint = buildContainerBlueprint(wi, wiById, qcMudaSeconds, loadMode);

            // Step 2: Run pipeline steps to enrich templates (e.g., adjust durations from digital map)
            for (var step : pipelineSteps) {
                blueprint = step.enrichTemplates(workQueueId, blueprint, wi);
            }

            // Step 3: Fit into takts
            var placed = placeContainerActions(blueprint, containerIdx, actionWis, qcMudaSeconds, takts);
            allPlacedActions.addAll(placed);

            containerIdx++;
        }

        wireDependencies(allPlacedActions);

        return takts.values().stream()
                .sorted(Comparator.comparingInt(Takt::sequence))
                .toList();
    }

    private static boolean isTwinDischarge(WorkInstructionEvent wi, LoadMode loadMode) {
        return loadMode == LoadMode.DSCH && wi.isTwinCarry();
    }

    /**
     * Filters work instructions based on the 1-based container suffix.
     * Suffix 0 means all work instructions, suffix 1 means only the first, suffix 2 only the second, etc.
     */
    private static List<WorkInstructionEvent> filterWorkInstructions(List<WorkInstructionEvent> workInstructions, int containerSuffix) {
        if (containerSuffix <= 0 || workInstructions.size() <= 1) {
            return workInstructions;
        }
        int index = containerSuffix - 1;
        if (index >= workInstructions.size()) {
            return workInstructions;
        }
        return List.of(workInstructions.get(index));
    }

    // ── Placement algorithm (determines takt assignment, creates Actions without deps) ──

    /**
     * Bundles the mutable state used during segment placement for a single container.
     * Provides generic forward/backward chain placement and sync resolution,
     * eliminating the duplicated placement loops from the original algorithm.
     */
    private class PlacementContext {
        final int containerIndex;
        final Map<Integer, Takt> takts;
        final Map<String, Integer> placementIndex;
        final List<PlacedAction> placedActions;
        final Map<ActionTemplate, Integer> blueprintOrder;
        final List<WorkInstructionEvent> workInstructions;
        final List<Segment> remaining;

        PlacementContext(int containerIndex, Map<Integer, Takt> takts,
                         List<ActionTemplate> blueprint, List<WorkInstructionEvent> workInstructions) {
            this.containerIndex = containerIndex;
            this.takts = takts;
            this.placementIndex = new HashMap<>();
            this.placedActions = new ArrayList<>();
            this.blueprintOrder = new HashMap<>();
            for (int i = 0; i < blueprint.size(); i++) {
                this.blueprintOrder.put(blueprint.get(i), i);
            }
            this.workInstructions = workInstructions;
            this.remaining = new ArrayList<>();
        }

        /** Places a segment's actions into a single takt WITHOUT wiring dependencies. */
        void place(Segment segment, int taktIndex) {
            var takt = takts.get(taktIndex);
            for (var tmpl : segment.templates()) {
                var actionWis = filterWorkInstructions(workInstructions, tmpl.containerSuffix());
                var gates = tmpl.eventGates().stream()
                        .map(g -> new EventGateCondition(g.sourceDeviceType(), g.sourceActionType(),
                                g.requiredEventType(), g.containerSuffix()))
                        .toList();
                var action = new Action(UUID.randomUUID(), segment.deviceType(), tmpl.actionType(), tmpl.name(),
                        new HashSet<>(), containerIndex, tmpl.durationSeconds(), tmpl.deviceIndex(), actionWis, gates, tmpl.skipWhenGatesSatisfied());
                takt.actions().add(action);
                placedActions.add(new PlacedAction(tmpl, action, containerIndex, blueprintOrder.getOrDefault(tmpl, 0)));
                placementIndex.put(placementKey(containerIndex, tmpl.deviceType(), tmpl.name()), taktIndex);
            }
        }

        /**
         * Places a synced segment: resolves device exclusivity, relocates sync source
         * actions if pushed forward, and places the segment.
         * @return the final takt index where the segment was placed
         */
        int placeSyncedSegment(Segment segment, int targetTakt) {
            ensureTaktExists(takts, targetTakt, computeTaktStartTime(targetTakt, takts), DEFAULT_TAKT_DURATION);
            int originalTakt = targetTakt;
            targetTakt = pushForwardForDeviceExclusivity(targetTakt, segment.deviceType(),
                    segment.deviceIndex(), containerIndex, takts);
            if (targetTakt != originalTakt && segment.syncRef() != null) {
                relocateSyncSourceActions(originalTakt, targetTakt,
                        segment.syncRef().deviceType(), containerIndex, takts, placementIndex);
            }
            place(segment, targetTakt);
            return targetTakt;
        }

        /**
         * Places segments forward (increasing takt indices) from a pivot point,
         * stopping at the first segment with a syncRef.
         * Each segment is pushed forward until the previous chain finishes before it starts,
         * and checked for onlyOnePerTakt conflicts.
         */
        void placeForwardChain(List<Segment> deviceSegs, int fromIdx, int startTakt,
                               int startDuration, int newTaktDuration) {
            int prevTakt = startTakt;
            int prevDuration = startDuration;
            for (int i = fromIdx + 1; i < deviceSegs.size(); i++) {
                var seg = deviceSegs.get(i);
                if (seg.syncRef() != null) break;
                if (!remaining.contains(seg)) continue;
                int candidate = prevTakt + 1;
                ensureTaktExists(takts, candidate, computeTaktStartTime(candidate, takts), newTaktDuration);
                candidate = pushForwardUntilFits(candidate, prevDuration, takts.get(prevTakt), takts);
                candidate = resolveOverflow(seg, candidate, takts);
                place(seg, candidate);
                remaining.remove(seg);
                prevTakt = candidate;
                prevDuration = segmentDuration(seg);
            }
        }

        /**
         * Places segments backward (decreasing takt indices) from a pivot point.
         * Each segment is checked for onlyOnePerTakt conflicts and pushed back
         * until its chain duration fits within the target takt's time window.
         */
        void placeBackwardChain(List<Segment> deviceSegs, int fromIdx, int startTakt) {
            int backTakt = startTakt;
            for (int i = fromIdx - 1; i >= 0; i--) {
                var seg = deviceSegs.get(i);
                if (!remaining.contains(seg)) continue;
                backTakt--;
                ensureTaktExists(takts, backTakt, computeTaktStartTime(backTakt, takts), DEFAULT_TAKT_DURATION);
                backTakt = resolveOverflow(seg, backTakt, takts);
                backTakt = pushBackUntilFits(backTakt, segmentDuration(seg), takts);
                place(seg, backTakt);
                remaining.remove(seg);
            }
        }
    }

    private List<PlacedAction> placeContainerActions(
            List<ActionTemplate> blueprint,
            int containerIndex,
            List<WorkInstructionEvent> workInstructions,
            int qcMudaSeconds,
            Map<Integer, Takt> takts
    ) {
        var wi = workInstructions.getFirst();
        var segmentsByDevice = buildSegmentsByDevice(blueprint);
        var ctx = new PlacementContext(containerIndex, takts, blueprint, workInstructions);

        // Step 1: Place anchor segment
        Segment anchorSegment = findAnchorSegment(segmentsByDevice);
        var anchorDeviceSegments = segmentsByDevice.getOrDefault(anchorSegment.deviceType(), List.of());
        int anchorTaktIndex;
        if (containerIndex == 0) {
            anchorTaktIndex = 0;
        } else {
            int maxAnchorDeviceTakt = findMaxTaktForDevice(takts, anchorSegment.deviceType());
            int numBackwardSegments = anchorDeviceSegments.indexOf(anchorSegment);
            anchorTaktIndex = maxAnchorDeviceTakt + numBackwardSegments;
        }
        int anchorTaktDuration = wi.estimatedCycleTimeSeconds() + qcMudaSeconds;
        ensureTaktExists(takts, anchorTaktIndex, computeAnchorStartTime(anchorTaktIndex, wi, takts), anchorTaktDuration);
        ctx.place(anchorSegment, anchorTaktIndex);

        // Step 2: Populate remaining and place anchor device chains
        segmentsByDevice.values().forEach(segs -> segs.stream()
                .filter(s -> s != anchorSegment)
                .forEach(ctx.remaining::add));

        int anchorIdx = anchorDeviceSegments.indexOf(anchorSegment);
        ctx.placeForwardChain(anchorDeviceSegments, anchorIdx, anchorTaktIndex,
                segmentDuration(anchorSegment), anchorTaktDuration);
        ctx.placeBackwardChain(anchorDeviceSegments, anchorIdx, anchorTaktIndex);

        // Step 3: Iteratively resolve sync-based segments and their device chains
        boolean progress = true;
        while (!ctx.remaining.isEmpty() && progress) {
            progress = false;
            for (var seg : ctx.remaining) {
                Integer targetTakt = resolveSyncTarget(seg, containerIndex, ctx.placementIndex);
                if (targetTakt != null) {
                    int placedTakt = ctx.placeSyncedSegment(seg, targetTakt);
                    ctx.remaining.remove(seg);
                    progress = true;

                    var deviceSegs = segmentsByDevice.getOrDefault(seg.deviceType(), List.of());
                    int syncIdx = deviceSegs.indexOf(seg);
                    ctx.placeBackwardChain(deviceSegs, syncIdx, placedTakt);
                    ctx.placeForwardChain(deviceSegs, syncIdx, placedTakt,
                            segmentDuration(seg), DEFAULT_TAKT_DURATION);

                    break; // restart iteration
                }
            }
        }

        return ctx.placedActions;
    }

    // ── Dependency wiring (post-processing) ────────────────────────────

    /**
     * Wires dependencies based on blueprint execution order:
     * <ul>
     *   <li>Each action depends on the previous action of the same device within the same container</li>
     *   <li>The first action of a device for container N depends on the last action of that device for container N-1</li>
     * </ul>
     */
    private void wireDependencies(List<PlacedAction> allPlacedActions) {
        // Sort by containerIndex first, then by blueprintOrder within each container
        allPlacedActions.sort(Comparator.comparingInt(PlacedAction::containerIndex)
                .thenComparingInt(PlacedAction::blueprintOrder));

        // Group by (containerIndex, deviceType, deviceIndex) — now in correct execution order
        // deviceIndex distinguishes independent physical devices (e.g., RTG-A vs RTG-B in different-bay twin carry)
        var byContainerDevice = new LinkedHashMap<String, List<PlacedAction>>();
        for (var pa : allPlacedActions) {
            String key = pa.containerIndex() + ":" + pa.action().deviceType() + ":" + pa.action().deviceIndex();
            byContainerDevice.computeIfAbsent(key, k -> new ArrayList<>()).add(pa);
        }

        // Track the last action per device instance across containers for cross-container chaining
        var lastActionByDevice = new HashMap<String, Action>();

        // Process containers in order (containerIndex 0, 1, 2...) and within each, per device instance
        var processedDevices = new HashSet<String>();
        for (var pa : allPlacedActions) {
            String key = pa.containerIndex() + ":" + pa.action().deviceType() + ":" + pa.action().deviceIndex();
            if (processedDevices.contains(key)) continue;
            processedDevices.add(key);

            var chain = byContainerDevice.get(key);
            Action prev = null;
            for (var placed : chain) {
                if (prev != null) {
                    placed.action().dependsOn().add(prev.id());
                } else {
                    // First action in this container's device chain — link to previous container
                    if (!placed.template().independentAcrossContainers()) {
                        String deviceKey = placed.action().deviceType() + ":" + placed.action().deviceIndex();
                        Action crossContainerPrev = lastActionByDevice.get(deviceKey);
                        if (crossContainerPrev != null) {
                            placed.action().dependsOn().add(crossContainerPrev.id());
                        }
                    }
                }
                prev = placed.action();
            }
            // Update cross-container tracking
            if (prev != null) {
                String deviceKey = prev.deviceType() + ":" + prev.deviceIndex();
                lastActionByDevice.put(deviceKey, prev);
            }
        }

        // Wire cross-device dependencies declared via withDependsOn
        for (var pa : allPlacedActions) {
            if (pa.template().dependsOn() != null) {
                var ref = pa.template().dependsOn();
                allPlacedActions.stream()
                        .filter(other -> other.containerIndex() == pa.containerIndex()
                                && other.action().deviceType() == ref.deviceType()
                                && other.template().actionType() == ref.actionType())
                        .findFirst()
                        .ifPresent(dep -> pa.action().dependsOn().add(dep.action().id()));
            }
        }
    }

    // ── Segment building ───────────────────────────────────────────────

    Map<DeviceType, List<Segment>> buildSegmentsByDevice(List<ActionTemplate> blueprint) {
        var result = new LinkedHashMap<DeviceType, List<Segment>>();
        var byDevice = new LinkedHashMap<DeviceType, List<ActionTemplate>>();
        for (var tmpl : blueprint) {
            byDevice.computeIfAbsent(tmpl.deviceType(), k -> new ArrayList<>()).add(tmpl);
        }

        for (var entry : byDevice.entrySet()) {
            var segments = new ArrayList<Segment>();
            var current = new ArrayList<ActionTemplate>();
            boolean currentIsAnchor = false;
            SyncRef currentSync = null;
            int currentDeviceIndex = -1;

            for (var tmpl : entry.getValue()) {
                boolean deviceIndexChanged = currentDeviceIndex >= 0 && tmpl.deviceIndex() != currentDeviceIndex;
                if ((tmpl.firstInTakt() || deviceIndexChanged) && !current.isEmpty()) {
                    segments.add(new Segment(entry.getKey(), List.copyOf(current), currentIsAnchor, currentSync, currentDeviceIndex));
                    current.clear();
                    currentIsAnchor = false;
                    currentSync = null;
                }
                current.add(tmpl);
                currentDeviceIndex = tmpl.deviceIndex();
                if (tmpl.isAnchor()) currentIsAnchor = true;
                if (tmpl.syncWith() != null) currentSync = tmpl.syncWith();
            }
            if (!current.isEmpty()) {
                segments.add(new Segment(entry.getKey(), List.copyOf(current), currentIsAnchor, currentSync, currentDeviceIndex));
            }
            result.put(entry.getKey(), segments);
        }
        return result;
    }

    /**
     * Finds the highest takt index that contains an action of the given device type.
     * Returns -1 if no such takt exists.
     */
    /**
     * Finds the highest takt index that contains the anchor action type.
     * This ensures proper spacing when placing consecutive containers.
     */
    private int findMaxTaktForAction(Map<Integer, Takt> takts, Segment anchorSegment) {
        int max = -1;
        var anchorActionTypes = anchorSegment.templates().stream()
                .map(ActionTemplate::actionType)
                .collect(java.util.stream.Collectors.toSet());
        for (var entry : takts.entrySet()) {
            for (var action : entry.getValue().actions()) {
                if (anchorActionTypes.contains(action.actionType())) {
                    max = Math.max(max, entry.getKey());
                    break;
                }
            }
        }
        return max;
    }

    private int findMaxTaktForDevice(Map<Integer, Takt> takts, DeviceType deviceType) {
        int max = -1;
        for (var entry : takts.entrySet()) {
            for (var action : entry.getValue().actions()) {
                if (action.deviceType() == deviceType) {
                    max = Math.max(max, entry.getKey());
                    break;
                }
            }
        }
        return max;
    }

    private Segment findAnchorSegment(Map<DeviceType, List<Segment>> segmentsByDevice) {
        return segmentsByDevice.values().stream()
                .flatMap(List::stream)
                .filter(Segment::isAnchor)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Blueprint must have exactly one anchor"));
    }

    // ── Sync and overflow resolution ───────────────────────────────────

    private Integer resolveSyncTarget(Segment segment, int containerIndex, Map<String, Integer> placementIndex) {
        if (segment.syncRef() == null) return null;
        String baseDisplayName = segment.syncRef().actionType().displayName();

        // First try exact match (no suffix)
        String key = placementKey(containerIndex, segment.syncRef().deviceType(), baseDisplayName);
        Integer result = placementIndex.get(key);
        if (result != null) return result;

        // If the segment's first template has a suffix, apply it to the sync target lookup.
        // E.g., RTG_LIFT_FROM_TT "1" syncing with TT_HANDOVER_TO_RTG should find "handover to RTG1".
        ActionTemplate firstTemplate = segment.templates().getFirst();
        String segmentName = firstTemplate.name();
        String segmentBaseName = firstTemplate.actionType().displayName();
        if (segmentName.length() > segmentBaseName.length()) {
            String suffix = segmentName.substring(segmentBaseName.length());
            String suffixedKey = placementKey(containerIndex, segment.syncRef().deviceType(),
                    baseDisplayName + suffix);
            return placementIndex.get(suffixedKey);
        }

        return null;
    }

    private static String placementKey(int containerIndex, DeviceType deviceType, String actionName) {
        return containerIndex + ":" + deviceType + ":" + actionName;
    }

    /**
     * If there's a onlyOnePerTakt conflict (another container's action with the same name
     * already exists in the takt), shift backward to an earlier takt.
     *
     * <p>Duration-based overflow is handled by the backward placement step which calculates
     * the number of takts to step back based on the previous segment's duration.
     */
    private int resolveOverflow(Segment segment, int targetTakt, Map<Integer, Takt> takts) {
        var takt = takts.get(targetTakt);
        var onlyOneNames = new HashSet<String>();
        for (var tmpl : segment.templates()) {
            if (tmpl.onlyOnePerTakt()) onlyOneNames.add(tmpl.name());
        }

        int maxShifts = 20;
        int shifts = 0;
        while (shifts < maxShifts && hasOnlyOnePerTaktConflict(takt, onlyOneNames)) {
            targetTakt--;
            shifts++;
            ensureTaktExists(takts, targetTakt, computeTaktStartTime(targetTakt, takts), DEFAULT_TAKT_DURATION);
            takt = takts.get(targetTakt);
        }
        return targetTakt;
    }

    /**
     * Pushes a chain to an earlier takt if its duration overflows the target takt's time window.
     * The chain (placed at PULSEx) must finish before the original target takt ends:
     * {@code PULSEx.start + chainDuration <= target.start + target.duration}.
     */
    /**
     * Pushes a segment forward to a later takt until the previous segment's chain
     * has finished before this takt starts.
     * {@code prevTakt.start + prevChainDuration <= candidate.start}
     */
    private int pushForwardUntilFits(int candidateTakt, int prevChainDuration,
                                      Takt prevTakt, Map<Integer, Takt> takts) {
        Instant prevChainEnd = prevTakt.plannedStartTime().plusSeconds(prevChainDuration);
        for (int shifts = 0; shifts < 50; shifts++) {
            Takt candidate = takts.get(candidateTakt);
            if (!candidate.plannedStartTime().isBefore(prevChainEnd)) {
                return candidateTakt;
            }
            candidateTakt++;
            ensureTaktExists(takts, candidateTakt, computeTaktStartTime(candidateTakt, takts), DEFAULT_TAKT_DURATION);
        }
        return candidateTakt;
    }

    private int pushBackUntilFits(int targetTakt, int chainDuration, Map<Integer, Takt> takts) {
        Takt target = takts.get(targetTakt);
        Instant deadline = target.plannedStartTime().plusSeconds(target.durationSeconds());

        int placeTakt = targetTakt;
        for (int shifts = 0; shifts < 50; shifts++) {
            Takt place = takts.get(placeTakt);
            Instant chainEnd = place.plannedStartTime().plusSeconds(chainDuration);
            if (!chainEnd.isAfter(deadline)) {
                return placeTakt;
            }
            placeTakt--;
            ensureTaktExists(takts, placeTakt, computeTaktStartTime(placeTakt, takts), DEFAULT_TAKT_DURATION);
        }
        return placeTakt;
    }

    /**
     * Pushes a segment forward to the next takt if the target takt already contains
     * actions of the same device type and device index from a different container.
     * Actions on different physical devices (different deviceIndex) can share a takt.
     */
    private int pushForwardForDeviceExclusivity(int targetTakt, DeviceType deviceType, int deviceIndex,
                                                 int containerIndex, Map<Integer, Takt> takts) {
        for (int shifts = 0; shifts < 20; shifts++) {
            var takt = takts.get(targetTakt);
            boolean hasOtherContainerDevice = takt.actions().stream()
                    .anyMatch(a -> a.deviceType() == deviceType && a.deviceIndex() == deviceIndex
                            && a.containerIndex() != containerIndex);
            if (!hasOtherContainerDevice) {
                return targetTakt;
            }
            targetTakt++;
            ensureTaktExists(takts, targetTakt, computeTaktStartTime(targetTakt, takts), DEFAULT_TAKT_DURATION);
        }
        return targetTakt;
    }

    /**
     * Relocates actions of the sync source device type from one takt to another.
     * Called when device exclusivity pushes a synced segment forward — the sync source
     * actions (e.g., TT handover) must move with the synced device (e.g., RTG lift).
     * If the destination takt already has actions of the same device type for this container,
     * those pre-existing actions are pushed forward to the next takt to avoid merging segments.
     */
    private void relocateSyncSourceActions(int fromTakt, int toTakt,
                                            DeviceType syncSourceDeviceType, int containerIndex,
                                            Map<Integer, Takt> takts, Map<String, Integer> placementIndex) {
        var sourceTakt = takts.get(fromTakt);
        var destTakt = takts.get(toTakt);

        var toMove = sourceTakt.actions().stream()
                .filter(a -> a.containerIndex() == containerIndex && a.deviceType() == syncSourceDeviceType)
                .toList();

        if (toMove.isEmpty()) return;

        // Push pre-existing actions of the same device type for this container to the next takt
        var toPush = destTakt.actions().stream()
                .filter(a -> a.containerIndex() == containerIndex && a.deviceType() == syncSourceDeviceType)
                .toList();

        if (!toPush.isEmpty()) {
            int nextTakt = toTakt + 1;
            ensureTaktExists(takts, nextTakt, computeTaktStartTime(nextTakt, takts), DEFAULT_TAKT_DURATION);
            destTakt.actions().removeAll(toPush);
            takts.get(nextTakt).actions().addAll(toPush);
            for (var action : toPush) {
                placementIndex.put(placementKey(containerIndex, action.deviceType(), action.description()), nextTakt);
            }
        }

        sourceTakt.actions().removeAll(toMove);
        destTakt.actions().addAll(toMove);

        for (var action : toMove) {
            placementIndex.put(placementKey(containerIndex, action.deviceType(), action.description()), toTakt);
        }
    }

    private static int segmentDuration(Segment segment) {
        return segment.templates().stream().mapToInt(ActionTemplate::durationSeconds).sum();
    }

    private int taktDurationAt(int taktIndex, Map<Integer, Takt> takts) {
        var takt = takts.get(taktIndex);
        return takt != null ? takt.durationSeconds() : DEFAULT_TAKT_DURATION;
    }

    private boolean hasOnlyOnePerTaktConflict(Takt takt, Set<String> onlyOneNames) {
        if (onlyOneNames.isEmpty()) return false;
        return takt.actions().stream()
                .anyMatch(a -> onlyOneNames.contains(a.description()));
    }

    // ── Takt management helpers ────────────────────────────────────────

    private Instant computeAnchorStartTime(int anchorTaktIndex, WorkInstructionEvent wi, Map<Integer, Takt> takts) {
        var prevTakt = takts.get(anchorTaktIndex - 1);
        if (prevTakt != null) {
            return prevTakt.plannedStartTime().plusSeconds(prevTakt.durationSeconds());
        }
        return wi.estimatedMoveTime();
    }

    private Instant computeTaktStartTime(int taktIndex, Map<Integer, Takt> takts) {
        // Search forward for the nearest existing takt
        for (int i = taktIndex + 1; i <= taktIndex + 50; i++) {
            var t = takts.get(i);
            if (t != null) {
                return t.plannedStartTime().plusSeconds((long) -(i - taktIndex) * DEFAULT_TAKT_DURATION);
            }
        }
        // Search backward for the nearest existing takt
        for (int i = taktIndex - 1; i >= taktIndex - 50; i--) {
            var t = takts.get(i);
            if (t != null) {
                return t.plannedStartTime().plusSeconds((long) (taktIndex - i) * t.durationSeconds());
            }
        }
        return takts.values().stream()
                .map(Takt::plannedStartTime)
                .min(Instant::compareTo)
                .orElse(Instant.EPOCH);
    }

    private void ensureTaktExists(Map<Integer, Takt> takts, int taktIndex, Instant startTime, int duration) {
        takts.computeIfAbsent(taktIndex,
                idx -> new Takt(idx, new ArrayList<>(), startTime, startTime, duration));
    }

}
