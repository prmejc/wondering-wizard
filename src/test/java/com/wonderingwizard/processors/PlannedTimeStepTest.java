package com.wonderingwizard.processors;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.ActionType;
import com.wonderingwizard.domain.takt.DeviceType;
import com.wonderingwizard.domain.takt.Takt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PlannedTimeStepTest {

    private PlannedTimeStep step;
    private static final Instant TAKT_START = Instant.parse("2026-01-01T10:00:00Z");

    @BeforeEach
    void setUp() {
        step = new PlannedTimeStep();
    }

    @Test
    @DisplayName("Actions with no dependencies start at takt planned start time")
    void noDependenciesStartAtTaktStart() {
        Action qcLift = Action.create(DeviceType.QC, ActionType.QC_LIFT, 0, 30);
        Action ttDrive = Action.create(DeviceType.TT, ActionType.TT_DRIVE_TO_QC_STANDBY, 0, 45);

        Takt takt = new Takt(0, new ArrayList<>(List.of(qcLift, ttDrive)), TAKT_START, TAKT_START, 120);
        List<Takt> result = step.process(List.of(takt));

        Action resultQc = result.getFirst().actions().get(0);
        Action resultTt = result.getFirst().actions().get(1);

        assertEquals(TAKT_START, resultQc.plannedStartTime());
        assertEquals(TAKT_START.plusSeconds(30), resultQc.plannedEndTime());
        assertEquals(TAKT_START, resultTt.plannedStartTime());
        assertEquals(TAKT_START.plusSeconds(45), resultTt.plannedEndTime());
    }

    @Test
    @DisplayName("Dependent action starts after its dependency ends")
    void dependentActionStartsAfterDependency() {
        Action qcLift = Action.create(DeviceType.QC, ActionType.QC_LIFT, 0, 30);
        Action qcPlace = new Action(qcLift.id(), DeviceType.QC, ActionType.QC_PLACE, "place",
                Set.of(qcLift.id()), 0, 20);
        // Fix: qcPlace depends on qcLift, but they share the same ID. Use a separate action.
        Action qcPlaceAction = Action.create(DeviceType.QC, ActionType.QC_PLACE, 0, 20)
                .withDependencies(Set.of(qcLift.id()));

        Takt takt = new Takt(0, new ArrayList<>(List.of(qcLift, qcPlaceAction)), TAKT_START, TAKT_START, 120);
        List<Takt> result = step.process(List.of(takt));

        Action resultPlace = result.getFirst().actions().get(1);
        assertEquals(TAKT_START.plusSeconds(30), resultPlace.plannedStartTime());
        assertEquals(TAKT_START.plusSeconds(50), resultPlace.plannedEndTime());
    }

    @Test
    @DisplayName("Only same-device dependencies count — cross-device deps ignored")
    void onlySameDeviceDependencies() {
        Action qcLift = Action.create(DeviceType.QC, ActionType.QC_LIFT, 0, 10);
        Action ttDrive = Action.create(DeviceType.TT, ActionType.TT_DRIVE_TO_QC_STANDBY, 0, 60);
        // QC_PLACE depends on both QC_LIFT and TT_DRIVE, but only QC_LIFT (same device) should matter
        Action qcPlace = Action.create(DeviceType.QC, ActionType.QC_PLACE, 0, 20)
                .withDependencies(Set.of(qcLift.id(), ttDrive.id()));

        Takt takt = new Takt(0, new ArrayList<>(List.of(qcLift, ttDrive, qcPlace)), TAKT_START, TAKT_START, 120);
        List<Takt> result = step.process(List.of(takt));

        Action resultDep = result.getFirst().actions().get(2);
        // Only QC_LIFT (10s) counts, TT dependency (60s) ignored
        assertEquals(TAKT_START.plusSeconds(10), resultDep.plannedStartTime());
        assertEquals(TAKT_START.plusSeconds(30), resultDep.plannedEndTime());
    }

    @Test
    @DisplayName("Multiple same-device dependencies uses the latest")
    void multipleSameDeviceDependencies() {
        Action rtgDrive = Action.create(DeviceType.RTG, ActionType.RTG_DRIVE, 0, 10);
        Action rtgWait = Action.create(DeviceType.RTG, ActionType.RTG_WAIT_FOR_TRUCK, 0, 50)
                .withDependencies(Set.of(rtgDrive.id()));
        // RTG action depends on both RTG actions — should use the later one (rtgWait ends at 60s)
        Action rtgLift = Action.create(DeviceType.RTG, ActionType.RTG_LIFT_FROM_TT, 0, 15)
                .withDependencies(Set.of(rtgDrive.id(), rtgWait.id()));

        Takt takt = new Takt(0, new ArrayList<>(List.of(rtgDrive, rtgWait, rtgLift)), TAKT_START, TAKT_START, 120);
        List<Takt> result = step.process(List.of(takt));

        Action resultLift = result.getFirst().actions().get(2);
        assertEquals(TAKT_START.plusSeconds(60), resultLift.plannedStartTime());
        assertEquals(TAKT_START.plusSeconds(75), resultLift.plannedEndTime());
    }

    @Test
    @DisplayName("Cross-takt dependencies are ignored — action starts at takt start")
    void crossTaktDependencyIgnored() {
        Action firstAction = Action.create(DeviceType.QC, ActionType.QC_LIFT, 0, 30);
        Instant takt1Start = TAKT_START;
        Takt takt1 = new Takt(0, new ArrayList<>(List.of(firstAction)), takt1Start, takt1Start, 120);

        Instant takt2Start = TAKT_START.plusSeconds(120);
        Action crossDepAction = Action.create(DeviceType.TT, ActionType.TT_DRIVE_TO_RTG_UNDER, 0, 25)
                .withDependencies(Set.of(firstAction.id()));
        Takt takt2 = new Takt(1, new ArrayList<>(List.of(crossDepAction)), takt2Start, takt2Start, 120);

        List<Takt> result = step.process(List.of(takt1, takt2));

        Action resultCross = result.get(1).actions().getFirst();
        // Cross-takt dependency is not resolved — starts at takt2's planned start
        assertEquals(takt2Start, resultCross.plannedStartTime());
        assertEquals(takt2Start.plusSeconds(25), resultCross.plannedEndTime());
    }

    @Test
    @DisplayName("Null takt planned start time leaves actions unchanged")
    void nullTaktStartTimeSkipped() {
        Action action = Action.create(DeviceType.QC, ActionType.QC_LIFT, 0, 30);
        Takt takt = new Takt(0, new ArrayList<>(List.of(action)), null, null, 120);

        List<Takt> result = step.process(List.of(takt));

        assertNull(result.getFirst().actions().getFirst().plannedStartTime());
        assertNull(result.getFirst().actions().getFirst().plannedEndTime());
    }

    @Test
    @DisplayName("Estimated times computed from takt estimated start time")
    void estimatedTimesFromEstimatedStart() {
        Instant estimatedStart = TAKT_START.plusSeconds(10);
        Action qcLift = Action.create(DeviceType.QC, ActionType.QC_LIFT, 0, 30);
        Action qcPlace = Action.create(DeviceType.QC, ActionType.QC_PLACE, 0, 20)
                .withDependencies(Set.of(qcLift.id()));

        Takt takt = new Takt(0, new ArrayList<>(List.of(qcLift, qcPlace)), TAKT_START, estimatedStart, 120);
        List<Takt> result = step.process(List.of(takt));

        Action resultLift = result.getFirst().actions().get(0);
        assertEquals(TAKT_START, resultLift.plannedStartTime());
        assertEquals(estimatedStart, resultLift.estimatedStartTime());
        assertEquals(estimatedStart.plusSeconds(30), resultLift.estimatedEndTime());

        Action resultPlace = result.getFirst().actions().get(1);
        assertEquals(TAKT_START.plusSeconds(30), resultPlace.plannedStartTime());
        assertEquals(estimatedStart.plusSeconds(30), resultPlace.estimatedStartTime());
        assertEquals(estimatedStart.plusSeconds(50), resultPlace.estimatedEndTime());
    }
}
