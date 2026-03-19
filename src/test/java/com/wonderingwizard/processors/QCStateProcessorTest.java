package com.wonderingwizard.processors;

import com.wonderingwizard.engine.EventProcessingEngine;
import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.CraneAvailabilityStatus;
import com.wonderingwizard.events.CraneAvailabilityStatusEvent;
import com.wonderingwizard.events.CraneDelayActivityEvent;
import com.wonderingwizard.events.CraneReadinessEvent;
import com.wonderingwizard.events.QuayCraneMappingEvent;
import com.wonderingwizard.sideeffects.QCStateUpdated;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("QC State Processor")
class QCStateProcessorTest {

    private EventProcessingEngine engine;
    private QCStateProcessor processor;

    @BeforeEach
    void setUp() {
        engine = new EventProcessingEngine();
        processor = new QCStateProcessor();
        engine.register(processor);
    }

    private QuayCraneMappingEvent qcEvent(String name, String vessel, String standby) {
        return new QuayCraneMappingEvent(name, vessel, "DSCH", null,
                standby, standby, "N", null, null, "MAPTM", System.currentTimeMillis());
    }

    private CraneReadinessEvent readinessEvent(String qcName, long workQueueId, Instant resumeTime) {
        return new CraneReadinessEvent(qcName, workQueueId, resumeTime, "operator1", "evt-001");
    }

    @Test
    @DisplayName("Should store new QC on first event")
    void storeNewQC() {
        List<SideEffect> effects = engine.processEvent(qcEvent("QCZ9", "EMERALD", "B80"));

        assertTrue(effects.stream().anyMatch(e -> e instanceof QCStateUpdated qc
                && "QCZ9".equals(qc.quayCraneShortName())));
        assertNotNull(processor.getQCState("QCZ9"));
        assertEquals("EMERALD", processor.getQCState("QCZ9").vesselName());
    }

    @Test
    @DisplayName("Should update existing QC")
    void updateExistingQC() {
        engine.processEvent(qcEvent("QCZ9", "EMERALD", "B80"));
        engine.processEvent(qcEvent("QCZ9", "SAFMARINE", "B82"));

        assertEquals("SAFMARINE", processor.getQCState("QCZ9").vesselName());
        assertEquals("B82", processor.getQCState("QCZ9").standbyPositionName());
    }

    @Test
    @DisplayName("Should track multiple QCs independently")
    void multipleQCs() {
        engine.processEvent(qcEvent("QCZ9", "EMERALD", "B80"));
        engine.processEvent(qcEvent("QCZ7", "MAERSK", "B60"));

        assertEquals(2, processor.getQCState().size());
        assertEquals("EMERALD", processor.getQCState("QCZ9").vesselName());
        assertEquals("MAERSK", processor.getQCState("QCZ7").vesselName());
    }

    @Test
    @DisplayName("Should ignore events with blank name")
    void ignoreBlankName() {
        List<SideEffect> effects = engine.processEvent(qcEvent("", "EMERALD", "B80"));

        assertFalse(effects.stream().anyMatch(e -> e instanceof QCStateUpdated));
        assertTrue(processor.getQCState().isEmpty());
    }

    @Test
    @DisplayName("Should store crane readiness for existing QC")
    void craneReadinessForExistingQC() {
        Instant resume = Instant.parse("2025-01-15T10:30:00Z");
        engine.processEvent(qcEvent("QCZ9", "EMERALD", "B80"));
        List<SideEffect> effects = engine.processEvent(readinessEvent("QCZ9", 42L, resume));

        assertTrue(effects.stream().anyMatch(e -> e instanceof QCStateUpdated qc
                && "QCZ9".equals(qc.quayCraneShortName())));
        var readiness = processor.getCraneReadiness().get("QCZ9");
        assertNotNull(readiness);
        assertEquals(42L, readiness.workQueueId());
        assertEquals(resume, readiness.qcResumeTimestamp());
    }

    @Test
    @DisplayName("Should create QC when crane readiness arrives for unknown QC")
    void craneReadinessCreatesQC() {
        Instant resume = Instant.parse("2025-01-15T10:30:00Z");
        List<SideEffect> effects = engine.processEvent(readinessEvent("QC01", 99L, resume));

        assertTrue(effects.stream().anyMatch(e -> e instanceof QCStateUpdated qc
                && "QC01".equals(qc.quayCraneShortName())));
        assertNotNull(processor.getQCState("QC01"));
        assertEquals("QC01", processor.getQCState("QC01").quayCraneShortName());
        var readiness = processor.getCraneReadiness().get("QC01");
        assertNotNull(readiness);
        assertEquals(99L, readiness.workQueueId());
    }

    @Test
    @DisplayName("Should ignore crane readiness with blank name")
    void craneReadinessBlankName() {
        List<SideEffect> effects = engine.processEvent(readinessEvent("", 42L, Instant.now()));

        assertFalse(effects.stream().anyMatch(e -> e instanceof QCStateUpdated));
        assertTrue(processor.getCraneReadiness().isEmpty());
    }

    @Test
    @DisplayName("Should update crane readiness on subsequent events")
    void craneReadinessUpdated() {
        Instant resume1 = Instant.parse("2025-01-15T10:30:00Z");
        Instant resume2 = Instant.parse("2025-01-15T11:00:00Z");
        engine.processEvent(qcEvent("QCZ9", "EMERALD", "B80"));
        engine.processEvent(readinessEvent("QCZ9", 42L, resume1));
        engine.processEvent(readinessEvent("QCZ9", 43L, resume2));

        var readiness = processor.getCraneReadiness().get("QCZ9");
        assertEquals(43L, readiness.workQueueId());
        assertEquals(resume2, readiness.qcResumeTimestamp());
    }

    @Test
    @DisplayName("Should capture and restore state including crane readiness")
    void captureAndRestore() {
        Instant resume = Instant.parse("2025-01-15T10:30:00Z");
        engine.processEvent(qcEvent("QCZ9", "EMERALD", "B80"));
        engine.processEvent(readinessEvent("QCZ9", 42L, resume));
        var state = processor.captureState();

        engine.processEvent(qcEvent("QCZ9", "CHANGED", "B99"));
        engine.processEvent(readinessEvent("QCZ9", 99L, Instant.now()));
        assertEquals("CHANGED", processor.getQCState("QCZ9").vesselName());
        assertEquals(99L, processor.getCraneReadiness().get("QCZ9").workQueueId());

        processor.restoreState(state);
        assertEquals("EMERALD", processor.getQCState("QCZ9").vesselName());
        assertEquals(42L, processor.getCraneReadiness().get("QCZ9").workQueueId());
    }

    // --- Crane Availability Status ---

    private CraneAvailabilityStatusEvent availabilityEvent(String cheId, CraneAvailabilityStatus status) {
        return new CraneAvailabilityStatusEvent("MAPTM", cheId, "STS", status, System.currentTimeMillis());
    }

    @Test
    @DisplayName("Should store availability for existing QC")
    void availabilityForExistingQC() {
        engine.processEvent(qcEvent("QCZ9", "EMERALD", "B80"));
        List<SideEffect> effects = engine.processEvent(
                availabilityEvent("QCZ9", CraneAvailabilityStatus.READY));

        assertTrue(effects.stream().anyMatch(e -> e instanceof QCStateUpdated qc
                && "QCZ9".equals(qc.quayCraneShortName())));
        assertEquals(CraneAvailabilityStatus.READY, processor.getCraneAvailability().get("QCZ9"));
    }

    @Test
    @DisplayName("Should create QC when availability arrives for unknown cheId")
    void availabilityCreatesQC() {
        List<SideEffect> effects = engine.processEvent(
                availabilityEvent("QC01", CraneAvailabilityStatus.NOT_READY));

        assertTrue(effects.stream().anyMatch(e -> e instanceof QCStateUpdated qc
                && "QC01".equals(qc.quayCraneShortName())));
        assertNotNull(processor.getQCState("QC01"));
        assertEquals(CraneAvailabilityStatus.NOT_READY, processor.getCraneAvailability().get("QC01"));
    }

    @Test
    @DisplayName("Should ignore availability with blank cheId")
    void availabilityBlankCheId() {
        List<SideEffect> effects = engine.processEvent(
                availabilityEvent("", CraneAvailabilityStatus.READY));

        assertFalse(effects.stream().anyMatch(e -> e instanceof QCStateUpdated));
        assertTrue(processor.getCraneAvailability().isEmpty());
    }

    @Test
    @DisplayName("Should update availability status")
    void availabilityUpdated() {
        engine.processEvent(qcEvent("QCZ9", "EMERALD", "B80"));
        engine.processEvent(availabilityEvent("QCZ9", CraneAvailabilityStatus.NOT_READY));
        assertEquals(CraneAvailabilityStatus.NOT_READY, processor.getCraneAvailability().get("QCZ9"));

        engine.processEvent(availabilityEvent("QCZ9", CraneAvailabilityStatus.READY));
        assertEquals(CraneAvailabilityStatus.READY, processor.getCraneAvailability().get("QCZ9"));
    }

    @Test
    @DisplayName("Should capture and restore state including crane availability")
    void captureAndRestoreWithAvailability() {
        engine.processEvent(qcEvent("QCZ9", "EMERALD", "B80"));
        engine.processEvent(availabilityEvent("QCZ9", CraneAvailabilityStatus.READY));
        var state = processor.captureState();

        engine.processEvent(availabilityEvent("QCZ9", CraneAvailabilityStatus.NOT_READY));
        assertEquals(CraneAvailabilityStatus.NOT_READY, processor.getCraneAvailability().get("QCZ9"));

        processor.restoreState(state);
        assertEquals(CraneAvailabilityStatus.READY, processor.getCraneAvailability().get("QCZ9"));
    }

    // --- Crane Delay Activities ---

    private CraneDelayActivityEvent delayEvent(String cheShortName, String delayType, String status) {
        return new CraneDelayActivityEvent(
                "Crane Delay Occured", "I", "MAPTM", null, null, "411608N",
                Instant.parse("2025-01-15T10:00:00Z"), null, cheShortName,
                "test remark", delayType, "TEST DELAY", "FIXED_START",
                status, "CONTAINER_MOVE_STOPPED", "ABNORMAL", null);
    }

    @Test
    @DisplayName("Should store delay for existing QC")
    void delayForExistingQC() {
        engine.processEvent(qcEvent("QCZ8", "EMERALD", "B80"));
        engine.processEvent(delayEvent("QCZ8", "1.1", "CLERK_STARTED"));

        var delays = processor.getCraneDelays().get("QCZ8");
        assertNotNull(delays);
        assertEquals(1, delays.size());
        assertEquals("1.1", delays.getFirst().delayType());
    }

    @Test
    @DisplayName("Should create QC when delay arrives for unknown crane")
    void delayCreatesQC() {
        engine.processEvent(delayEvent("QC99", "2.1", "CLERK_STARTED"));

        assertNotNull(processor.getQCState("QC99"));
        assertEquals(1, processor.getCraneDelays().get("QC99").size());
    }

    @Test
    @DisplayName("Should keep only last 3 delays per crane")
    void delayMaxThree() {
        engine.processEvent(qcEvent("QCZ8", "EMERALD", "B80"));
        engine.processEvent(delayEvent("QCZ8", "1.1", "CLERK_STARTED"));
        engine.processEvent(delayEvent("QCZ8", "1.2", "CLERK_STARTED"));
        engine.processEvent(delayEvent("QCZ8", "2.1", "CLERK_STARTED"));
        engine.processEvent(delayEvent("QCZ8", "3.1", "CLERK_STARTED"));

        var delays = processor.getCraneDelays().get("QCZ8");
        assertEquals(3, delays.size());
        assertEquals("1.2", delays.get(0).delayType());
        assertEquals("2.1", delays.get(1).delayType());
        assertEquals("3.1", delays.get(2).delayType());
    }

    @Test
    @DisplayName("Should ignore delay with blank cheShortName")
    void delayBlankName() {
        engine.processEvent(delayEvent("", "1.1", "CLERK_STARTED"));

        assertTrue(processor.getCraneDelays().isEmpty());
    }

    @Test
    @DisplayName("Should capture and restore state including crane delays")
    void captureAndRestoreWithDelays() {
        engine.processEvent(qcEvent("QCZ8", "EMERALD", "B80"));
        engine.processEvent(delayEvent("QCZ8", "1.1", "CLERK_STARTED"));
        var state = processor.captureState();

        engine.processEvent(delayEvent("QCZ8", "2.1", "CLERK_STARTED"));
        assertEquals(2, processor.getCraneDelays().get("QCZ8").size());

        processor.restoreState(state);
        assertEquals(1, processor.getCraneDelays().get("QCZ8").size());
        assertEquals("1.1", processor.getCraneDelays().get("QCZ8").getFirst().delayType());
    }
}
