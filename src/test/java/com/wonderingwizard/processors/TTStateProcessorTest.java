package com.wonderingwizard.processors;

import com.wonderingwizard.engine.EventProcessingEngine;
import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.CheJobStepState;
import com.wonderingwizard.events.CheLogicalPositionEvent;
import com.wonderingwizard.events.CheStatus;
import com.wonderingwizard.events.ContainerHandlingEquipmentEvent;
import com.wonderingwizard.events.TimeEvent;
import com.wonderingwizard.sideeffects.TTStateUpdated;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for F-22: TT State Processor feature.
 *
 * @see <a href="docs/requirements.md">F-22 Requirements</a>
 */
@DisplayName("F-22: TT State Processor")
class TTStateProcessorTest {

    private EventProcessingEngine engine;
    private TTStateProcessor processor;

    @BeforeEach
    void setUp() {
        engine = new EventProcessingEngine();
        processor = new TTStateProcessor();
        engine.register(processor);
    }

    private ContainerHandlingEquipmentEvent ttEvent(String cheShortName, CheStatus cheStatus, CheJobStepState jobStepState) {
        return new ContainerHandlingEquipmentEvent(
                "CHE_UPDATE", 1L, "OP", "TERM1", 1L,
                cheShortName, cheStatus, "TT", 100L, jobStepState, System.currentTimeMillis());
    }

    private ContainerHandlingEquipmentEvent rtgEvent(String cheShortName) {
        return new ContainerHandlingEquipmentEvent(
                "CHE_UPDATE", 2L, "OP", "TERM1", 1L,
                cheShortName, CheStatus.WORKING, "RTG", 200L, CheJobStepState.IDLE, System.currentTimeMillis());
    }

    @Nested
    @DisplayName("F-22.1: No CHE events")
    class NoCheEvents {

        @Test
        @DisplayName("Should return empty side effects for non-CHE events")
        void nonCheEvent_returnsEmptySideEffects() {
            List<SideEffect> sideEffects = engine.processEvent(new TimeEvent(Instant.now()));
            assertTrue(sideEffects.isEmpty());
        }

        @Test
        @DisplayName("Should have no truck state initially")
        void initialState_noTrucks() {
            assertTrue(processor.getTruckState().isEmpty());
        }
    }

    @Nested
    @DisplayName("F-22.2: TT CHE event processing")
    class TTEventProcessing {

        @Test
        @DisplayName("Should produce TTStateUpdated side effect for TT event")
        void ttEvent_producesTTStateUpdated() {
            ContainerHandlingEquipmentEvent event = ttEvent("TT01", CheStatus.WORKING, CheJobStepState.IDLE);
            List<SideEffect> sideEffects = engine.processEvent(event);

            assertEquals(1, sideEffects.size());
            assertInstanceOf(TTStateUpdated.class, sideEffects.get(0));

            TTStateUpdated updated = (TTStateUpdated) sideEffects.get(0);
            assertEquals("TT01", updated.cheShortName());
            assertEquals(event, updated.event());
        }

        @Test
        @DisplayName("Should store truck state keyed by cheShortName")
        void ttEvent_storesState() {
            ContainerHandlingEquipmentEvent event = ttEvent("TT01", CheStatus.WORKING, CheJobStepState.IDLE);
            engine.processEvent(event);

            Map<String, ContainerHandlingEquipmentEvent> state = processor.getTruckState();
            assertEquals(1, state.size());
            assertEquals(event, state.get("TT01"));
        }

        @Test
        @DisplayName("Should store multiple trucks independently")
        void multipleTrucks_storedIndependently() {
            ContainerHandlingEquipmentEvent event1 = ttEvent("TT01", CheStatus.WORKING, CheJobStepState.IDLE);
            ContainerHandlingEquipmentEvent event2 = ttEvent("TT02", CheStatus.UNAVAILABLE, CheJobStepState.IDLE);

            engine.processEvent(event1);
            engine.processEvent(event2);

            Map<String, ContainerHandlingEquipmentEvent> state = processor.getTruckState();
            assertEquals(2, state.size());
            assertEquals(event1, state.get("TT01"));
            assertEquals(event2, state.get("TT02"));
        }
    }

    @Nested
    @DisplayName("F-22.3: State override on same cheShortName")
    class StateOverride {

        @Test
        @DisplayName("Should override previous state for same cheShortName")
        void sameCheName_overridesState() {
            ContainerHandlingEquipmentEvent first = ttEvent("TT01", CheStatus.WORKING, CheJobStepState.IDLE);
            ContainerHandlingEquipmentEvent second = ttEvent("TT01", CheStatus.UNAVAILABLE, CheJobStepState.LOGGED_OUT);

            engine.processEvent(first);
            engine.processEvent(second);

            ContainerHandlingEquipmentEvent current = processor.getTruckState("TT01");
            assertEquals(CheStatus.UNAVAILABLE, current.cheStatus());
            assertEquals(CheJobStepState.LOGGED_OUT, current.cheJobStepState());
        }

        @Test
        @DisplayName("Should produce TTStateUpdated for each update")
        void sameCheName_producesSideEffectEachTime() {
            List<SideEffect> first = engine.processEvent(ttEvent("TT01", CheStatus.WORKING, CheJobStepState.IDLE));
            List<SideEffect> second = engine.processEvent(ttEvent("TT01", CheStatus.UNAVAILABLE, CheJobStepState.LOGGED_OUT));

            assertEquals(1, first.size());
            assertEquals(1, second.size());
            assertInstanceOf(TTStateUpdated.class, first.get(0));
            assertInstanceOf(TTStateUpdated.class, second.get(0));
        }
    }

    @Nested
    @DisplayName("F-22.4: Non-TT CHE events are ignored")
    class NonTTEvents {

        @Test
        @DisplayName("Should ignore RTG CHE events")
        void rtgEvent_ignored() {
            List<SideEffect> sideEffects = engine.processEvent(rtgEvent("RTG01"));
            assertTrue(sideEffects.isEmpty());
            assertTrue(processor.getTruckState().isEmpty());
        }
    }

    @Nested
    @DisplayName("F-22.5: Blank cheShortName is ignored")
    class BlankName {

        @Test
        @DisplayName("Should ignore events with null cheShortName")
        void nullName_ignored() {
            ContainerHandlingEquipmentEvent event = new ContainerHandlingEquipmentEvent(
                    "CHE_UPDATE", 1L, "OP", "TERM1", 1L,
                    null, CheStatus.WORKING, "TT", 100L, CheJobStepState.IDLE, 0L);
            List<SideEffect> sideEffects = engine.processEvent(event);
            assertTrue(sideEffects.isEmpty());
        }

        @Test
        @DisplayName("Should ignore events with blank cheShortName")
        void blankName_ignored() {
            ContainerHandlingEquipmentEvent event = new ContainerHandlingEquipmentEvent(
                    "CHE_UPDATE", 1L, "OP", "TERM1", 1L,
                    "  ", CheStatus.WORKING, "TT", 100L, CheJobStepState.IDLE, 0L);
            List<SideEffect> sideEffects = engine.processEvent(event);
            assertTrue(sideEffects.isEmpty());
        }
    }

    @Nested
    @DisplayName("F-22.6: State capture and restore")
    class StateManagement {

        @Test
        @DisplayName("Should capture and restore state correctly")
        void captureAndRestore() {
            engine.processEvent(ttEvent("TT01", CheStatus.WORKING, CheJobStepState.IDLE));
            engine.processEvent(ttEvent("TT02", CheStatus.UNAVAILABLE, CheJobStepState.IDLE));

            Object snapshot = processor.captureState();

            // Process more events
            engine.processEvent(ttEvent("TT03", CheStatus.WORKING, CheJobStepState.TO_ROW_TO_COLLECT_CNTR));
            assertEquals(3, processor.getTruckState().size());

            // Restore to snapshot
            processor.restoreState(snapshot);
            assertEquals(2, processor.getTruckState().size());
            assertNotNull(processor.getTruckState("TT01"));
            assertNotNull(processor.getTruckState("TT02"));
            assertNull(processor.getTruckState("TT03"));
        }
    }

    @Nested
    @DisplayName("F-22.7: Get individual truck state")
    class GetIndividualState {

        @Test
        @DisplayName("Should return null for unknown truck")
        void unknownTruck_returnsNull() {
            assertNull(processor.getTruckState("UNKNOWN"));
        }

        @Test
        @DisplayName("Should return latest state for known truck")
        void knownTruck_returnsLatest() {
            ContainerHandlingEquipmentEvent event = ttEvent("TT01", CheStatus.WORKING, CheJobStepState.IDLE);
            engine.processEvent(event);

            ContainerHandlingEquipmentEvent state = processor.getTruckState("TT01");
            assertNotNull(state);
            assertEquals("TT01", state.cheShortName());
            assertEquals(CheStatus.WORKING, state.cheStatus());
        }
    }

    @Nested
    @DisplayName("Position tracking")
    class PositionTracking {

        @Test
        @DisplayName("Should store position for known truck")
        void storePositionForKnownTruck() {
            engine.processEvent(ttEvent("TT01", CheStatus.WORKING, CheJobStepState.IDLE));
            engine.processEvent(new CheLogicalPositionEvent("TT01", 42L, "3A13", 35.889, -5.496, 1.2, 1700000000000L));

            var positions = processor.getTruckPositions();
            assertTrue(positions.containsKey("TT01"));
            assertEquals(42L, positions.get("TT01").currentMapNodeId());
            assertEquals("3A13", positions.get("TT01").currentMapNodeName());
            assertEquals(35.889, positions.get("TT01").latitude(), 0.0001);
        }

        @Test
        @DisplayName("Should not store position for unknown truck")
        void ignorePositionForUnknownTruck() {
            engine.processEvent(new CheLogicalPositionEvent("TT99", 42L, "3A13", 35.889, -5.496, 1.2, 1700000000000L));

            var positions = processor.getTruckPositions();
            assertFalse(positions.containsKey("TT99"));
        }

        @Test
        @DisplayName("Should update position on new event")
        void updatePositionOnNewEvent() {
            engine.processEvent(ttEvent("TT01", CheStatus.WORKING, CheJobStepState.IDLE));
            engine.processEvent(new CheLogicalPositionEvent("TT01", 42L, "3A13", 35.889, -5.496, 1.2, 1000L));
            engine.processEvent(new CheLogicalPositionEvent("TT01", 99L, "3B15", 35.890, -5.497, 0.8, 2000L));

            var pos = processor.getTruckPositions().get("TT01");
            assertEquals(99L, pos.currentMapNodeId());
            assertEquals("3B15", pos.currentMapNodeName());
        }
    }
}
