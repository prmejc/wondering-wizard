package com.wonderingwizard.processors;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.DeviceType;
import com.wonderingwizard.domain.takt.Takt;
import com.wonderingwizard.sideeffects.WorkInstruction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static com.wonderingwizard.domain.takt.DeviceActionTemplate.DEFAULT_DURATION_SECONDS;
import static com.wonderingwizard.domain.takt.DeviceType.*;
import static com.wonderingwizard.events.WorkInstructionStatus.PENDING;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("GraphScheduleBuilder Tests")
class GraphScheduleBuilderTest {

    private static final Instant EMT = Instant.parse("2024-01-01T10:00:00Z");

    private GraphScheduleBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new GraphScheduleBuilder(() -> DEFAULT_DURATION_SECONDS, () -> 0);
    }

    private WorkInstruction wi(String id, int cycleTime, int rtgCycleTime) {
        return new WorkInstruction(id, "queue-1", "CHE-001", PENDING, EMT, cycleTime, rtgCycleTime);
    }

    @Nested
    @DisplayName("Blueprint and segment building")
    class BlueprintTests {

        @Test
        @DisplayName("Blueprint contains actions for all three device types")
        void blueprintContainsAllDeviceTypes() {
            var blueprint = builder.buildContainerBlueprint(wi("wi-1", 120, 60), 0);
            var deviceTypes = blueprint.stream()
                    .map(GraphScheduleBuilder.ActionTemplate::deviceType)
                    .collect(Collectors.toSet());
            assertEquals(Set.of(QC, TT, RTG), deviceTypes);
        }

        @Test
        @DisplayName("Blueprint has exactly one anchor")
        void blueprintHasOneAnchor() {
            var blueprint = builder.buildContainerBlueprint(wi("wi-1", 120, 60), 0);
            long anchorCount = blueprint.stream().filter(GraphScheduleBuilder.ActionTemplate::isAnchor).count();
            assertEquals(1, anchorCount, "Should have exactly one anchor action");
        }

        @Test
        @DisplayName("Segments are split at firstInTakt boundaries")
        void segmentsSplitAtBoundaries() {
            var blueprint = builder.buildContainerBlueprint(wi("wi-1", 120, 60), 0);
            var segments = builder.buildSegmentsByDevice(blueprint);

            // QC: 1 segment (anchor with QC Lift + QC Place)
            var qcSegments = segments.get(QC);
            assertEquals(1, qcSegments.size(), "QC should have 1 segment (anchor includes both actions)");

            // TT: 3 segments (split at "drive to RTG under" and "handover to QC")
            var ttSegments = segments.get(TT);
            assertEquals(3, ttSegments.size(), "TT should have 3 segments");

            // RTG: 2 segments (split at "handover to tt" which is firstInTakt)
            var rtgSegments = segments.get(RTG);
            assertEquals(2, rtgSegments.size(), "RTG should have 2 segments");
        }

        @Test
        @DisplayName("Anchor segment is correctly identified")
        void anchorSegmentIdentified() {
            var blueprint = builder.buildContainerBlueprint(wi("wi-1", 120, 60), 0);
            var segments = builder.buildSegmentsByDevice(blueprint);
            var anchorSeg = segments.values().stream()
                    .flatMap(List::stream)
                    .filter(GraphScheduleBuilder.Segment::isAnchor)
                    .findFirst()
                    .orElseThrow();
            assertEquals(QC, anchorSeg.deviceType());
            assertTrue(anchorSeg.templates().stream().anyMatch(t -> t.name().equals("QC Lift")));
        }
    }

    @Nested
    @DisplayName("Single container takt creation")
    class SingleContainerTests {

        @Test
        @DisplayName("Creates takts for a single work instruction")
        void singleWorkInstruction_createsTakts() {
            var instructions = List.of(wi("wi-1", 120, 60));
            var takts = builder.createTakts(instructions, EMT, 0);
            assertFalse(takts.isEmpty(), "Should create at least one takt");
        }

        @Test
        @DisplayName("Anchor takt is at container index 0")
        void anchorTaktAtContainerIndex() {
            var instructions = List.of(wi("wi-1", 120, 60));
            var takts = builder.createTakts(instructions, EMT, 0);

            // Takt with sequence=0 should contain QC actions (anchor)
            var anchorTakt = takts.stream()
                    .filter(t -> t.sequence() == 0)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Anchor takt at sequence 0 not found"));

            var qcActions = anchorTakt.actions().stream()
                    .filter(a -> a.deviceType() == QC)
                    .toList();
            assertFalse(qcActions.isEmpty(), "Anchor takt should contain QC actions");
        }

        @Test
        @DisplayName("QC actions are in the anchor takt with correct descriptions")
        void qcActionsInAnchorTakt() {
            var instructions = List.of(wi("wi-1", 120, 60));
            var takts = builder.createTakts(instructions, EMT, 0);

            var anchorTakt = takts.stream().filter(t -> t.sequence() == 0).findFirst().orElseThrow();
            var qcDescriptions = anchorTakt.actions().stream()
                    .filter(a -> a.deviceType() == QC)
                    .map(Action::description)
                    .toList();
            assertEquals(List.of("QC Lift", "QC Place"), qcDescriptions);
        }

        @Test
        @DisplayName("TT handover to QC is synced to the same takt as QC Lift")
        void ttHandoverSyncedToQcTakt() {
            var instructions = List.of(wi("wi-1", 120, 60));
            var takts = builder.createTakts(instructions, EMT, 0);

            // Find takt containing QC Lift
            int qcTaktSeq = takts.stream()
                    .filter(t -> t.actions().stream().anyMatch(a -> a.description().equals("QC Lift")))
                    .mapToInt(Takt::sequence)
                    .findFirst()
                    .orElseThrow();

            // Find takt containing TT "handover to QC"
            int ttHandoverSeq = takts.stream()
                    .filter(t -> t.actions().stream().anyMatch(a -> a.description().equals("handover to QC")))
                    .mapToInt(Takt::sequence)
                    .findFirst()
                    .orElseThrow();

            assertEquals(qcTaktSeq, ttHandoverSeq,
                    "TT 'handover to QC' should be in the same takt as QC 'QC Lift'");
        }

        @Test
        @DisplayName("RTG handover to TT is synced to the same takt as TT handover from RTG")
        void rtgHandoverSyncedToTtTakt() {
            var instructions = List.of(wi("wi-1", 120, 60));
            var takts = builder.createTakts(instructions, EMT, 0);

            // Find takt containing TT "handover from RTG"
            int ttHandoverSeq = takts.stream()
                    .filter(t -> t.actions().stream().anyMatch(a -> a.description().equals("handover from RTG")))
                    .mapToInt(Takt::sequence)
                    .findFirst()
                    .orElseThrow();

            // Find takt containing RTG "handover to tt"
            int rtgHandoverSeq = takts.stream()
                    .filter(t -> t.actions().stream().anyMatch(a -> a.description().equals("handover to tt")))
                    .mapToInt(Takt::sequence)
                    .findFirst()
                    .orElseThrow();

            assertEquals(ttHandoverSeq, rtgHandoverSeq,
                    "RTG 'handover to tt' should be in same takt as TT 'handover from RTG'");
        }

        @Test
        @DisplayName("All TT actions are present")
        void allTtActionsPresent() {
            var instructions = List.of(wi("wi-1", 120, 60));
            var takts = builder.createTakts(instructions, EMT, 0);

            var ttDescriptions = takts.stream()
                    .flatMap(t -> t.actions().stream())
                    .filter(a -> a.deviceType() == TT)
                    .map(Action::description)
                    .toList();

            var expectedTt = List.of("drive to RTG pull", "drive to RTG standby",
                    "drive to RTG under", "handover from RTG",
                    "drive to QC pull", "drive to QC standby", "drive under QC",
                    "handover to QC", "drive to buffer");
            assertTrue(ttDescriptions.containsAll(expectedTt),
                    "All TT actions should be present. Got: " + ttDescriptions);
        }

        @Test
        @DisplayName("All RTG actions are present")
        void allRtgActionsPresent() {
            var instructions = List.of(wi("wi-1", 120, 60));
            var takts = builder.createTakts(instructions, EMT, 0);

            var rtgDescriptions = takts.stream()
                    .flatMap(t -> t.actions().stream())
                    .filter(a -> a.deviceType() == RTG)
                    .map(Action::description)
                    .toList();

            assertTrue(rtgDescriptions.containsAll(List.of("drive", "fetch", "handover to tt")),
                    "All RTG actions should be present. Got: " + rtgDescriptions);
        }

        @Test
        @DisplayName("Pulse takts have negative sequence numbers")
        void pulseTaktsHaveNegativeSequence() {
            var instructions = List.of(wi("wi-1", 120, 60));
            var takts = builder.createTakts(instructions, EMT, 0);

            var negativeTakts = takts.stream().filter(t -> t.sequence() < 0).toList();
            assertFalse(negativeTakts.isEmpty(),
                    "Should have pulse takts with negative sequence (TT/RTG before QC)");

            // Pulse takts should contain TT or RTG actions, not QC
            for (var pulseTakt : negativeTakts) {
                assertTrue(pulseTakt.actions().stream().noneMatch(a -> a.deviceType() == QC),
                        "Pulse takts should not contain QC actions");
            }
        }

        @Test
        @DisplayName("Takts are sorted by sequence")
        void taktsSortedBySequence() {
            var instructions = List.of(wi("wi-1", 120, 60));
            var takts = builder.createTakts(instructions, EMT, 0);

            for (int i = 1; i < takts.size(); i++) {
                assertTrue(takts.get(i).sequence() > takts.get(i - 1).sequence(),
                        "Takts should be sorted by sequence");
            }
        }
    }

    @Nested
    @DisplayName("Dependency wiring")
    class DependencyTests {

        @Test
        @DisplayName("QC Place depends on QC Lift within same container")
        void qcPlaceDependsOnQcLift() {
            var instructions = List.of(wi("wi-1", 120, 60));
            var takts = builder.createTakts(instructions, EMT, 0);

            var allActions = takts.stream().flatMap(t -> t.actions().stream()).toList();
            var qcLift = allActions.stream().filter(a -> a.description().equals("QC Lift")).findFirst().orElseThrow();
            var qcPlace = allActions.stream().filter(a -> a.description().equals("QC Place")).findFirst().orElseThrow();

            assertTrue(qcPlace.dependsOn().contains(qcLift.id()),
                    "QC Place should depend on QC Lift");
        }

        @Test
        @DisplayName("TT actions form an intra-chain dependency sequence")
        void ttActionsFormChain() {
            var instructions = List.of(wi("wi-1", 120, 60));
            var takts = builder.createTakts(instructions, EMT, 0);

            var ttActions = takts.stream()
                    .flatMap(t -> t.actions().stream())
                    .filter(a -> a.deviceType() == TT)
                    .toList();

            // Each TT action (except the first) should depend on the previous TT action
            for (int i = 1; i < ttActions.size(); i++) {
                assertTrue(ttActions.get(i).dependsOn().contains(ttActions.get(i - 1).id()),
                        "TT action '" + ttActions.get(i).description()
                                + "' should depend on '" + ttActions.get(i - 1).description() + "'");
            }
        }

        @Test
        @DisplayName("RTG actions form an intra-chain dependency sequence")
        void rtgActionsFormChain() {
            var instructions = List.of(wi("wi-1", 120, 60));
            var takts = builder.createTakts(instructions, EMT, 0);

            var rtgActions = takts.stream()
                    .flatMap(t -> t.actions().stream())
                    .filter(a -> a.deviceType() == RTG)
                    .toList();

            for (int i = 1; i < rtgActions.size(); i++) {
                assertTrue(rtgActions.get(i).dependsOn().contains(rtgActions.get(i - 1).id()),
                        "RTG action '" + rtgActions.get(i).description()
                                + "' should depend on '" + rtgActions.get(i - 1).description() + "'");
            }
        }

        @Test
        @DisplayName("First action of each device chain has no intra-chain dependency")
        void firstActionsHaveNoDeps() {
            var instructions = List.of(wi("wi-1", 120, 60));
            var takts = builder.createTakts(instructions, EMT, 0);

            var allActions = takts.stream().flatMap(t -> t.actions().stream()).toList();

            // First QC action (QC Lift) for container 0 should have no deps
            var qcLift = allActions.stream()
                    .filter(a -> a.deviceType() == QC && a.description().equals("QC Lift"))
                    .findFirst().orElseThrow();
            assertTrue(qcLift.dependsOn().isEmpty(),
                    "First QC action should have no dependencies for single container");

            // First TT action for container 0 should have no deps
            var firstTt = allActions.stream()
                    .filter(a -> a.deviceType() == TT)
                    .findFirst().orElseThrow();
            assertTrue(firstTt.dependsOn().isEmpty(),
                    "First TT action should have no dependencies for single container");

            // First RTG action for container 0 should have no deps
            var firstRtg = allActions.stream()
                    .filter(a -> a.deviceType() == RTG)
                    .findFirst().orElseThrow();
            assertTrue(firstRtg.dependsOn().isEmpty(),
                    "First RTG action should have no dependencies for single container");
        }

        @Test
        @DisplayName("TT actions have no cross-container dependencies")
        void ttActionsNoCrossContainerDeps() {
            var instructions = List.of(
                    wi("wi-1", 120, 60),
                    wi("wi-2", 120, 60)
            );
            var takts = builder.createTakts(instructions, EMT, 0);

            var allActions = takts.stream().flatMap(t -> t.actions().stream()).toList();
            var c0TtIds = allActions.stream()
                    .filter(a -> a.deviceType() == TT && a.containerIndex() == 0)
                    .map(Action::id)
                    .collect(Collectors.toSet());
            var c1TtActions = allActions.stream()
                    .filter(a -> a.deviceType() == TT && a.containerIndex() == 1)
                    .toList();

            for (Action ttAction : c1TtActions) {
                for (UUID dep : ttAction.dependsOn()) {
                    assertFalse(c0TtIds.contains(dep),
                            "C1 TT action '" + ttAction.description()
                                    + "' should not depend on any C0 TT action");
                }
            }
        }
    }

    @Nested
    @DisplayName("Multiple container takt creation")
    class MultiContainerTests {

        @Test
        @DisplayName("Creates takts for multiple work instructions")
        void multipleWorkInstructions() {
            var instructions = List.of(
                    wi("wi-1", 120, 60),
                    wi("wi-2", 120, 60),
                    wi("wi-3", 120, 60)
            );
            var takts = builder.createTakts(instructions, EMT, 0);
            assertFalse(takts.isEmpty());

            // Each container should have QC actions in its anchor takt
            for (int containerIdx = 0; containerIdx < 3; containerIdx++) {
                final int idx = containerIdx;
                var containerQcActions = takts.stream()
                        .flatMap(t -> t.actions().stream())
                        .filter(a -> a.deviceType() == QC && a.containerIndex() == idx)
                        .toList();
                assertEquals(2, containerQcActions.size(),
                        "Container " + idx + " should have 2 QC actions");
            }
        }

        @Test
        @DisplayName("Cross-container QC dependency: container 1 QC Lift depends on container 0 QC Place")
        void crossContainerQcDependency() {
            var instructions = List.of(
                    wi("wi-1", 120, 60),
                    wi("wi-2", 120, 60)
            );
            var takts = builder.createTakts(instructions, EMT, 0);
            var allActions = takts.stream().flatMap(t -> t.actions().stream()).toList();

            var c0QcPlace = allActions.stream()
                    .filter(a -> a.deviceType() == QC && a.containerIndex() == 0 && a.description().equals("QC Place"))
                    .findFirst().orElseThrow();

            var c1QcLift = allActions.stream()
                    .filter(a -> a.deviceType() == QC && a.containerIndex() == 1 && a.description().equals("QC Lift"))
                    .findFirst().orElseThrow();

            assertTrue(c1QcLift.dependsOn().contains(c0QcPlace.id()),
                    "Container 1's QC Lift should depend on container 0's QC Place");
        }

        @Test
        @DisplayName("Anchor takts are at container indices")
        void anchorTaktsAtContainerIndices() {
            var instructions = List.of(
                    wi("wi-1", 120, 60),
                    wi("wi-2", 120, 60)
            );
            var takts = builder.createTakts(instructions, EMT, 0);

            // Container 0's QC actions should be in takt with sequence=0
            var takt0 = takts.stream().filter(t -> t.sequence() == 0).findFirst().orElseThrow();
            assertTrue(takt0.actions().stream().anyMatch(a -> a.deviceType() == QC && a.containerIndex() == 0));

            // Container 1's QC actions should be in takt with sequence=1
            var takt1 = takts.stream().filter(t -> t.sequence() == 1).findFirst().orElseThrow();
            assertTrue(takt1.actions().stream().anyMatch(a -> a.deviceType() == QC && a.containerIndex() == 1));
        }

        @Test
        @DisplayName("All containers have all device actions")
        void allContainersHaveAllDeviceActions() {
            var instructions = List.of(
                    wi("wi-1", 120, 60),
                    wi("wi-2", 120, 60)
            );
            var takts = builder.createTakts(instructions, EMT, 0);

            for (int containerIdx = 0; containerIdx < 2; containerIdx++) {
                final int idx = containerIdx;
                for (DeviceType dt : DeviceType.values()) {
                    var deviceActions = takts.stream()
                            .flatMap(t -> t.actions().stream())
                            .filter(a -> a.deviceType() == dt && a.containerIndex() == idx)
                            .toList();
                    assertFalse(deviceActions.isEmpty(),
                            "Container " + idx + " should have " + dt + " actions");
                }
            }
        }
    }

    @Nested
    @DisplayName("QC muda handling")
    class QcMudaTests {

        @Test
        @DisplayName("QC muda increases anchor takt duration")
        void qcMudaIncreasesTaktDuration() {
            var instructions = List.of(wi("wi-1", 120, 60));
            int qcMuda = 15;
            var takts = builder.createTakts(instructions, EMT, qcMuda);

            var anchorTakt = takts.stream().filter(t -> t.sequence() == 0).findFirst().orElseThrow();
            assertEquals(120 + qcMuda, anchorTakt.durationSeconds(),
                    "Anchor takt duration should include QC muda");
        }
    }

    @Nested
    @DisplayName("Takt timing")
    class TaktTimingTests {

        @Test
        @DisplayName("Anchor takt has correct planned start time from work instruction")
        void anchorTaktStartTime() {
            var instructions = List.of(wi("wi-1", 120, 60));
            var takts = builder.createTakts(instructions, EMT, 0);

            var anchorTakt = takts.stream().filter(t -> t.sequence() == 0).findFirst().orElseThrow();
            assertEquals(EMT, anchorTakt.plannedStartTime(),
                    "Anchor takt should start at the estimated move time");
        }

        @Test
        @DisplayName("Second container anchor takt starts after first container's takt ends")
        void secondContainerStartsAfterFirst() {
            var instructions = List.of(
                    wi("wi-1", 120, 60),
                    wi("wi-2", 120, 60)
            );
            var takts = builder.createTakts(instructions, EMT, 0);

            var takt0 = takts.stream().filter(t -> t.sequence() == 0).findFirst().orElseThrow();
            var takt1 = takts.stream().filter(t -> t.sequence() == 1).findFirst().orElseThrow();

            assertEquals(takt0.plannedStartTime().plusSeconds(takt0.durationSeconds()),
                    takt1.plannedStartTime(),
                    "Second container's takt should start when first container's takt ends");
        }

        @Test
        @DisplayName("Pulse takts have start times derived from adjacent takt")
        void pulseTaktsTimingDerived() {
            var instructions = List.of(wi("wi-1", 120, 60));
            var takts = builder.createTakts(instructions, EMT, 0);

            var negativeTakts = takts.stream()
                    .filter(t -> t.sequence() < 0)
                    .sorted(Comparator.comparingInt(Takt::sequence).reversed())
                    .toList();

            // Each negative takt should have a reasonable start time (before the anchor)
            for (var pulseTakt : negativeTakts) {
                assertTrue(pulseTakt.plannedStartTime().isBefore(EMT),
                        "Pulse takt should start before the anchor takt");
            }
        }
    }

    @Nested
    @DisplayName("Feature flag integration")
    class FeatureFlagTests {

        @Test
        @DisplayName("WorkQueueProcessor uses graph builder when flag is true")
        void featureFlagEnablesGraphBuilder() {
            var engine = new com.wonderingwizard.engine.EventProcessingEngine();
            engine.register(new WorkQueueProcessor(() -> DEFAULT_DURATION_SECONDS, () -> 0, true));

            engine.processEvent(new com.wonderingwizard.events.WorkInstructionEvent(
                    "wi-1", "queue-1", "CHE-001", PENDING, EMT, 120, 60));
            engine.processEvent(new com.wonderingwizard.events.WorkInstructionEvent(
                    "wi-2", "queue-1", "CHE-002", PENDING, EMT, 120, 60));

            var effects = engine.processEvent(
                    new com.wonderingwizard.events.WorkQueueMessage("queue-1",
                            com.wonderingwizard.events.WorkQueueStatus.ACTIVE, 0));

            assertEquals(1, effects.size());
            assertInstanceOf(com.wonderingwizard.sideeffects.ScheduleCreated.class, effects.getFirst());
            var created = (com.wonderingwizard.sideeffects.ScheduleCreated) effects.getFirst();
            assertFalse(created.takts().isEmpty(), "Graph builder should produce takts");
        }

        @Test
        @DisplayName("WorkQueueProcessor uses legacy builder when flag is false")
        void featureFlagDisablesGraphBuilder() {
            var engine = new com.wonderingwizard.engine.EventProcessingEngine();
            engine.register(new WorkQueueProcessor(() -> DEFAULT_DURATION_SECONDS, () -> 0, false));

            engine.processEvent(new com.wonderingwizard.events.WorkInstructionEvent(
                    "wi-1", "queue-1", "CHE-001", PENDING, EMT, 120, 60));

            var effects = engine.processEvent(
                    new com.wonderingwizard.events.WorkQueueMessage("queue-1",
                            com.wonderingwizard.events.WorkQueueStatus.ACTIVE, 0));

            assertEquals(1, effects.size());
            assertInstanceOf(com.wonderingwizard.sideeffects.ScheduleCreated.class, effects.getFirst());
        }
    }

    @Nested
    @DisplayName("TT overflow handling")
    class TtOverflowTests {

        @Test
        @DisplayName("TT segments are pushed back enough takts based on their own duration")
        void ttSegmentsPushedBackByDuration() {
            var instructions = List.of(wi("wi-1", 120, 60));
            var takts = builder.createTakts(instructions, EMT, 0);

            // The sync segment (handover to QC + drive to buffer) is at anchor takt 0.
            // The middle TT segment (drive to RTG under..drive under QC) has 140s duration.
            // ceil(140/120) = 2, so it should be placed 2 takts before the anchor.
            int anchorTakt = 0;

            int middleSegTakt = takts.stream()
                    .filter(t -> t.actions().stream().anyMatch(a -> a.description().equals("drive to RTG under")))
                    .mapToInt(Takt::sequence)
                    .findFirst().orElseThrow();

            assertTrue(anchorTakt - middleSegTakt >= 2,
                    "Middle TT segment (140s) should be at least 2 takts before anchor. "
                            + "Middle at " + middleSegTakt + ", anchor at " + anchorTakt);
        }

        @Test
        @DisplayName("Single container TT segment is allowed even if it exceeds takt duration")
        void singleContainerTtSegmentAllowed() {
            var instructions = List.of(wi("wi-1", 120, 60));
            var takts = builder.createTakts(instructions, EMT, 0);

            // The middle TT segment has duration > 120s but should still be placed in one takt
            var middleTakt = takts.stream()
                    .filter(t -> t.actions().stream().anyMatch(a -> a.description().equals("drive to RTG under")))
                    .findFirst()
                    .orElseThrow();

            var ttActionsInMiddle = middleTakt.actions().stream()
                    .filter(a -> a.deviceType() == TT)
                    .toList();

            assertTrue(ttActionsInMiddle.size() >= 3,
                    "Middle TT segment should be kept together in one takt");
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Empty instruction list returns empty takts")
        void emptyInstructions() {
            var takts = builder.createTakts(List.of(), EMT, 0);
            assertTrue(takts.isEmpty());
        }

        @Test
        @DisplayName("Single instruction with high RTG cycle time")
        void highRtgCycleTime() {
            var instructions = List.of(wi("wi-1", 120, 180));
            var takts = builder.createTakts(instructions, EMT, 0);
            assertFalse(takts.isEmpty());

            // RTG fetch should have a larger duration
            var fetchAction = takts.stream()
                    .flatMap(t -> t.actions().stream())
                    .filter(a -> a.description().equals("fetch") && a.deviceType() == RTG)
                    .findFirst().orElseThrow();
            assertTrue(fetchAction.durationSeconds() > 30,
                    "RTG fetch should have increased duration for high RTG cycle time");
        }

        @Test
        @DisplayName("Variable drive times produce different takt layouts")
        void variableDriveTimes() {
            // Short drive time
            var shortBuilder = new GraphScheduleBuilder(() -> 40, () -> 0);
            var shortTakts = shortBuilder.createTakts(List.of(wi("wi-1", 120, 60)), EMT, 0);

            // Long drive time
            var longBuilder = new GraphScheduleBuilder(() -> 250, () -> 0);
            var longTakts = longBuilder.createTakts(List.of(wi("wi-1", 120, 60)), EMT, 0);

            // Longer drive times should potentially create more pulse takts
            assertTrue(longTakts.size() >= shortTakts.size(),
                    "Longer drive times should produce at least as many takts");
        }
    }
}
