package com.wonderingwizard.processors;

import com.wonderingwizard.domain.YardLocation;
import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.ActionType;
import com.wonderingwizard.domain.takt.DeviceType;
import com.wonderingwizard.domain.takt.Takt;
import com.wonderingwizard.events.LoadMode;
import com.wonderingwizard.sideeffects.WorkInstruction;

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
            int deviceIndex
    ) {
        static ActionTemplate of(ActionType actionType, DeviceType type, int duration) {
            return new ActionTemplate(actionType, actionType.displayName(), type, duration, false, false, null, false, 0);
        }

        static ActionTemplate of(ActionType actionType, String suffix, DeviceType type, int duration) {
            return new ActionTemplate(actionType, actionType.displayName(suffix), type, duration, false, false, null, false, 0);
        }

        ActionTemplate withFirstInTakt() {
            return new ActionTemplate(actionType, name, deviceType, durationSeconds, true, isAnchor, syncWith, onlyOnePerTakt, deviceIndex);
        }

        ActionTemplate withAnchor() {
            return new ActionTemplate(actionType, name, deviceType, durationSeconds, firstInTakt, true, syncWith, onlyOnePerTakt, deviceIndex);
        }

        ActionTemplate withSyncWith(DeviceType type, ActionType refActionType) {
            return new ActionTemplate(actionType, name, deviceType, durationSeconds, firstInTakt, isAnchor, new SyncRef(type, refActionType), onlyOnePerTakt, deviceIndex);
        }

        ActionTemplate withOnlyOnePerTakt() {
            return new ActionTemplate(actionType, name, deviceType, durationSeconds, firstInTakt, isAnchor, syncWith, true, deviceIndex);
        }

        ActionTemplate withDeviceIndex(int deviceIndex) {
            return new ActionTemplate(actionType, name, deviceType, durationSeconds, firstInTakt, isAnchor, syncWith, onlyOnePerTakt, deviceIndex);
        }
    }

    public record SyncRef(DeviceType deviceType, ActionType actionType) {}

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

    List<ActionTemplate> buildContainerBlueprint(WorkInstruction wi, HashMap<Long, WorkInstruction> workInstructionHashMap, int qcMudaSeconds, LoadMode loadMode) {
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
                if (!wi.isTwinCarry()) {
                    yield getDischargeSingleTemplate(wi, qcLiftDuration, driveToRtgPull, driveToUnderRtg, rtgPlaceDuration, driveToQcPull);
                }
                boolean differentBay = isDifferentBay(wi, workInstructionHashMap);
                yield getTwinDischargeTemplate(wi, wi.isTwinFetch(), wi.isTwinPut(), differentBay,
                        qcLiftDuration, driveToRtgPull, driveToUnderRtg, rtgPlaceDuration, driveToQcPull);
            }
        };
    }

    /**
     * Consolidated template for all twin-carry discharge variants.
     * The three dimensions (twinFetch, twinPut, differentBay) control how QC, TT, and RTG chains are built.
     */
    private List<ActionTemplate> getTwinDischargeTemplate(
            WorkInstruction wi, boolean twinFetch, boolean twinPut, boolean differentBay,
            int qcLiftDuration, int driveToRtgPull, int driveToUnderRtg, int rtgPlaceDuration, int driveToQcPull) {

        var templates = new ArrayList<ActionTemplate>();

        // ── QC chain ──
        if (twinFetch) {
            var lift = ActionTemplate.of(QC_LIFT, QC, wi.estimatedCycleTimeSeconds() - qcLiftDuration);
            var place = ActionTemplate.of(QC_PLACE, QC, qcLiftDuration).withFirstInTakt();
            if (!twinPut && !differentBay) {
                // Same-bay single drop: anchor on QC_LIFT
                templates.add(lift.withAnchor());
                templates.add(place);
            } else {
                // Twin drop or different bay: anchor on QC_PLACE
                templates.add(lift);
                templates.add(place.withAnchor());
            }
        } else {
            // Single fetch: two QC pairs with suffixes
            templates.add(ActionTemplate.of(QC_LIFT, "1", QC, wi.estimatedCycleTimeSeconds() - qcLiftDuration));
            templates.add(ActionTemplate.of(QC_PLACE, "1", QC, qcLiftDuration).withFirstInTakt().withAnchor());
            templates.add(ActionTemplate.of(QC_LIFT, "2", QC, wi.estimatedCycleTimeSeconds() - qcLiftDuration));
            templates.add(ActionTemplate.of(QC_PLACE, "2", QC, qcLiftDuration).withFirstInTakt());
        }

        // ── TT chain ──
        // Common QC-approach prefix
        templates.add(ActionTemplate.of(TT_DRIVE_TO_QC_PULL, TT, 30));
        templates.add(ActionTemplate.of(TT_DRIVE_TO_QC_STANDBY, TT, driveToQcPull));
        templates.add(ActionTemplate.of(TT_DRIVE_UNDER_QC, TT, 30));

        // QC handover
        if (twinFetch) {
            templates.add(ActionTemplate.of(TT_HANDOVER_FROM_QC, TT, qcLiftDuration)
                    .withFirstInTakt().withSyncWith(QC, QC_PLACE));
        } else {
            templates.add(ActionTemplate.of(TT_HANDOVER_FROM_QC, "1", TT, qcLiftDuration)
                    .withFirstInTakt().withSyncWith(QC, QC_PLACE));
            templates.add(ActionTemplate.of(TT_HANDOVER_FROM_QC, "2", TT, qcLiftDuration)
                    .withFirstInTakt().withSyncWith(QC, QC_PLACE));
        }

        // Drive to RTG + handover
        if (differentBay && !twinPut) {
            // Two separate drive-to-RTG sequences for different bays
            templates.add(ActionTemplate.of(TT_DRIVE_TO_RTG_PULL, "1", TT, driveToRtgPull));
            templates.add(ActionTemplate.of(TT_DRIVE_TO_RTG_STANDBY, "1", TT, 240));
            templates.add(ActionTemplate.of(TT_DRIVE_TO_RTG_UNDER, "1", TT, driveToUnderRtg));
            templates.add(ActionTemplate.of(TT_HANDOVER_TO_RTG, "1", TT, rtgPlaceDuration));
            templates.add(ActionTemplate.of(TT_DRIVE_TO_RTG_PULL, "2", TT, twinFetch ? driveToRtgPull : 30));
            templates.add(ActionTemplate.of(TT_DRIVE_TO_RTG_STANDBY, "2", TT, 30));
            templates.add(ActionTemplate.of(TT_DRIVE_TO_RTG_UNDER, "2", TT, twinFetch ? driveToUnderRtg : 30));
            templates.add(ActionTemplate.of(TT_HANDOVER_TO_RTG, "2", TT, rtgPlaceDuration));
        } else {
            // Single drive-to-RTG sequence
            templates.add(ActionTemplate.of(TT_DRIVE_TO_RTG_PULL, TT, driveToRtgPull));
            templates.add(ActionTemplate.of(TT_DRIVE_TO_RTG_STANDBY, TT, 240));
            templates.add(ActionTemplate.of(TT_DRIVE_TO_RTG_UNDER, TT, driveToUnderRtg));
            if (twinPut) {
                templates.add(ActionTemplate.of(TT_HANDOVER_TO_RTG, TT, rtgPlaceDuration));
            } else {
                templates.add(ActionTemplate.of(TT_HANDOVER_TO_RTG, "1", TT, rtgPlaceDuration));
                templates.add(ActionTemplate.of(TT_HANDOVER_TO_RTG, "2", TT, rtgPlaceDuration));
            }
        }
        templates.add(ActionTemplate.of(TT_DRIVE_TO_BUFFER, TT, TT_DRIVE_TO_BUFFER_SECONDS));

        // ── RTG chain ──
        if (differentBay && !twinPut) {
            // Two RTG device chains on separate physical devices
            templates.add(ActionTemplate.of(RTG_DRIVE, "1", RTG, RTG_DRIVE_SECONDS)
                    .withFirstInTakt().withSyncWith(TT, TT_DRIVE_TO_RTG_PULL).withDeviceIndex(1));
            templates.add(ActionTemplate.of(RTG_WAIT_FOR_TRUCK, "1", RTG, 0).withDeviceIndex(1));
            templates.add(ActionTemplate.of(RTG_LIFT_FROM_TT, "1", RTG, rtgPlaceDuration).withDeviceIndex(1));
            templates.add(ActionTemplate.of(RTG_PLACE_ON_YARD, "1", RTG, driveToUnderRtg + rtgPlaceDuration).withDeviceIndex(1));
            templates.add(ActionTemplate.of(RTG_DRIVE, "2", RTG, RTG_DRIVE_SECONDS)
                    .withFirstInTakt().withSyncWith(TT, TT_DRIVE_TO_RTG_PULL).withDeviceIndex(2));
            templates.add(ActionTemplate.of(RTG_WAIT_FOR_TRUCK, "2", RTG, 0).withDeviceIndex(2));
            templates.add(ActionTemplate.of(RTG_LIFT_FROM_TT, "2", RTG, rtgPlaceDuration).withDeviceIndex(2));
            templates.add(ActionTemplate.of(RTG_PLACE_ON_YARD, "2", RTG, driveToUnderRtg + rtgPlaceDuration).withDeviceIndex(2));
        } else if (twinPut) {
            // Twin put: single RTG set
            templates.add(ActionTemplate.of(RTG_DRIVE, "1", RTG, RTG_DRIVE_SECONDS)
                    .withFirstInTakt().withSyncWith(TT, TT_DRIVE_TO_RTG_PULL));
            templates.add(ActionTemplate.of(RTG_WAIT_FOR_TRUCK, "1", RTG, 0));
            templates.add(ActionTemplate.of(RTG_LIFT_FROM_TT, "1", RTG, rtgPlaceDuration));
            templates.add(ActionTemplate.of(RTG_PLACE_ON_YARD, "1", RTG, driveToUnderRtg + rtgPlaceDuration));
        } else {
            // Same bay, single put: two RTG sets on same device
            templates.add(ActionTemplate.of(RTG_DRIVE, "1", RTG, RTG_DRIVE_SECONDS)
                    .withFirstInTakt().withSyncWith(TT, TT_DRIVE_TO_RTG_PULL));
            templates.add(ActionTemplate.of(RTG_WAIT_FOR_TRUCK, "1", RTG, 0));
            templates.add(ActionTemplate.of(RTG_LIFT_FROM_TT, "1", RTG, rtgPlaceDuration));
            templates.add(ActionTemplate.of(RTG_PLACE_ON_YARD, "1", RTG, driveToUnderRtg + rtgPlaceDuration));
            templates.add(ActionTemplate.of(RTG_DRIVE, "2", RTG, RTG_DRIVE_SECONDS));
            templates.add(ActionTemplate.of(RTG_LIFT_FROM_TT, "2", RTG, rtgPlaceDuration));
            templates.add(ActionTemplate.of(RTG_PLACE_ON_YARD, "2", RTG, driveToUnderRtg + rtgPlaceDuration));
        }

        return withComputedRtgWaitDuration(templates);
    }

    private boolean isDifferentBay(WorkInstruction wi, HashMap<Long, WorkInstruction> workInstructionHashMap) {
        if (wi.twinCompanionWorkInstruction() < 1) return false;

        WorkInstruction companion = workInstructionHashMap.get(wi.twinCompanionWorkInstruction());
        if (companion == null) return false;

        YardLocation yardLocation = YardLocation.parse(wi.toPosition());
        YardLocation companionYardLocation = YardLocation.parse(companion.toPosition());

        if (yardLocation == null || companionYardLocation == null) return false;

        return !yardLocation.bay().equals(companionYardLocation.bay());
    }

    private static List<ActionTemplate> getDischargeSingleTemplate(WorkInstruction wi, int qcLiftDuration, int driveToRtgPull, int driveToUnderRtg, int rtgPlaceDuration, int driveToQcPull) {
        return List.of(
                // ── QC chain (forward from anchor) ──
                ActionTemplate.of(QC_LIFT, QC, wi.estimatedCycleTimeSeconds() - qcLiftDuration)
                        .withFirstInTakt().withAnchor(),
                ActionTemplate.of(QC_PLACE, QC, qcLiftDuration),

                // ── TT chain (backward from sync point) ──
                ActionTemplate.of(TT_DRIVE_TO_QC_PULL, TT, driveToQcPull),
                ActionTemplate.of(TT_DRIVE_TO_QC_STANDBY, TT, 30),
                ActionTemplate.of(TT_DRIVE_UNDER_QC, TT, 30),
                ActionTemplate.of(TT_HANDOVER_FROM_QC, TT, qcLiftDuration)
                        .withFirstInTakt().withSyncWith(QC, QC_PLACE),

                ActionTemplate.of(TT_DRIVE_TO_RTG_PULL, TT, driveToRtgPull),
                ActionTemplate.of(TT_DRIVE_TO_RTG_STANDBY, TT, 240),
                ActionTemplate.of(TT_DRIVE_TO_RTG_UNDER, TT, driveToUnderRtg)
                        .withFirstInTakt().withOnlyOnePerTakt(),
                ActionTemplate.of(TT_HANDOVER_TO_RTG, TT, rtgPlaceDuration)
                        .withOnlyOnePerTakt(),
                ActionTemplate.of(TT_DRIVE_TO_BUFFER, TT, TT_DRIVE_TO_BUFFER_SECONDS),

                // ── RTG chain (backward from sync point) ──
                ActionTemplate.of(RTG_DRIVE, RTG, 1),
                ActionTemplate.of(RTG_LIFT_FROM_TT, RTG,
                        (wi.estimatedRtgCycleTimeSeconds() - rtgPlaceDuration)).withFirstInTakt().withSyncWith(TT, TT_HANDOVER_TO_RTG),
                ActionTemplate.of(RTG_PLACE_ON_YARD, RTG, driveToUnderRtg + rtgPlaceDuration)
        );
    }

    private static List<ActionTemplate> getLoadSingleTemplate(WorkInstruction wi, int qcLiftDuration, int driveToRtgPull, int driveToUnderRtg, int rtgPlaceDuration, int driveToQcPull) {
        return List.of(
                // ── QC chain (forward from anchor) ──
                ActionTemplate.of(QC_LIFT, QC, wi.estimatedCycleTimeSeconds() - qcLiftDuration)
                        .withFirstInTakt().withAnchor(),
                ActionTemplate.of(QC_PLACE, QC, qcLiftDuration),

                // ── TT chain (backward from sync point) ──
                ActionTemplate.of(TT_DRIVE_TO_RTG_PULL, TT, driveToRtgPull),
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
                ActionTemplate.of(TT_DRIVE_TO_BUFFER, TT, TT_DRIVE_TO_BUFFER_SECONDS),

                // ── RTG chain (backward from sync point) ──
                ActionTemplate.of(RTG_DRIVE, RTG, 1),
                ActionTemplate.of(RTG_FETCH, RTG,
                        (wi.estimatedRtgCycleTimeSeconds() - rtgPlaceDuration)),
                ActionTemplate.of(RTG_HANDOVER_TO_TT, RTG, driveToUnderRtg + rtgPlaceDuration)
                        .withFirstInTakt().withSyncWith(TT, TT_HANDOVER_FROM_RTG)
        );
    }


    /**
     * Computes the RTG_WAIT_FOR_TRUCK duration as the sum of all TT action durations
     * between the last TT_HANDOVER_FROM_QC and the first TT_HANDOVER_TO_RTG.
     */
    private static List<ActionTemplate> withComputedRtgWaitDuration(List<ActionTemplate> templates) {
        List<ActionTemplate> ttActions = templates.stream()
                .filter(t -> t.deviceType() == TT)
                .toList();

        // Find last HANDOVER_FROM_QC index
        int fromQcIndex = -1;
        for (int i = 0; i < ttActions.size(); i++) {
            if (ttActions.get(i).actionType() == TT_HANDOVER_FROM_QC) {
                fromQcIndex = i;
            }
        }
        if (fromQcIndex == -1) return templates;

        // Find all HANDOVER_TO_RTG indices (in order)
        List<Integer> toRtgIndices = new ArrayList<>();
        for (int i = fromQcIndex + 1; i < ttActions.size(); i++) {
            if (ttActions.get(i).actionType() == TT_HANDOVER_TO_RTG) {
                toRtgIndices.add(i);
            }
        }
        if (toRtgIndices.isEmpty()) return templates;

        // Compute cumulative sum from HANDOVER_FROM_QC to each HANDOVER_TO_RTG
        List<Integer> waitDurations = new ArrayList<>();
        for (int toRtgIndex : toRtgIndices) {
            int sum = 0;
            for (int i = fromQcIndex; i < toRtgIndex; i++) {
                sum += ttActions.get(i).durationSeconds();
            }
            waitDurations.add(sum);
        }

        // Replace RTG_WAIT_FOR_TRUCK durations: 1st wait gets 1st duration, 2nd gets 2nd, etc.
        int[] waitIndex = {0};
        return templates.stream()
                .map(t -> {
                    if (t.actionType() == RTG_WAIT_FOR_TRUCK && waitIndex[0] < waitDurations.size()) {
                        int duration = waitDurations.get(waitIndex[0]++);
                        return new ActionTemplate(t.actionType(), t.name(), t.deviceType(), duration,
                                t.firstInTakt(), t.isAnchor(), t.syncWith(), t.onlyOnePerTakt(), t.deviceIndex());
                    }
                    return t;
                })
                .toList();
    }

    // ── Public entry point ─────────────────────────────────────────────

    public List<Takt> createTakts(List<WorkInstruction> instructions, Instant estimatedMoveTime, int qcMudaSeconds, LoadMode loadMode) {
        var takts = new HashMap<Integer, Takt>();
        // Ordered list of all placed actions across all containers, in blueprint order per container
        var allPlacedActions = new ArrayList<PlacedAction>();

        // Sort by estimated move time, then deduplicate twin pairs by companion ID
        var sorted = instructions.stream()
                .sorted(Comparator.comparing(WorkInstruction::estimatedMoveTime))
                .toList();

        var processedTwinIds = new HashSet<Long>();
        int containerIdx = 0;

        // Index WIs by ID for twin companion lookup
        var wiById = new HashMap<Long, WorkInstruction>();
        for (var wi : sorted) {
            wiById.put(wi.workInstructionId(), wi);
        }

        for (var wi : sorted) {
            // Skip twin companion that was already processed as part of its pair
            if (isTwinDischarge(wi, loadMode) && processedTwinIds.contains(wi.workInstructionId())) {
                continue;
            }

            // Build the list of WIs for this action — twin pairs include both WIs
            List<WorkInstruction> actionWis;
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

    private static boolean isTwinDischarge(WorkInstruction wi, LoadMode loadMode) {
        return loadMode == LoadMode.DSCH && wi.isTwinCarry();
    }

    // ── Placement algorithm (determines takt assignment, creates Actions without deps) ──

    private List<PlacedAction> placeContainerActions(
            List<ActionTemplate> blueprint,
            int containerIndex,
            List<WorkInstruction> workInstructions,
            int qcMudaSeconds,
            Map<Integer, Takt> takts
    ) {
        var wi = workInstructions.getFirst();
        var segmentsByDevice = buildSegmentsByDevice(blueprint);
        var placementIndex = new HashMap<String, Integer>(); // (containerIdx:device:name) → taktIndex
        var placedActions = new ArrayList<PlacedAction>();

        // Build blueprint order map: template → index in blueprint list
        var blueprintOrder = new HashMap<ActionTemplate, Integer>();
        for (int i = 0; i < blueprint.size(); i++) {
            blueprintOrder.put(blueprint.get(i), i);
        }

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

        Instant anchorStartTime = computeAnchorStartTime(anchorTaktIndex, wi, takts);
        int anchorTaktDuration = wi.estimatedCycleTimeSeconds() + qcMudaSeconds;

        ensureTaktExists(takts, anchorTaktIndex, anchorStartTime, anchorTaktDuration);
        placeSegment(anchorSegment, anchorTaktIndex, containerIndex, takts, placementIndex, placedActions, blueprintOrder, workInstructions);

        // Step 2: Place forward segments of the anchor device (e.g., if QC had more segments after anchor)
        var remaining = new ArrayList<Segment>();
        segmentsByDevice.values().forEach(segs -> segs.stream()
                .filter(s -> s != anchorSegment)
                .forEach(remaining::add));

        var deviceTaktCursor = new HashMap<DeviceType, Integer>();
        deviceTaktCursor.put(anchorSegment.deviceType(), anchorTaktIndex);

        // Forward segments of anchor device
        boolean foundAnchor = false;
        int forwardTakt = anchorTaktIndex;
        int prevAnchorSegDuration = segmentDuration(anchorSegment);
        for (var seg : anchorDeviceSegments) {
            if (seg.isAnchor()) { foundAnchor = true; continue; }
            if (foundAnchor && seg.syncRef() == null) {
                int prevTaktIdx = forwardTakt;
                forwardTakt++;
                ensureTaktExists(takts, forwardTakt,
                        computeTaktStartTime(forwardTakt, takts),
                        wi.estimatedCycleTimeSeconds() + qcMudaSeconds);
                forwardTakt = pushForwardUntilFits(forwardTakt, prevAnchorSegDuration,
                        takts.get(prevTaktIdx), takts);
                placeSegment(seg, forwardTakt, containerIndex, takts, placementIndex, placedActions, blueprintOrder, workInstructions);
                deviceTaktCursor.put(anchorSegment.deviceType(), forwardTakt);
                remaining.remove(seg);
                prevAnchorSegDuration = segmentDuration(seg);
            }
        }

        // Step 2b: Place backward segments of the anchor device (before the anchor)
        int anchorIdx = anchorDeviceSegments.indexOf(anchorSegment);
        int backTakt = anchorTaktIndex;
        for (int i = anchorIdx - 1; i >= 0; i--) {
            var backSeg = anchorDeviceSegments.get(i);
            backTakt--;
            ensureTaktExists(takts, backTakt,
                    computeTaktStartTime(backTakt, takts), DEFAULT_TAKT_DURATION);
            placeSegment(backSeg, backTakt, containerIndex, takts, placementIndex, placedActions, blueprintOrder, workInstructions);
            deviceTaktCursor.put(anchorSegment.deviceType(), backTakt);
            remaining.remove(backSeg);
        }

        // Step 3: Resolve sync-based segments and their backward chains
        boolean progress = true;
        while (!remaining.isEmpty() && progress) {
            progress = false;
            var it = remaining.iterator();
            while (it.hasNext()) {
                var seg = it.next();
                Integer targetTakt = resolveSyncTarget(seg, containerIndex, placementIndex);
                if (targetTakt != null) {
                    ensureTaktExists(takts, targetTakt,
                            computeTaktStartTime(targetTakt, takts), DEFAULT_TAKT_DURATION);
                    int originalSyncTakt = targetTakt;
                    targetTakt = pushForwardForDeviceExclusivity(targetTakt, seg.deviceType(), seg.deviceIndex(), containerIndex, takts);
                    if (targetTakt != originalSyncTakt && seg.syncRef() != null) {
                        relocateSyncSourceActions(originalSyncTakt, targetTakt,
                                seg.syncRef().deviceType(), containerIndex, takts, placementIndex);
                    }
                    placeSegment(seg, targetTakt, containerIndex, takts, placementIndex, placedActions, blueprintOrder, workInstructions);
                    deviceTaktCursor.put(seg.deviceType(), targetTakt);
                    it.remove();
                    progress = true;

                    // Place backward segments for this device.
                    // Each chain stays together in one takt but is pushed back to an
                    // earlier pulse if its duration overflows the target takt's time window.
                    var deviceSegs = segmentsByDevice.getOrDefault(seg.deviceType(), List.of());
                    int syncIdx = deviceSegs.indexOf(seg);
                    int backwardTakt = targetTakt;
                    for (int i = syncIdx - 1; i >= 0; i--) {
                        var backSeg = deviceSegs.get(i);
                        if (!remaining.contains(backSeg)) continue;
                        backwardTakt--;
                        ensureTaktExists(takts, backwardTakt,
                                computeTaktStartTime(backwardTakt, takts), DEFAULT_TAKT_DURATION);
                        backwardTakt = resolveOverflow(backSeg, backwardTakt, takts);
                        backwardTakt = pushBackUntilFits(backwardTakt, segmentDuration(backSeg), takts);
                        placeSegment(backSeg, backwardTakt, containerIndex, takts, placementIndex, placedActions, blueprintOrder, workInstructions);
                        deviceTaktCursor.put(backSeg.deviceType(), backwardTakt);
                        remaining.remove(backSeg);
                    }

                    // Place forward segments for this device (after the sync point).
                    // Each forward segment must start after the previous segment's chain has finished.
                    // Stop at segments that have their own syncRef — they will be placed by their own sync resolution.
                    int prevFwdTaktIdx = targetTakt;
                    int prevSegDuration = segmentDuration(seg);
                    for (int i = syncIdx + 1; i < deviceSegs.size(); i++) {
                        var fwdSeg = deviceSegs.get(i);
                        if (fwdSeg.syncRef() != null) break;
                        if (!remaining.contains(fwdSeg)) continue;
                        int forwardTaktIdx = prevFwdTaktIdx + 1;
                        ensureTaktExists(takts, forwardTaktIdx,
                                computeTaktStartTime(forwardTaktIdx, takts), DEFAULT_TAKT_DURATION);
                        forwardTaktIdx = pushForwardUntilFits(forwardTaktIdx, prevSegDuration,
                                takts.get(prevFwdTaktIdx), takts);
                        forwardTaktIdx = resolveOverflow(fwdSeg, forwardTaktIdx, takts);
                        placeSegment(fwdSeg, forwardTaktIdx, containerIndex, takts, placementIndex, placedActions, blueprintOrder, workInstructions);
                        deviceTaktCursor.put(fwdSeg.deviceType(), forwardTaktIdx);
                        remaining.remove(fwdSeg);
                        prevFwdTaktIdx = forwardTaktIdx;
                        prevSegDuration = segmentDuration(fwdSeg);
                    }

                    break; // restart iteration
                }
            }
        }

        return placedActions;
    }

    /**
     * Places a segment's actions into a single takt WITHOUT wiring dependencies.
     * The entire chain stays together in one takt.
     * Dependencies are wired later in execution order.
     */
    private void placeSegment(
            Segment segment,
            int taktIndex,
            int containerIndex,
            Map<Integer, Takt> takts,
            Map<String, Integer> placementIndex,
            List<PlacedAction> placedActions,
            Map<ActionTemplate, Integer> blueprintOrder,
            List<WorkInstruction> workInstructions
    ) {
        var takt = takts.get(taktIndex);
        for (var tmpl : segment.templates()) {
            var action = new Action(UUID.randomUUID(), segment.deviceType(), tmpl.actionType(), tmpl.name(),
                    new HashSet<>(), containerIndex, tmpl.durationSeconds(), tmpl.deviceIndex(), workInstructions);
            takt.actions().add(action);
            placedActions.add(new PlacedAction(tmpl, action, containerIndex, blueprintOrder.getOrDefault(tmpl, 0)));
            placementIndex.put(placementKey(containerIndex, tmpl.deviceType(), tmpl.name()), taktIndex);
        }
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

        // Group by (containerIndex, deviceType) — now in correct execution order
        var byContainerDevice = new LinkedHashMap<String, List<PlacedAction>>();
        for (var pa : allPlacedActions) {
            String key = pa.containerIndex() + ":" + pa.action().deviceType();
            byContainerDevice.computeIfAbsent(key, k -> new ArrayList<>()).add(pa);
        }

        // Track the last action per device across containers for cross-container chaining
        var lastActionByDevice = new HashMap<DeviceType, Action>();

        // Process containers in order (containerIndex 0, 1, 2...) and within each, per device
        var processedDevices = new HashSet<String>();
        for (var pa : allPlacedActions) {
            String key = pa.containerIndex() + ":" + pa.action().deviceType();
            if (processedDevices.contains(key)) continue;
            processedDevices.add(key);

            var chain = byContainerDevice.get(key);
            Action prev = null;
            for (var placed : chain) {
                if (prev != null) {
                    placed.action().dependsOn().add(prev.id());
                } else {
                    // First action in this container's device chain — link to previous container
                    // TT actions are independent across containers (no cross-container chaining)
                    if (placed.action().deviceType() != DeviceType.TT) {
                        Action crossContainerPrev = lastActionByDevice.get(placed.action().deviceType());
                        if (crossContainerPrev != null) {
                            placed.action().dependsOn().add(crossContainerPrev.id());
                        }
                    }
                }
                prev = placed.action();
            }
            // Update cross-container tracking
            if (prev != null) {
                lastActionByDevice.put(prev.deviceType(), prev);
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

    private Instant computeAnchorStartTime(int anchorTaktIndex, WorkInstruction wi, Map<Integer, Takt> takts) {
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
