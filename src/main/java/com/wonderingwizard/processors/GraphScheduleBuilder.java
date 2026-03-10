package com.wonderingwizard.processors;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.DeviceType;
import com.wonderingwizard.domain.takt.Takt;
import com.wonderingwizard.events.LoadMode;
import com.wonderingwizard.sideeffects.WorkInstruction;

import java.time.Instant;
import java.util.*;
import java.util.function.IntSupplier;

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
            String name,
            DeviceType deviceType,
            int durationSeconds,
            boolean firstInTakt,
            boolean isAnchor,
            SyncRef syncWith,
            boolean onlyOnePerTakt
    ) {
        static ActionTemplate of(String name, DeviceType type, int duration) {
            return new ActionTemplate(name, type, duration, false, false, null, false);
        }

        ActionTemplate withFirstInTakt() {
            return new ActionTemplate(name, deviceType, durationSeconds, true, isAnchor, syncWith, onlyOnePerTakt);
        }

        ActionTemplate withAnchor() {
            return new ActionTemplate(name, deviceType, durationSeconds, firstInTakt, true, syncWith, onlyOnePerTakt);
        }

        ActionTemplate withSyncWith(DeviceType type, String actionName) {
            return new ActionTemplate(name, deviceType, durationSeconds, firstInTakt, isAnchor, new SyncRef(type, actionName), onlyOnePerTakt);
        }

        ActionTemplate withOnlyOnePerTakt() {
            return new ActionTemplate(name, deviceType, durationSeconds, firstInTakt, isAnchor, syncWith, true);
        }
    }

    public record SyncRef(DeviceType deviceType, String actionName) {}

    // ── Segment: group of actions that go into the same takt ───────────

    record Segment(
            DeviceType deviceType,
            List<ActionTemplate> templates,
            boolean isAnchor,
            SyncRef syncRef
    ) {}

    // ── Placed action: tracks the mapping from template to created Action ──

    private record PlacedAction(ActionTemplate template, Action action, int containerIndex, int blueprintOrder) {}

    // ── Blueprint ──────────────────────────────────────────────────────

    List<ActionTemplate> buildContainerBlueprint(WorkInstruction wi, int qcMudaSeconds, LoadMode loadMode) {
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
                else if (wi.isTwinFetch() && wi.isTwinPut()) {
                    yield getDischargeTwinTemplate(wi, qcLiftDuration, driveToRtgPull, driveToUnderRtg, rtgPlaceDuration, driveToQcPull);
                } else {
                    yield getDischargeTwinAsSingleTemplate(wi, qcLiftDuration, driveToRtgPull, driveToUnderRtg, rtgPlaceDuration, driveToQcPull);
                }
            }
        };
    }

    private static List<ActionTemplate> getDischargeTwinTemplate(WorkInstruction wi, int qcLiftDuration, int driveToRtgPull, int driveToUnderRtg, int rtgPlaceDuration, int driveToQcPull) {
        return List.of(
                // ── QC chain (forward from anchor) ──
                ActionTemplate.of("QC Lift", QC, qcLiftDuration)
                        .withFirstInTakt().withAnchor(),
                ActionTemplate.of("QC Place", QC, wi.estimatedCycleTimeSeconds() - qcLiftDuration),

                // ── TT chain (backward from sync point) ──
                ActionTemplate.of("drive to QC pull", TT, driveToQcPull),
                ActionTemplate.of("drive to QC standby", TT, 30),
                ActionTemplate.of("drive under QC", TT, 30),
                ActionTemplate.of("handover from QC", TT, qcLiftDuration)
                        .withFirstInTakt().withSyncWith(QC, "QC Place"),

                ActionTemplate.of("drive to RTG pull", TT, driveToRtgPull),
                ActionTemplate.of("drive to RTG standby", TT, 240),
                ActionTemplate.of("drive to RTG under", TT, driveToUnderRtg)
                        .withFirstInTakt().withOnlyOnePerTakt(),
                ActionTemplate.of("handover to RTG", TT, rtgPlaceDuration)
                        .withOnlyOnePerTakt(),
                ActionTemplate.of("drive to buffer", TT, 30),

                // ── RTG chain (backward from sync point) ──
                ActionTemplate.of("drive", RTG, 1),
                ActionTemplate.of("lift from tt", RTG,
                        (wi.estimatedRtgCycleTimeSeconds() - rtgPlaceDuration)).withFirstInTakt().withSyncWith(TT, "handover to RTG"),
                ActionTemplate.of("place on yard", RTG, driveToUnderRtg + rtgPlaceDuration)
        );
    }

    private static List<ActionTemplate> getDischargeSingleTemplate(WorkInstruction wi, int qcLiftDuration, int driveToRtgPull, int driveToUnderRtg, int rtgPlaceDuration, int driveToQcPull) {
        return List.of(
                // ── QC chain (forward from anchor) ──
                ActionTemplate.of("QC Lift", QC, qcLiftDuration)
                        .withFirstInTakt().withAnchor(),
                ActionTemplate.of("QC Place", QC, wi.estimatedCycleTimeSeconds() - qcLiftDuration),

                // ── TT chain (backward from sync point) ──
                ActionTemplate.of("drive to QC pull", TT, driveToQcPull),
                ActionTemplate.of("drive to QC standby", TT, 30),
                ActionTemplate.of("drive under QC", TT, 30),
                ActionTemplate.of("handover from QC", TT, qcLiftDuration)
                        .withFirstInTakt().withSyncWith(QC, "QC Place"),

                ActionTemplate.of("drive to RTG pull", TT, driveToRtgPull),
                ActionTemplate.of("drive to RTG standby", TT, 240),
                ActionTemplate.of("drive to RTG under", TT, driveToUnderRtg)
                        .withFirstInTakt().withOnlyOnePerTakt(),
                ActionTemplate.of("handover to RTG", TT, rtgPlaceDuration)
                        .withOnlyOnePerTakt(),
                ActionTemplate.of("drive to buffer", TT, 30),

                // ── RTG chain (backward from sync point) ──
                ActionTemplate.of("drive", RTG, 1),
                ActionTemplate.of("lift from tt", RTG,
                        (wi.estimatedRtgCycleTimeSeconds() - rtgPlaceDuration)).withFirstInTakt().withSyncWith(TT, "handover to RTG"),
                ActionTemplate.of("place on yard", RTG, driveToUnderRtg + rtgPlaceDuration)
        );
    }

    private static List<ActionTemplate> getDischargeTwinAsSingleTemplate(WorkInstruction wi, int qcLiftDuration, int driveToRtgPull, int driveToUnderRtg, int rtgPlaceDuration, int driveToQcPull) {
        return List.of(
                // ── QC chain (forward from anchor) ──
                ActionTemplate.of("QC Lift", QC, qcLiftDuration)
                        .withFirstInTakt().withAnchor(),
                ActionTemplate.of("QC Place", QC, wi.estimatedCycleTimeSeconds() - qcLiftDuration),

                // ── TT chain (backward from sync point) ──
                ActionTemplate.of("drive to QC pull", TT, driveToQcPull),
                ActionTemplate.of("drive to QC standby", TT, 30),
                ActionTemplate.of("drive under QC", TT, 30),
                ActionTemplate.of("handover from QC", TT, qcLiftDuration)
                        .withFirstInTakt().withSyncWith(QC, "QC Place"),

                ActionTemplate.of("drive to RTG pull", TT, driveToRtgPull),
                ActionTemplate.of("drive to RTG standby", TT, 240),
                ActionTemplate.of("drive to RTG under", TT, driveToUnderRtg)
                        .withFirstInTakt().withOnlyOnePerTakt(),
                ActionTemplate.of("handover to RTG", TT, rtgPlaceDuration)
                        .withOnlyOnePerTakt(),
                ActionTemplate.of("drive to buffer", TT, 30),

                // ── RTG chain (backward from sync point) ──
                ActionTemplate.of("drive", RTG, 1),
                ActionTemplate.of("lift from tt", RTG,
                        (wi.estimatedRtgCycleTimeSeconds() - rtgPlaceDuration)).withFirstInTakt().withSyncWith(TT, "handover to RTG"),
                ActionTemplate.of("place on yard", RTG, driveToUnderRtg + rtgPlaceDuration)
        );
    }

    private static List<ActionTemplate> getLoadSingleTemplate(WorkInstruction wi, int qcLiftDuration, int driveToRtgPull, int driveToUnderRtg, int rtgPlaceDuration, int driveToQcPull) {
        return List.of(
                // ── QC chain (forward from anchor) ──
                ActionTemplate.of("QC Lift", QC, qcLiftDuration)
                        .withFirstInTakt().withAnchor(),
                ActionTemplate.of("QC Place", QC, wi.estimatedCycleTimeSeconds() - qcLiftDuration),

                // ── TT chain (backward from sync point) ──
                ActionTemplate.of("drive to RTG pull", TT, driveToRtgPull),
                ActionTemplate.of("drive to RTG standby", TT, 240),
                ActionTemplate.of("drive to RTG under", TT, driveToUnderRtg)
                        .withFirstInTakt().withOnlyOnePerTakt(),
                ActionTemplate.of("handover from RTG", TT, rtgPlaceDuration)
                        .withOnlyOnePerTakt(),
                ActionTemplate.of("drive to QC pull", TT, driveToQcPull),
                ActionTemplate.of("drive to QC standby", TT, 30),
                ActionTemplate.of("drive under QC", TT, 30),
                ActionTemplate.of("handover to QC", TT, qcLiftDuration)
                        .withFirstInTakt().withSyncWith(QC, "QC Lift"),
                ActionTemplate.of("drive to buffer", TT, 30),

                // ── RTG chain (backward from sync point) ──
                ActionTemplate.of("drive", RTG, 1),
                ActionTemplate.of("fetch", RTG,
                        (wi.estimatedRtgCycleTimeSeconds() - rtgPlaceDuration)),
                ActionTemplate.of("handover to tt", RTG, driveToUnderRtg + rtgPlaceDuration)
                        .withFirstInTakt().withSyncWith(TT, "handover from RTG")
        );
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

            var blueprint = buildContainerBlueprint(wi, qcMudaSeconds, loadMode);
            var placed = placeContainerActions(blueprint, containerIdx, actionWis, qcMudaSeconds, takts);
            allPlacedActions.addAll(placed);

            containerIdx++;
        }

        // Wire dependencies as a post-processing step
        wireDependencies(allPlacedActions);

        return takts.values().stream()
                .filter(t -> !t.actions().isEmpty())
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
        int anchorTaktIndex = containerIndex;
        Segment anchorSegment = findAnchorSegment(segmentsByDevice);

        Instant anchorStartTime = computeAnchorStartTime(containerIndex, wi, takts);
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
        var anchorDeviceSegments = segmentsByDevice.getOrDefault(anchorSegment.deviceType(), List.of());
        boolean foundAnchor = false;
        int forwardTakt = anchorTaktIndex;
        for (var seg : anchorDeviceSegments) {
            if (seg.isAnchor()) { foundAnchor = true; continue; }
            if (foundAnchor && seg.syncRef() == null) {
                forwardTakt++;
                ensureTaktExists(takts, forwardTakt,
                        computeTaktStartTime(forwardTakt, takts),
                        wi.estimatedCycleTimeSeconds() + qcMudaSeconds);
                placeSegment(seg, forwardTakt, containerIndex, takts, placementIndex, placedActions, blueprintOrder, workInstructions);
                deviceTaktCursor.put(anchorSegment.deviceType(), forwardTakt);
                remaining.remove(seg);
            }
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
                    int prevFwdTaktIdx = targetTakt;
                    int prevSegDuration = segmentDuration(seg);
                    for (int i = syncIdx + 1; i < deviceSegs.size(); i++) {
                        var fwdSeg = deviceSegs.get(i);
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
            var action = new Action(UUID.randomUUID(), segment.deviceType(), tmpl.name(),
                    new HashSet<>(), containerIndex, tmpl.durationSeconds(), workInstructions);
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

            for (var tmpl : entry.getValue()) {
                if (tmpl.firstInTakt() && !current.isEmpty()) {
                    segments.add(new Segment(entry.getKey(), List.copyOf(current), currentIsAnchor, currentSync));
                    current.clear();
                    currentIsAnchor = false;
                    currentSync = null;
                }
                current.add(tmpl);
                if (tmpl.isAnchor()) currentIsAnchor = true;
                if (tmpl.syncWith() != null) currentSync = tmpl.syncWith();
            }
            if (!current.isEmpty()) {
                segments.add(new Segment(entry.getKey(), List.copyOf(current), currentIsAnchor, currentSync));
            }
            result.put(entry.getKey(), segments);
        }
        return result;
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
        String key = placementKey(containerIndex, segment.syncRef().deviceType(), segment.syncRef().actionName());
        return placementIndex.get(key);
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

    private Instant computeAnchorStartTime(int containerIndex, WorkInstruction wi, Map<Integer, Takt> takts) {
        if (containerIndex > 0) {
            var prevTakt = takts.get(containerIndex - 1);
            if (prevTakt != null) {
                return prevTakt.plannedStartTime().plusSeconds(prevTakt.durationSeconds());
            }
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
