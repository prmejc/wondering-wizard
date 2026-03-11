package com.wonderingwizard.processors;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.ActionType;
import com.wonderingwizard.domain.takt.DeviceType;
import com.wonderingwizard.domain.takt.Takt;
import com.wonderingwizard.events.LoadMode;
import com.wonderingwizard.processors.GraphScheduleBuilder.ActionTemplate;
import com.wonderingwizard.sideeffects.WorkInstruction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static com.wonderingwizard.domain.takt.DeviceActionTemplate.DEFAULT_DURATION_SECONDS;
import static com.wonderingwizard.domain.takt.ActionType.*;
import static com.wonderingwizard.domain.takt.DeviceType.*;
import static com.wonderingwizard.events.WorkInstructionStatus.PENDING;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("GraphScheduleBuilder Tests")
class GraphScheduleBuilderTest {

    private static final Instant EMT = Instant.parse("2024-01-01T10:00:00Z");

    // ── Common test templates ───────────────────────────────────────────

    /**
     * Discharge twin: QC discharges container onto TT, TT drives to yard, RTG stacks.
     * QC anchor → TT backward pre-QC + sync + forward post-sync → RTG sync to TT handover.
     */
    static final List<ActionTemplate> DISCHARGE_TEMPLATE = List.of(
            ActionTemplate.of(QC_LIFT, QC, 20).withFirstInTakt().withAnchor(),
            ActionTemplate.of(QC_PLACE, QC, 100),

            ActionTemplate.of(TT_DRIVE_TO_QC_PULL, TT, 170),
            ActionTemplate.of(TT_DRIVE_TO_QC_STANDBY, TT, 30),
            ActionTemplate.of(TT_DRIVE_UNDER_QC, TT, 30),

            ActionTemplate.of(TT_HANDOVER_FROM_QC, TT, 20)
                    .withFirstInTakt().withSyncWith(QC, QC_PLACE),
            ActionTemplate.of(TT_DRIVE_TO_RTG_PULL, TT, 30),
            ActionTemplate.of(TT_DRIVE_TO_RTG_STANDBY, TT, 240),

            ActionTemplate.of(TT_DRIVE_TO_RTG_UNDER, TT, 30)
                    .withFirstInTakt().withOnlyOnePerTakt(),
            ActionTemplate.of(TT_HANDOVER_TO_RTG, TT, 20).withOnlyOnePerTakt(),
            ActionTemplate.of(TT_DRIVE_TO_BUFFER, TT, 30),

            ActionTemplate.of(RTG_DRIVE, RTG, 1),
            ActionTemplate.of(RTG_LIFT_FROM_TT, RTG, 40)
                    .withFirstInTakt().withSyncWith(TT, TT_HANDOVER_TO_RTG),
            ActionTemplate.of(RTG_PLACE_ON_YARD, RTG, 50)
    );

    /**
     * Load single: RTG fetches container from yard, places on TT, TT drives to QC, QC loads.
     * QC anchor → TT backward pre-RTG + RTG-handover segment + forward to QC sync → RTG sync to TT.
     */
    static final List<ActionTemplate> LOAD_TEMPLATE = List.of(
            ActionTemplate.of(QC_LIFT, QC, 20).withFirstInTakt().withAnchor(),
            ActionTemplate.of(QC_PLACE, QC, 100),

            ActionTemplate.of(TT_DRIVE_TO_RTG_PULL, TT, 30),
            ActionTemplate.of(TT_DRIVE_TO_RTG_STANDBY, TT, 240),

            ActionTemplate.of(TT_DRIVE_TO_RTG_UNDER, TT, 30)
                    .withFirstInTakt().withOnlyOnePerTakt(),
            ActionTemplate.of(TT_HANDOVER_FROM_RTG, TT, 20).withOnlyOnePerTakt(),
            ActionTemplate.of(TT_DRIVE_TO_QC_PULL, TT, 170),
            ActionTemplate.of(TT_DRIVE_TO_QC_STANDBY, TT, 30),
            ActionTemplate.of(TT_DRIVE_UNDER_QC, TT, 30),

            ActionTemplate.of(TT_HANDOVER_TO_QC, TT, 20)
                    .withFirstInTakt().withSyncWith(QC, QC_LIFT),
            ActionTemplate.of(TT_DRIVE_TO_BUFFER, TT, 30),

            ActionTemplate.of(RTG_DRIVE, RTG, 1),
            ActionTemplate.of(RTG_FETCH, RTG, 40),
            ActionTemplate.of(RTG_HANDOVER_TO_TT, RTG, 50)
                    .withFirstInTakt().withSyncWith(TT, TT_HANDOVER_FROM_RTG)
    );

    /**
     * Minimal two-device template for focused algorithm tests.
     * QC anchor + TT sync only — no RTG, no backward/forward complexity.
     */
    static final List<ActionTemplate> MINIMAL_TEMPLATE = List.of(
            ActionTemplate.of(QC_LIFT, QC, 20).withFirstInTakt().withAnchor(),
            ActionTemplate.of(QC_PLACE, QC, 100),
            ActionTemplate.of(TT_HANDOVER_FROM_QC, TT, 30)
                    .withFirstInTakt().withSyncWith(QC, QC_PLACE),
            ActionTemplate.of(TT_DRIVE_TO_RTG_PULL, TT, 60)
    );

    // ── Helpers ──────────────────────────────────────────────────────────

    private static GraphScheduleBuilder builderWith(List<ActionTemplate> template) {
        return new GraphScheduleBuilder(() -> DEFAULT_DURATION_SECONDS, () -> 0) {
            @Override
            List<ActionTemplate> buildContainerBlueprint(WorkInstruction wi, HashMap<Long, WorkInstruction> workInstructionHashMap, int qcMudaSeconds, LoadMode loadMode) {
                return template;
            }
        };
    }

    private static WorkInstruction wi(long id) {
        return new WorkInstruction(id, 1L, "CHE-001", PENDING, EMT, 120, 60, "", false, false, false, 0L, "");
    }

    private static List<Takt> schedule(List<ActionTemplate> template, int containerCount) {
        var instructions = new ArrayList<WorkInstruction>();
        for (int i = 0; i < containerCount; i++) {
            instructions.add(wi(i + 1));
        }
        return builderWith(template).createTakts(instructions, EMT, 0, LoadMode.DSCH);
    }

    private static List<Action> allActions(List<Takt> takts) {
        return takts.stream().flatMap(t -> t.actions().stream()).toList();
    }

    private static Optional<Action> findAction(List<Takt> takts, DeviceType dt, String description) {
        return allActions(takts).stream()
                .filter(a -> a.deviceType() == dt && a.description().equals(description))
                .findFirst();
    }

    private static int taktSequenceOf(List<Takt> takts, String actionDescription) {
        return takts.stream()
                .filter(t -> t.actions().stream().anyMatch(a -> a.description().equals(actionDescription)))
                .mapToInt(Takt::sequence)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Action '" + actionDescription + "' not found in any takt"));
    }

    // ── Blueprint and Segment Tests ─────────────────────────────────────

    @Nested
    @DisplayName("Blueprint and segment building")
    class BlueprintTests {

        @Test
        @DisplayName("Segments are split at firstInTakt boundaries")
        void segmentsSplitAtBoundaries() {
            var builder = builderWith(DISCHARGE_TEMPLATE);
            var segments = builder.buildSegmentsByDevice(DISCHARGE_TEMPLATE);

            assertEquals(1, segments.get(QC).size(), "QC should have 1 segment");
            assertEquals(3, segments.get(TT).size(), "TT should have 3 segments");
            assertEquals(2, segments.get(RTG).size(), "RTG should have 2 segments");
        }

        @Test
        @DisplayName("Load template also splits into correct segments")
        void loadTemplateSplitsCorrectly() {
            var builder = builderWith(LOAD_TEMPLATE);
            var segments = builder.buildSegmentsByDevice(LOAD_TEMPLATE);

            assertEquals(1, segments.get(QC).size(), "QC should have 1 segment");
            assertEquals(3, segments.get(TT).size(), "TT should have 3 segments");
            assertEquals(2, segments.get(RTG).size(), "RTG should have 2 segments");
        }

        @Test
        @DisplayName("Anchor segment is correctly identified in any template")
        void anchorSegmentIdentified() {
            var builder = builderWith(DISCHARGE_TEMPLATE);
            var segments = builder.buildSegmentsByDevice(DISCHARGE_TEMPLATE);
            var anchorSeg = segments.values().stream()
                    .flatMap(List::stream)
                    .filter(GraphScheduleBuilder.Segment::isAnchor)
                    .findFirst()
                    .orElseThrow();

            assertEquals(QC, anchorSeg.deviceType());
            assertTrue(anchorSeg.templates().stream().anyMatch(t -> t.name().equals(QC_LIFT.displayName())));
        }

        @Test
        @DisplayName("Template must have exactly one anchor")
        void templateHasOneAnchor() {
            long anchorCount = DISCHARGE_TEMPLATE.stream().filter(ActionTemplate::isAnchor).count();
            assertEquals(1, anchorCount);

            long loadAnchorCount = LOAD_TEMPLATE.stream().filter(ActionTemplate::isAnchor).count();
            assertEquals(1, loadAnchorCount);
        }
    }

    // ── Discharge Template Tests ────────────────────────────────────────

    @Nested
    @DisplayName("Discharge template — single container")
    class DischargeSingleContainerTests {

        @Test
        @DisplayName("Creates takts with all actions present")
        void allActionsPresent() {
            var takts = schedule(DISCHARGE_TEMPLATE, 1);
            assertFalse(takts.isEmpty());

            var templateNames = DISCHARGE_TEMPLATE.stream().map(ActionTemplate::name).toList();
            var placedNames = allActions(takts).stream().map(Action::description).toList();

            assertTrue(placedNames.containsAll(templateNames),
                    "All template actions should be placed. Missing: "
                            + templateNames.stream().filter(n -> !placedNames.contains(n)).toList());
        }

        @Test
        @DisplayName("QC actions are in the anchor takt at sequence 0")
        void qcActionsInAnchorTakt() {
            var takts = schedule(DISCHARGE_TEMPLATE, 1);
            var anchorTakt = takts.stream().filter(t -> t.sequence() == 0).findFirst().orElseThrow();
            var qcDescs = anchorTakt.actions().stream()
                    .filter(a -> a.deviceType() == QC)
                    .map(Action::description)
                    .toList();
            assertEquals(List.of(QC_LIFT.displayName(), QC_PLACE.displayName()), qcDescs);
        }

        @Test
        @DisplayName("TT sync segment is in the same takt as QC Place")
        void ttSyncedToQcPlace() {
            var takts = schedule(DISCHARGE_TEMPLATE, 1);
            assertEquals(taktSequenceOf(takts, QC_PLACE.displayName()), taktSequenceOf(takts, TT_HANDOVER_FROM_QC.displayName()),
                    "TT 'handover from QC' should be in the same takt as QC 'QC Place'");
        }

        @Test
        @DisplayName("RTG sync segment is in the same takt as TT RTG handover")
        void rtgSyncedToTtHandover() {
            var takts = schedule(DISCHARGE_TEMPLATE, 1);
            assertEquals(taktSequenceOf(takts, TT_HANDOVER_TO_RTG.displayName()), taktSequenceOf(takts, RTG_LIFT_FROM_TT.displayName()),
                    "RTG 'lift from tt' should be in the same takt as TT 'handover to RTG'");
        }

        @Test
        @DisplayName("Forward TT segment (under RTG, RTG handover, buffer) stays together")
        void forwardSegmentStaysTogether() {
            var takts = schedule(DISCHARGE_TEMPLATE, 1);
            var takt = takts.stream()
                    .filter(t -> t.actions().stream().anyMatch(a -> a.description().equals(TT_DRIVE_TO_RTG_UNDER.displayName())))
                    .findFirst()
                    .orElseThrow();
            var ttDescs = takt.actions().stream()
                    .filter(a -> a.deviceType() == TT)
                    .map(Action::description)
                    .toList();
            assertTrue(ttDescs.containsAll(List.of(TT_DRIVE_TO_RTG_UNDER.displayName(), TT_HANDOVER_TO_RTG.displayName(), TT_DRIVE_TO_BUFFER.displayName())),
                    "Forward TT segment should be kept together. Got: " + ttDescs);
        }

        @Test
        @DisplayName("Pre-QC takts have negative sequence numbers")
        void pulseTaktsHaveNegativeSequence() {
            var takts = schedule(DISCHARGE_TEMPLATE, 1);
            var negativeTakts = takts.stream().filter(t -> t.sequence() < 0).toList();
            assertFalse(negativeTakts.isEmpty());
            for (var t : negativeTakts) {
                assertTrue(t.actions().stream().noneMatch(a -> a.deviceType() == QC),
                        "Pulse takts should not contain QC actions");
            }
        }

        @Test
        @DisplayName("Takts are sorted by sequence")
        void taktsSortedBySequence() {
            var takts = schedule(DISCHARGE_TEMPLATE, 1);
            for (int i = 1; i < takts.size(); i++) {
                assertTrue(takts.get(i).sequence() > takts.get(i - 1).sequence());
            }
        }
    }

    // ── Load Template Tests ─────────────────────────────────────────────

    @Nested
    @DisplayName("Load template — single container")
    class LoadSingleContainerTests {

        @Test
        @DisplayName("Creates takts with all actions present")
        void allActionsPresent() {
            var takts = schedule(LOAD_TEMPLATE, 1);
            var templateNames = LOAD_TEMPLATE.stream().map(ActionTemplate::name).toList();
            var placedNames = allActions(takts).stream().map(Action::description).toList();
            assertTrue(placedNames.containsAll(templateNames),
                    "All template actions should be placed. Missing: "
                            + templateNames.stream().filter(n -> !placedNames.contains(n)).toList());
        }

        @Test
        @DisplayName("TT QC handover synced to QC Lift takt")
        void ttSyncedToQcLift() {
            var takts = schedule(LOAD_TEMPLATE, 1);
            assertEquals(taktSequenceOf(takts, QC_LIFT.displayName()), taktSequenceOf(takts, TT_HANDOVER_TO_QC.displayName()));
        }

        @Test
        @DisplayName("RTG deliver synced to TT RTG handover takt")
        void rtgSyncedToTtHandover() {
            var takts = schedule(LOAD_TEMPLATE, 1);
            assertEquals(taktSequenceOf(takts, TT_HANDOVER_FROM_RTG.displayName()), taktSequenceOf(takts, RTG_HANDOVER_TO_TT.displayName()));
        }
    }

    // ── Dependency Wiring Tests ─────────────────────────────────────────

    @Nested
    @DisplayName("Dependency wiring")
    class DependencyTests {

        @Test
        @DisplayName("QC Place depends on QC Lift")
        void qcPlaceDependsOnQcLift() {
            var takts = schedule(DISCHARGE_TEMPLATE, 1);
            var all = allActions(takts);
            var qcLift = findAction(takts, QC, QC_LIFT.displayName()).orElseThrow();
            var qcPlace = findAction(takts, QC, QC_PLACE.displayName()).orElseThrow();
            assertTrue(qcPlace.dependsOn().contains(qcLift.id()));
        }

        @Test
        @DisplayName("Each device's actions form an intra-chain dependency sequence")
        void deviceActionsFormChain() {
            var takts = schedule(DISCHARGE_TEMPLATE, 1);
            for (DeviceType dt : DeviceType.values()) {
                var actions = allActions(takts).stream()
                        .filter(a -> a.deviceType() == dt)
                        .toList();
                for (int i = 1; i < actions.size(); i++) {
                    assertTrue(actions.get(i).dependsOn().contains(actions.get(i - 1).id()),
                            dt + " action '" + actions.get(i).description()
                                    + "' should depend on '" + actions.get(i - 1).description() + "'");
                }
            }
        }

        @Test
        @DisplayName("First action of each device has no dependencies for single container")
        void firstActionsHaveNoDeps() {
            var takts = schedule(DISCHARGE_TEMPLATE, 1);
            for (DeviceType dt : DeviceType.values()) {
                var firstAction = allActions(takts).stream()
                        .filter(a -> a.deviceType() == dt)
                        .findFirst().orElseThrow();
                assertTrue(firstAction.dependsOn().isEmpty(),
                        "First " + dt + " action should have no dependencies");
            }
        }

        @Test
        @DisplayName("TT actions have no cross-container dependencies")
        void ttNoCrossContainerDeps() {
            var takts = schedule(DISCHARGE_TEMPLATE, 2);
            var all = allActions(takts);
            var c0TtIds = all.stream()
                    .filter(a -> a.deviceType() == TT && a.containerIndex() == 0)
                    .map(Action::id)
                    .collect(Collectors.toSet());
            var c1TtActions = all.stream()
                    .filter(a -> a.deviceType() == TT && a.containerIndex() == 1)
                    .toList();

            for (Action ttAction : c1TtActions) {
                for (UUID dep : ttAction.dependsOn()) {
                    assertFalse(c0TtIds.contains(dep),
                            "C1 TT '" + ttAction.description() + "' should not depend on any C0 TT action");
                }
            }
        }

        @Test
        @DisplayName("Cross-container QC dependency: C1 QC Lift depends on C0 QC Place")
        void crossContainerQcDependency() {
            var takts = schedule(DISCHARGE_TEMPLATE, 2);
            var all = allActions(takts);
            var c0QcPlace = all.stream()
                    .filter(a -> a.deviceType() == QC && a.containerIndex() == 0 && a.description().equals(QC_PLACE.displayName()))
                    .findFirst().orElseThrow();
            var c1QcLift = all.stream()
                    .filter(a -> a.deviceType() == QC && a.containerIndex() == 1 && a.description().equals(QC_LIFT.displayName()))
                    .findFirst().orElseThrow();
            assertTrue(c1QcLift.dependsOn().contains(c0QcPlace.id()));
        }

        @Test
        @DisplayName("Load template: dependency chain is correct")
        void loadTemplateDependencyChain() {
            var takts = schedule(LOAD_TEMPLATE, 1);
            for (DeviceType dt : DeviceType.values()) {
                var actions = allActions(takts).stream()
                        .filter(a -> a.deviceType() == dt)
                        .toList();
                for (int i = 1; i < actions.size(); i++) {
                    assertTrue(actions.get(i).dependsOn().contains(actions.get(i - 1).id()),
                            dt + " action '" + actions.get(i).description()
                                    + "' should depend on '" + actions.get(i - 1).description() + "'");
                }
            }
        }
    }

    // ── Multi-Container Tests ───────────────────────────────────────────

    @Nested
    @DisplayName("Multiple containers")
    class MultiContainerTests {

        @Test
        @DisplayName("Each container has QC actions in its anchor takt")
        void eachContainerHasQcActions() {
            var takts = schedule(DISCHARGE_TEMPLATE, 3);
            for (int idx = 0; idx < 3; idx++) {
                final int ci = idx;
                var qcActions = allActions(takts).stream()
                        .filter(a -> a.deviceType() == QC && a.containerIndex() == ci)
                        .toList();
                assertEquals(2, qcActions.size(), "Container " + ci + " should have 2 QC actions");
            }
        }

        @Test
        @DisplayName("Anchor takts are at container indices")
        void anchorTaktsAtContainerIndices() {
            var takts = schedule(DISCHARGE_TEMPLATE, 2);
            var takt0 = takts.stream().filter(t -> t.sequence() == 0).findFirst().orElseThrow();
            assertTrue(takt0.actions().stream().anyMatch(a -> a.deviceType() == QC && a.containerIndex() == 0));
            var takt1 = takts.stream().filter(t -> t.sequence() == 1).findFirst().orElseThrow();
            assertTrue(takt1.actions().stream().anyMatch(a -> a.deviceType() == QC && a.containerIndex() == 1));
        }

        @Test
        @DisplayName("All containers have all device types")
        void allContainersHaveAllDevices() {
            var takts = schedule(DISCHARGE_TEMPLATE, 2);
            for (int idx = 0; idx < 2; idx++) {
                final int ci = idx;
                for (DeviceType dt : DeviceType.values()) {
                    var actions = allActions(takts).stream()
                            .filter(a -> a.deviceType() == dt && a.containerIndex() == ci)
                            .toList();
                    assertFalse(actions.isEmpty(), "C" + ci + " should have " + dt + " actions");
                }
            }
        }

        @Test
        @DisplayName("Load template multi-container: all actions placed for each container")
        void loadTemplateMultiContainer() {
            var takts = schedule(LOAD_TEMPLATE, 2);
            var templateNames = LOAD_TEMPLATE.stream().map(ActionTemplate::name).collect(Collectors.toSet());
            for (int idx = 0; idx < 2; idx++) {
                final int ci = idx;
                var placedNames = allActions(takts).stream()
                        .filter(a -> a.containerIndex() == ci)
                        .map(Action::description)
                        .collect(Collectors.toSet());
                assertTrue(placedNames.containsAll(templateNames),
                        "C" + ci + " missing actions: "
                                + templateNames.stream().filter(n -> !placedNames.contains(n)).toList());
            }
        }
    }

    // ── QC Muda Tests ───────────────────────────────────────────────────

    @Nested
    @DisplayName("QC muda handling")
    class QcMudaTests {

        @Test
        @DisplayName("QC muda increases anchor takt duration")
        void qcMudaIncreasesTaktDuration() {
            int qcMuda = 15;
            var takts = builderWith(MINIMAL_TEMPLATE)
                    .createTakts(List.of(wi(1)), EMT, qcMuda, LoadMode.DSCH);
            var anchorTakt = takts.stream().filter(t -> t.sequence() == 0).findFirst().orElseThrow();
            assertEquals(120 + qcMuda, anchorTakt.durationSeconds());
        }
    }

    // ── Takt Timing Tests ───────────────────────────────────────────────

    @Nested
    @DisplayName("Takt timing")
    class TaktTimingTests {

        @Test
        @DisplayName("Anchor takt starts at estimated move time")
        void anchorTaktStartTime() {
            var takts = schedule(MINIMAL_TEMPLATE, 1);
            var anchorTakt = takts.stream().filter(t -> t.sequence() == 0).findFirst().orElseThrow();
            assertEquals(EMT, anchorTakt.plannedStartTime());
        }

        @Test
        @DisplayName("Second container anchor starts after first ends")
        void secondContainerStartsAfterFirst() {
            var takts = schedule(DISCHARGE_TEMPLATE, 2);
            var takt0 = takts.stream().filter(t -> t.sequence() == 0).findFirst().orElseThrow();
            var takt1 = takts.stream().filter(t -> t.sequence() == 1).findFirst().orElseThrow();
            assertEquals(takt0.plannedStartTime().plusSeconds(takt0.durationSeconds()),
                    takt1.plannedStartTime());
        }

        @Test
        @DisplayName("Pulse takts have start times before the anchor")
        void pulseTaktsBeforeAnchor() {
            var takts = schedule(DISCHARGE_TEMPLATE, 1);
            var negativeTakts = takts.stream().filter(t -> t.sequence() < 0).toList();
            for (var t : negativeTakts) {
                assertTrue(t.plannedStartTime().isBefore(EMT));
            }
        }
    }

    // ── Overflow Tests ──────────────────────────────────────────────────

    @Nested
    @DisplayName("TT overflow handling")
    class TtOverflowTests {

        @Test
        @DisplayName("TT segment chain stays together in one takt")
        void ttSegmentStaysTogether() {
            var takts = schedule(DISCHARGE_TEMPLATE, 1);
            // The forward TT segment should stay together
            var takt = takts.stream()
                    .filter(t -> t.actions().stream().anyMatch(a -> a.description().equals(TT_DRIVE_TO_RTG_UNDER.displayName())))
                    .findFirst()
                    .orElseThrow();
            var ttDescs = takt.actions().stream()
                    .filter(a -> a.deviceType() == TT)
                    .map(Action::description)
                    .toList();
            assertTrue(ttDescs.containsAll(List.of(TT_DRIVE_TO_RTG_UNDER.displayName(), TT_HANDOVER_TO_RTG.displayName(), TT_DRIVE_TO_BUFFER.displayName())),
                    "Got: " + ttDescs);
        }

        @Test
        @DisplayName("TT chain in a takt finishes before next TT takt starts")
        void chainFinishesBeforeNextTaktStarts() {
            var takts = schedule(DISCHARGE_TEMPLATE, 2);
            for (int ci = 0; ci < 2; ci++) {
                final int containerIdx = ci;
                var ttTakts = takts.stream()
                        .filter(t -> t.actions().stream()
                                .anyMatch(a -> a.deviceType() == TT && a.containerIndex() == containerIdx))
                        .sorted(Comparator.comparingInt(Takt::sequence))
                        .toList();

                for (int i = 0; i < ttTakts.size() - 1; i++) {
                    Takt current = ttTakts.get(i);
                    Takt next = ttTakts.get(i + 1);
                    int ttDuration = current.actions().stream()
                            .filter(a -> a.deviceType() == TT && a.containerIndex() == containerIdx)
                            .mapToInt(Action::durationSeconds).sum();
                    Instant chainEnd = current.plannedStartTime().plusSeconds(ttDuration);
                    assertTrue(!chainEnd.isAfter(next.plannedStartTime()),
                            "C" + containerIdx + " TT chain in " + current.name()
                                    + " (duration " + ttDuration + "s, ends " + chainEnd
                                    + ") overlaps with " + next.name()
                                    + " (starts " + next.plannedStartTime() + ")");
                }
            }
        }

        @Test
        @DisplayName("onlyOnePerTakt prevents two containers' actions from sharing a takt")
        void onlyOnePerTaktSeparatesContainers() {
            var takts = schedule(DISCHARGE_TEMPLATE, 2);
            // "drive to RTG under" is onlyOnePerTakt — each container's instance should be in a different takt
            var underRtgTakts = takts.stream()
                    .filter(t -> t.actions().stream().anyMatch(a -> a.description().equals(TT_DRIVE_TO_RTG_UNDER.displayName())))
                    .toList();

            for (var takt : underRtgTakts) {
                long count = takt.actions().stream()
                        .filter(a -> a.description().equals(TT_DRIVE_TO_RTG_UNDER.displayName()))
                        .count();
                assertEquals(1, count,
                        "Each takt should have at most one 'under RTG' action, found " + count);
            }
        }

        @Test
        @DisplayName("Load template: TT chain overflow handled correctly")
        void loadTemplateOverflow() {
            var takts = schedule(LOAD_TEMPLATE, 2);
            for (int ci = 0; ci < 2; ci++) {
                final int containerIdx = ci;
                var ttTakts = takts.stream()
                        .filter(t -> t.actions().stream()
                                .anyMatch(a -> a.deviceType() == TT && a.containerIndex() == containerIdx))
                        .sorted(Comparator.comparingInt(Takt::sequence))
                        .toList();

                for (int i = 0; i < ttTakts.size() - 1; i++) {
                    Takt current = ttTakts.get(i);
                    Takt next = ttTakts.get(i + 1);
                    int ttDuration = current.actions().stream()
                            .filter(a -> a.deviceType() == TT && a.containerIndex() == containerIdx)
                            .mapToInt(Action::durationSeconds).sum();
                    Instant chainEnd = current.plannedStartTime().plusSeconds(ttDuration);
                    assertTrue(!chainEnd.isAfter(next.plannedStartTime()),
                            "C" + containerIdx + " TT chain overlap in load template");
                }
            }
        }
    }

    // ── Minimal Template Tests ──────────────────────────────────────────

    @Nested
    @DisplayName("Minimal template — algorithm fundamentals")
    class MinimalTemplateTests {

        @Test
        @DisplayName("Minimal template produces takts")
        void createsTakts() {
            var takts = schedule(MINIMAL_TEMPLATE, 1);
            assertFalse(takts.isEmpty());
        }

        @Test
        @DisplayName("Sync places TT in same takt as QC")
        void syncPlacement() {
            var takts = schedule(MINIMAL_TEMPLATE, 1);
            assertEquals(taktSequenceOf(takts, QC_PLACE.displayName()), taktSequenceOf(takts, TT_HANDOVER_FROM_QC.displayName()));
        }

        @Test
        @DisplayName("All template actions are placed")
        void allActionsPlaced() {
            var takts = schedule(MINIMAL_TEMPLATE, 1);
            var templateNames = MINIMAL_TEMPLATE.stream().map(ActionTemplate::name).toList();
            var placedNames = allActions(takts).stream().map(Action::description).toList();
            assertTrue(placedNames.containsAll(templateNames));
        }
    }

    // ── Edge Cases ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Empty instruction list returns empty takts")
        void emptyInstructions() {
            var takts = builderWith(DISCHARGE_TEMPLATE).createTakts(List.of(), EMT, 0, LoadMode.DSCH);
            assertTrue(takts.isEmpty());
        }

        @Test
        @DisplayName("Template with long RTG action still places all actions")
        void longRtgAction() {
            var template = List.of(
                    ActionTemplate.of(QC_LIFT, QC, 20).withFirstInTakt().withAnchor(),
                    ActionTemplate.of(QC_PLACE, QC, 100),
                    ActionTemplate.of(TT_HANDOVER_FROM_QC, TT, 30)
                            .withFirstInTakt().withSyncWith(QC, QC_PLACE),
                    ActionTemplate.of(TT_DRIVE_TO_RTG_PULL, TT, 60),
                    ActionTemplate.of(RTG_DRIVE, RTG, 1),
                    ActionTemplate.of(RTG_LIFT_FROM_TT, RTG, 160)
                            .withFirstInTakt().withSyncWith(TT, TT_HANDOVER_FROM_QC),
                    ActionTemplate.of(RTG_PLACE_ON_YARD, RTG, 50)
            );

            var takts = schedule(template, 1);
            var rtgLift = findAction(takts, RTG, RTG_LIFT_FROM_TT.displayName()).orElseThrow();
            assertTrue(rtgLift.durationSeconds() > 100,
                    "RTG lift should have the long duration specified in template");
        }

        @Test
        @DisplayName("Single action per device creates valid schedule")
        void singleActionPerDevice() {
            var template = List.of(
                    ActionTemplate.of(QC_LIFT, QC, 120).withFirstInTakt().withAnchor(),
                    ActionTemplate.of(TT_HANDOVER_TO_QC, TT, 60)
                            .withFirstInTakt().withSyncWith(QC, QC_LIFT)
            );
            var takts = schedule(template, 1);
            assertEquals(2, allActions(takts).size());
        }

        @Test
        @DisplayName("Three containers all get complete action sets")
        void threeContainersComplete() {
            var takts = schedule(DISCHARGE_TEMPLATE, 3);
            var templateSize = DISCHARGE_TEMPLATE.size();
            for (int ci = 0; ci < 3; ci++) {
                final int idx = ci;
                long containerActions = allActions(takts).stream()
                        .filter(a -> a.containerIndex() == idx)
                        .count();
                assertEquals(templateSize, containerActions,
                        "Container " + idx + " should have " + templateSize + " actions");
            }
        }
    }

    // ── Feature Flag Integration Tests ──────────────────────────────────

    @Nested
    @DisplayName("Feature flag integration")
    class FeatureFlagTests {

        @Test
        @DisplayName("WorkQueueProcessor uses graph builder when flag is true")
        void featureFlagEnablesGraphBuilder() {
            var engine = new com.wonderingwizard.engine.EventProcessingEngine();
            engine.register(new WorkQueueProcessor(() -> DEFAULT_DURATION_SECONDS, () -> 0, true));

            engine.processEvent(new com.wonderingwizard.events.WorkInstructionEvent(
                    1L, 1L, "CHE-001", PENDING, EMT, 120, 60));
            engine.processEvent(new com.wonderingwizard.events.WorkInstructionEvent(
                    2L, 1L, "CHE-002", PENDING, EMT, 120, 60));

            var effects = engine.processEvent(
                    new com.wonderingwizard.events.WorkQueueMessage(1L,
                            com.wonderingwizard.events.WorkQueueStatus.ACTIVE, 0, null));

            assertEquals(1, effects.size());
            assertInstanceOf(com.wonderingwizard.sideeffects.ScheduleCreated.class, effects.getFirst());
            var created = (com.wonderingwizard.sideeffects.ScheduleCreated) effects.getFirst();
            assertFalse(created.takts().isEmpty(), "Graph builder should produce takts");
        }

        @Test
        @DisplayName("DSCH load mode uses discharge twin template")
        void dschLoadModeUsesDischargeTemplate() {
            var engine = new com.wonderingwizard.engine.EventProcessingEngine();
            engine.register(new WorkQueueProcessor(() -> DEFAULT_DURATION_SECONDS, () -> 0, true));

            engine.processEvent(new com.wonderingwizard.events.WorkInstructionEvent(
                    1L, 1L, "CHE-001", PENDING, EMT, 120, 60));

            var effects = engine.processEvent(
                    new com.wonderingwizard.events.WorkQueueMessage(1L,
                            com.wonderingwizard.events.WorkQueueStatus.ACTIVE, 0,
                            com.wonderingwizard.events.LoadMode.DSCH));

            var created = (com.wonderingwizard.sideeffects.ScheduleCreated) effects.getFirst();
            var allActions = created.takts().stream()
                    .flatMap(t -> t.actions().stream())
                    .map(Action::description)
                    .toList();
            // Discharge template has "handover from QC" (TT receives from QC)
            assertTrue(allActions.contains("handover from QC"),
                    "DSCH mode should use discharge template with 'handover from QC'");
        }

        @Test
        @DisplayName("LOAD load mode uses load single template")
        void loadLoadModeUsesLoadTemplate() {
            var engine = new com.wonderingwizard.engine.EventProcessingEngine();
            engine.register(new WorkQueueProcessor(() -> DEFAULT_DURATION_SECONDS, () -> 0, true));

            engine.processEvent(new com.wonderingwizard.events.WorkInstructionEvent(
                    1L, 1L, "CHE-001", PENDING, EMT, 120, 60));

            var effects = engine.processEvent(
                    new com.wonderingwizard.events.WorkQueueMessage(1L,
                            com.wonderingwizard.events.WorkQueueStatus.ACTIVE, 0,
                            com.wonderingwizard.events.LoadMode.LOAD));

            var created = (com.wonderingwizard.sideeffects.ScheduleCreated) effects.getFirst();
            var allActions = created.takts().stream()
                    .flatMap(t -> t.actions().stream())
                    .map(Action::description)
                    .toList();
            // Load template has "handover to QC" (TT delivers to QC)
            assertTrue(allActions.contains("handover to QC"),
                    "LOAD mode should use load template with 'handover to QC'");
        }

        @Test
        @DisplayName("WorkQueueProcessor uses legacy builder when flag is false")
        void featureFlagDisablesGraphBuilder() {
            var engine = new com.wonderingwizard.engine.EventProcessingEngine();
            engine.register(new WorkQueueProcessor(() -> DEFAULT_DURATION_SECONDS, () -> 0, false));

            engine.processEvent(new com.wonderingwizard.events.WorkInstructionEvent(
                    1L, 1L, "CHE-001", PENDING, EMT, 120, 60));

            var effects = engine.processEvent(
                    new com.wonderingwizard.events.WorkQueueMessage(1L,
                            com.wonderingwizard.events.WorkQueueStatus.ACTIVE, 0, null));

            assertEquals(1, effects.size());
            assertInstanceOf(com.wonderingwizard.sideeffects.ScheduleCreated.class, effects.getFirst());
        }
    }
}
