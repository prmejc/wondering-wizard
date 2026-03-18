package com.wonderingwizard.processors;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.ActionType;
import com.wonderingwizard.domain.takt.DeviceType;
import com.wonderingwizard.domain.takt.Takt;
import com.wonderingwizard.engine.EventProcessingEngine;
import com.wonderingwizard.engine.EventPropagatingEngine;
import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.ActionCompletedEvent;
import com.wonderingwizard.events.CheJobStepState;
import com.wonderingwizard.events.CheStatus;
import com.wonderingwizard.events.ContainerHandlingEquipmentEvent;
import com.wonderingwizard.events.TimeEvent;
import com.wonderingwizard.events.WorkInstructionEvent;
import com.wonderingwizard.sideeffects.ActionActivated;
import com.wonderingwizard.sideeffects.ScheduleCreated;
import com.wonderingwizard.sideeffects.TruckAssigned;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TT allocation: assigning free trucks to TT actions.
 */
@DisplayName("F-22: TT Allocation")
class TTAllocationTest {

    private EventProcessingEngine baseEngine;
    private EventPropagatingEngine engine;
    private ScheduleRunnerProcessor scheduleRunner;
    private TTStateProcessor ttState;
    private Instant now;

    @BeforeEach
    void setUp() {
        baseEngine = new EventProcessingEngine();
        ttState = new TTStateProcessor();
        scheduleRunner = new ScheduleRunnerProcessor();
        scheduleRunner.registerTTAllocationStrategy(ttState);
        baseEngine.register(ttState);
        baseEngine.register(scheduleRunner);
        engine = new EventPropagatingEngine(baseEngine);
        now = Instant.parse("2025-01-01T12:00:00Z");
        // Set current time so takt time conditions are satisfied
        engine.processEvent(new TimeEvent(now));
    }

    private ContainerHandlingEquipmentEvent workingTruck(String name, long poolId) {
        return new ContainerHandlingEquipmentEvent(
                "CHE Status Changed", 100L, "U", "TERM1", 1L,
                name, CheStatus.WORKING, "TT", poolId, CheJobStepState.IDLE, 0L);
    }

    private ContainerHandlingEquipmentEvent unavailableTruck(String name, long poolId) {
        return new ContainerHandlingEquipmentEvent(
                "CHE Status Changed", 101L, "U", "TERM1", 1L,
                name, CheStatus.UNAVAILABLE, "TT", poolId, CheJobStepState.IDLE, 0L);
    }

    private ScheduleCreated simpleScheduleWithTTAction(long workQueueId) {
        Action ttAction = Action.create(DeviceType.TT, ActionType.TT_DRIVE_TO_QC_PULL, 0, 30);
        Takt takt = new Takt(0, List.of(ttAction), now, now, 120);
        return new ScheduleCreated(workQueueId, List.of(takt), now);
    }

    private ScheduleCreated scheduleWithQCAndTTAction(long workQueueId) {
        Action qcAction = Action.create(DeviceType.QC, ActionType.QC_LIFT, 0, 20);
        Action ttAction = new Action(UUID.randomUUID(), DeviceType.TT, ActionType.TT_DRIVE_TO_QC_PULL,
                "TT Drive to QC Pull", Set.of(qcAction.id()), 0, 30);
        Takt takt = new Takt(0, List.of(qcAction, ttAction), now, now, 120);
        return new ScheduleCreated(workQueueId, List.of(takt), now);
    }

    @Nested
    @DisplayName("F-22.8: TT action waits when no truck available")
    class NoTruckAvailable {

        @Test
        @DisplayName("Should not activate TT action when no trucks exist")
        void noTrucks_ttActionPending() {
            List<SideEffect> effects = engine.processEvent(simpleScheduleWithTTAction(1));

            // TT action should NOT be activated (no TruckAssigned, no ActionActivated for TT)
            assertFalse(effects.stream().anyMatch(e -> e instanceof TruckAssigned),
                    "No truck should be assigned");
            assertFalse(effects.stream().anyMatch(e -> e instanceof ActionActivated aa
                            && aa.deviceType() == DeviceType.TT),
                    "TT action should not be activated");
        }

        @Test
        @DisplayName("Should not activate TT action when trucks exist but wrong pool")
        void wrongPool_ttActionPending() {
            engine.processEvent(workingTruck("TT01", 99));
            List<SideEffect> effects = engine.processEvent(simpleScheduleWithTTAction(1));

            assertFalse(effects.stream().anyMatch(e -> e instanceof TruckAssigned));
        }

        @Test
        @DisplayName("Should not activate TT action when truck is unavailable")
        void unavailableTruck_ttActionPending() {
            engine.processEvent(unavailableTruck("TT01", 23));
            List<SideEffect> effects = engine.processEvent(simpleScheduleWithTTAction(1));

            assertFalse(effects.stream().anyMatch(e -> e instanceof TruckAssigned));
        }
    }

    @Nested
    @DisplayName("F-22.9: TT action activates when truck available")
    class TruckAvailable {

        @Test
        @DisplayName("Should assign truck and activate TT action when free truck exists")
        void freeTruck_assigned() {
            engine.processEvent(workingTruck("TT01", 23));
            List<SideEffect> effects = engine.processEvent(simpleScheduleWithTTAction(1));

            // Should have TruckAssigned side effect
            List<TruckAssigned> assignments = effects.stream()
                    .filter(e -> e instanceof TruckAssigned)
                    .map(e -> (TruckAssigned) e)
                    .toList();
            assertEquals(1, assignments.size());
            assertEquals("TT01", assignments.get(0).cheShortName());
            assertEquals(100L, assignments.get(0).cheId());

            // Should also have ActionActivated
            assertTrue(effects.stream().anyMatch(e -> e instanceof ActionActivated aa
                    && aa.deviceType() == DeviceType.TT));
        }

        @Test
        @DisplayName("Should store truck assignment on action")
        void truckAssignment_storedOnAction() {
            engine.processEvent(workingTruck("TT01", 23));
            ScheduleCreated schedule = simpleScheduleWithTTAction(1);
            engine.processEvent(schedule);

            UUID actionId = schedule.takts().get(0).actions().get(0).id();
            Action action = scheduleRunner.getAction(1, actionId);
            assertEquals("TT01", action.cheShortName());
            assertEquals(100L, action.cheId());
        }
    }

    @Nested
    @DisplayName("F-22.10: TT action activates later when truck becomes available")
    class DelayedTruckAvailability {

        @Test
        @DisplayName("Should activate TT action on next tick after truck becomes available")
        void truckBecomesAvailable_activatesOnTick() {
            // Create schedule with no trucks available
            engine.processEvent(simpleScheduleWithTTAction(1));

            // Add a truck
            engine.processEvent(workingTruck("TT01", 23));

            // Tick to trigger re-evaluation
            List<SideEffect> effects = engine.processEvent(new TimeEvent(now.plusSeconds(5)));

            assertTrue(effects.stream().anyMatch(e -> e instanceof TruckAssigned ta
                    && "TT01".equals(ta.cheShortName())));
            assertTrue(effects.stream().anyMatch(e -> e instanceof ActionActivated aa
                    && aa.deviceType() == DeviceType.TT));
        }
    }

    @Nested
    @DisplayName("F-22.11: Multiple schedules compete for trucks")
    class MultiScheduleAllocation {

        @Test
        @DisplayName("Should not double-allocate a truck to two schedules")
        void twoSchedules_oneTruck_onlyOneGetsIt() {
            engine.processEvent(workingTruck("TT01", 23));

            // First schedule gets the truck
            engine.processEvent(simpleScheduleWithTTAction(1));

            // Second schedule should NOT get a truck
            List<SideEffect> effects2 = engine.processEvent(simpleScheduleWithTTAction(2));
            assertFalse(effects2.stream().anyMatch(e -> e instanceof TruckAssigned),
                    "Second schedule should not get a truck");
        }

        @Test
        @DisplayName("Should allocate different trucks to different schedules")
        void twoSchedules_twoTrucks_bothAllocated() {
            engine.processEvent(workingTruck("TT01", 23));
            engine.processEvent(workingTruck("TT02", 23));

            engine.processEvent(simpleScheduleWithTTAction(1));
            List<SideEffect> effects2 = engine.processEvent(simpleScheduleWithTTAction(2));

            assertTrue(effects2.stream().anyMatch(e -> e instanceof TruckAssigned));
        }
    }

    @Nested
    @DisplayName("F-22.12: Nuke frees truck allocation")
    class NukeFreesAllocation {

        @Test
        @DisplayName("Should free truck when schedule is nuked")
        void nukeSchedule_freessTruck() {
            engine.processEvent(workingTruck("TT01", 23));

            // First schedule gets TT01
            engine.processEvent(simpleScheduleWithTTAction(1));

            // Second schedule can't get a truck
            List<SideEffect> effects2 = engine.processEvent(simpleScheduleWithTTAction(2));
            assertFalse(effects2.stream().anyMatch(e -> e instanceof TruckAssigned));

            // Nuke first schedule
            engine.processEvent(new com.wonderingwizard.events.NukeWorkQueueEvent(1));

            // Tick to re-evaluate — second schedule should now get TT01
            List<SideEffect> effects3 = engine.processEvent(new TimeEvent(now.plusSeconds(5)));
            assertTrue(effects3.stream().anyMatch(e -> e instanceof TruckAssigned ta
                    && "TT01".equals(ta.cheShortName())));
        }
    }

    @Nested
    @DisplayName("F-22.13: Truck propagates to all TT actions in same container")
    class TruckPropagation {

        @Test
        @DisplayName("Should propagate truck to subsequent TT actions with same containerIndex")
        void truckPropagates_sameContainer() {
            engine.processEvent(workingTruck("TT01", 23));

            // Schedule with two TT actions in sequence, same containerIndex=0
            Action ttFirst = Action.create(DeviceType.TT, ActionType.TT_DRIVE_TO_QC_PULL, 0, 30);
            Action ttSecond = new Action(UUID.randomUUID(), DeviceType.TT, ActionType.TT_HANDOVER_FROM_QC,
                    "TT Handover from QC", Set.of(ttFirst.id()), 0, 30);
            Takt takt = new Takt(0, List.of(ttFirst, ttSecond), now, now, 120);
            ScheduleCreated schedule = new ScheduleCreated(1, List.of(takt), now);

            engine.processEvent(schedule);

            // First TT action should be assigned and activated
            Action firstAction = scheduleRunner.getAction(1, ttFirst.id());
            assertEquals("TT01", firstAction.cheShortName());

            // Second TT action (not yet activated) should also have the truck propagated
            Action secondAction = scheduleRunner.getAction(1, ttSecond.id());
            assertEquals("TT01", secondAction.cheShortName(),
                    "Truck should propagate to subsequent TT actions with same containerIndex");
        }

        @Test
        @DisplayName("Should NOT propagate truck to TT actions with different containerIndex")
        void truckDoesNotPropagate_differentContainer() {
            engine.processEvent(workingTruck("TT01", 23));

            // Two TT actions with different containerIndex, second depends on first
            Action tt0 = Action.create(DeviceType.TT, ActionType.TT_DRIVE_TO_QC_PULL, 0, 30);
            Action tt1 = new Action(UUID.randomUUID(), DeviceType.TT, ActionType.TT_DRIVE_TO_QC_PULL,
                    "TT Drive to QC Pull", Set.of(tt0.id()), 1, 30, 0, List.of(), List.of(), false);
            Takt takt = new Takt(0, List.of(tt0, tt1), now, now, 120);
            ScheduleCreated schedule = new ScheduleCreated(1, List.of(takt), now);

            engine.processEvent(schedule);

            // First container gets the truck
            Action first = scheduleRunner.getAction(1, tt0.id());
            assertEquals("TT01", first.cheShortName());

            // Second container (different containerIndex) should NOT get the same truck via propagation
            Action second = scheduleRunner.getAction(1, tt1.id());
            assertNull(second.cheShortName(),
                    "Different containerIndex should not get the truck via propagation");
        }
    }

    @Nested
    @DisplayName("F-22.14: Truck freed after chain completes")
    class TruckFreedAfterCompletion {

        @Test
        @DisplayName("Should assign freed truck to second container after first chain completes")
        void secondChainGetsTruckAfterFirstCompletes() {
            engine.processEvent(workingTruck("TT01", 23));

            // Two containers, each with one TT action, in separate takts
            // Container 0 in takt 0, container 1 in takt 1
            Action tt0 = Action.create(DeviceType.TT, ActionType.TT_DRIVE_TO_QC_PULL, 0, 30);
            Action tt1 = Action.create(DeviceType.TT, ActionType.TT_DRIVE_TO_QC_PULL, 1, 30);
            Takt takt0 = new Takt(0, List.of(tt0), now, now, 120);
            Takt takt1 = new Takt(1, List.of(tt1), now, now, 120);
            ScheduleCreated schedule = new ScheduleCreated(1, List.of(takt0, takt1), now);

            engine.processEvent(schedule);

            // Container 0 got the truck
            assertEquals("TT01", scheduleRunner.getAction(1, tt0.id()).cheShortName());
            // Container 1 did NOT get the truck
            assertNull(scheduleRunner.getAction(1, tt1.id()).cheShortName());

            // Complete the first TT action — truck should be freed
            List<SideEffect> effects = engine.processEvent(
                    new ActionCompletedEvent(tt0.id(), 1));

            // Second container should now have the truck assigned
            assertTrue(effects.stream().anyMatch(e -> e instanceof TruckAssigned ta
                    && "TT01".equals(ta.cheShortName())),
                    "Freed truck should be assigned to the second container's TT action");
            assertEquals("TT01", scheduleRunner.getAction(1, tt1.id()).cheShortName());
        }

        @Test
        @DisplayName("Should assign freed truck to another schedule after chain completes")
        void otherScheduleGetsTruckAfterFirstCompletes() {
            engine.processEvent(workingTruck("TT01", 23));

            // Schedule 1: one TT action
            Action tt1 = Action.create(DeviceType.TT, ActionType.TT_DRIVE_TO_QC_PULL, 0, 30);
            Takt takt1 = new Takt(0, List.of(tt1), now, now, 120);
            engine.processEvent(new ScheduleCreated(1, List.of(takt1), now));

            // Schedule 2: one TT action (can't get truck)
            Action tt2 = Action.create(DeviceType.TT, ActionType.TT_DRIVE_TO_QC_PULL, 0, 30);
            Takt takt2 = new Takt(0, List.of(tt2), now, now, 120);
            engine.processEvent(new ScheduleCreated(2, List.of(takt2), now));
            assertNull(scheduleRunner.getAction(2, tt2.id()).cheShortName());

            // Complete schedule 1's action — truck freed
            List<SideEffect> effects = engine.processEvent(
                    new ActionCompletedEvent(tt1.id(), 1));

            assertTrue(effects.stream().anyMatch(e -> e instanceof TruckAssigned ta
                    && "TT01".equals(ta.cheShortName())),
                    "Freed truck should be assigned to the other schedule's TT action");
        }
    }

    @Nested
    @DisplayName("F-22.15: Non-TT actions unaffected")
    class NonTTActions {

        @Test
        @DisplayName("QC actions should activate without truck allocation")
        void qcAction_activatesWithoutTruck() {
            // No trucks at all, but QC action should still activate
            Action qcAction = Action.create(DeviceType.QC, ActionType.QC_LIFT, 0, 20);
            Takt takt = new Takt(0, List.of(qcAction), now, now, 120);
            ScheduleCreated schedule = new ScheduleCreated(1, List.of(takt), now);

            List<SideEffect> effects = engine.processEvent(schedule);
            assertTrue(effects.stream().anyMatch(e -> e instanceof ActionActivated aa
                    && aa.deviceType() == DeviceType.QC));
        }
    }
}
