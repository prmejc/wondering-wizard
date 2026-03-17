package com.wonderingwizard.processors;

import com.wonderingwizard.domain.takt.ActionType;
import com.wonderingwizard.domain.takt.DeviceType;
import com.wonderingwizard.engine.EventProcessingEngine;
import com.wonderingwizard.engine.EventPropagatingEngine;
import com.wonderingwizard.events.DigitalMapEvent;
import com.wonderingwizard.events.LoadMode;
import com.wonderingwizard.events.WorkInstructionEvent;
import com.wonderingwizard.events.MoveStage;
import com.wonderingwizard.events.WorkQueueMessage;
import com.wonderingwizard.events.WorkQueueStatus;
import com.wonderingwizard.sideeffects.ScheduleCreated;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class DigitalMapProcessorTest {

    private DigitalMapProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new DigitalMapProcessor();
    }

    // ── EventProcessor tests ─────────────────────────────────────────

    @Test
    void processDigitalMapEvent_parsesMapAndStoresState() {
        var payload = createMapPayload("""
                <?xml version='1.0' encoding='UTF-8'?>
                <osm version="0.6">
                  <node id="1" lat="35.887" lon="-5.494" version="1">
                    <tag k="amenity" v="apmt_poi_destination" />
                    <tag k="name" v="YARD-A" />
                  </node>
                  <node id="2" lat="35.888" lon="-5.493" version="1">
                    <tag k="amenity" v="apmt_poi_destination" />
                    <tag k="name" v="QC-01" />
                  </node>
                  <node id="10" lat="35.887" lon="-5.494" version="1" />
                  <node id="11" lat="35.8875" lon="-5.4935" version="1" />
                  <node id="12" lat="35.888" lon="-5.493" version="1" />
                  <way id="100" version="1">
                    <nd ref="10" />
                    <nd ref="11" />
                    <nd ref="12" />
                    <tag k="highway" v="service" />
                    <tag k="apmt_average_speed" v="10" />
                  </way>
                </osm>
                """);

        var sideEffects = processor.process(new DigitalMapEvent(payload));

        assertTrue(sideEffects.isEmpty(), "DigitalMapEvent should produce no side effects");
        assertTrue(processor.isMapLoaded());
    }

    @Test
    void processDigitalMapEvent_emptyPayload_noMapLoaded() {
        var sideEffects = processor.process(new DigitalMapEvent(""));

        assertTrue(sideEffects.isEmpty());
        assertFalse(processor.isMapLoaded());
    }

    @Test
    void processDigitalMapEvent_invalidPayload_noMapLoaded() {
        var sideEffects = processor.process(new DigitalMapEvent("not json"));

        assertTrue(sideEffects.isEmpty());
        assertFalse(processor.isMapLoaded());
    }

    @Test
    void processNonDigitalMapEvent_ignored() {
        var sideEffects = processor.process(
                new WorkQueueMessage(1L, WorkQueueStatus.ACTIVE, 0, null));

        assertTrue(sideEffects.isEmpty());
        assertFalse(processor.isMapLoaded());
    }

    // ── Pathfinding tests ────────────────────────────────────────────

    @Test
    void findPathDuration_directPath() {
        loadSimpleMap();
        int duration = processor.findPathDuration("A", "C");
        assertTrue(duration > 0, "Should find a path from A to C");
    }

    @Test
    void findPathDuration_noPath() {
        loadSimpleMap();
        assertEquals(-1, processor.findPathDuration("A", "UNKNOWN"));
    }

    @Test
    void findPathDuration_sameNode() {
        loadSimpleMap();
        assertEquals(0, processor.findPathDuration("A", "A"));
    }

    @Test
    void findPathDuration_noMapLoaded() {
        assertEquals(-1, processor.findPathDuration("A", "B"));
    }

    @Test
    void findPathDuration_bidirectional() {
        loadBidirectionalMap();
        int forward = processor.findPathDuration("X", "Y");
        int backward = processor.findPathDuration("Y", "X");
        assertTrue(forward > 0);
        assertEquals(forward, backward, "Bidirectional road should have same duration both ways");
    }

    @Test
    void findPathDuration_oneway() {
        loadOnewayMap();
        int forward = processor.findPathDuration("P", "Q");
        int backward = processor.findPathDuration("Q", "P");
        assertTrue(forward > 0);
        assertEquals(-1, backward, "One-way road should not allow reverse travel");
    }

    // ── SchedulePipelineStep tests ───────────────────────────────────

    @Test
    void enrichTemplates_noMapLoaded_passthrough() {
        var templates = java.util.List.of(
                GraphScheduleBuilder.ActionTemplate.of(ActionType.TT_DRIVE_TO_QC_STANDBY, DeviceType.TT, 100));

        var wi = createWorkInstruction("Y-PTM-1A25E4", "QC-01");

        var result = processor.enrichTemplates(1L, templates, wi);
        assertEquals(templates, result);
    }

    @Test
    void enrichTemplates_withMap_setsFixedDurations() {
        loadMapWithRouteAndStandby("1A25", "B52", "1A25-SB");

        var templates = java.util.List.of(
                GraphScheduleBuilder.ActionTemplate.of(ActionType.TT_DRIVE_UNDER_QC, DeviceType.TT, 99),
                GraphScheduleBuilder.ActionTemplate.of(ActionType.TT_HANDOVER_FROM_QC, DeviceType.TT, 99),
                GraphScheduleBuilder.ActionTemplate.of(ActionType.TT_DRIVE_TO_RTG_PULL, DeviceType.TT, 99),
                GraphScheduleBuilder.ActionTemplate.of(ActionType.TT_DRIVE_TO_RTG_UNDER, DeviceType.TT, 99),
                GraphScheduleBuilder.ActionTemplate.of(ActionType.TT_HANDOVER_TO_RTG, DeviceType.TT, 99),
                GraphScheduleBuilder.ActionTemplate.of(ActionType.TT_DRIVE_TO_BUFFER, DeviceType.TT, 99),
                GraphScheduleBuilder.ActionTemplate.of(ActionType.QC_LIFT, DeviceType.QC, 80)
        );

        var wi = createWorkInstruction("Y-PTM-1A25E4", "QC-01");

        var result = processor.enrichTemplates(1L, templates, wi);

        // Fixed durations
        assertEquals(20, result.get(0).durationSeconds(), "drive under QC");
        assertEquals(20, result.get(1).durationSeconds(), "handover from QC");
        assertEquals(20, result.get(2).durationSeconds(), "drive to RTG pull");
        assertEquals(20, result.get(3).durationSeconds(), "drive to RTG under");
        assertEquals(20, result.get(4).durationSeconds(), "handover to RTG");
        assertEquals(1, result.get(5).durationSeconds(), "drive to buffer");
        // Non-TT actions unchanged
        assertEquals(80, result.get(6).durationSeconds(), "QC lift unchanged");
    }

    @Test
    void enrichTemplates_withMap_computesDriveDurations() {
        loadMapWithRouteAndStandby("1A25", "B52", "1A25-SB");

        int yardToBerth = processor.findPathDuration("1A25", "B52");
        int berthToStandby = processor.findPathDuration("B52", "1A25-SB");
        assertTrue(yardToBerth > 0);
        assertTrue(berthToStandby > 0);

        var templates = java.util.List.of(
                GraphScheduleBuilder.ActionTemplate.of(ActionType.TT_DRIVE_TO_QC_STANDBY, DeviceType.TT, 99),
                GraphScheduleBuilder.ActionTemplate.of(ActionType.TT_DRIVE_TO_RTG_STANDBY, DeviceType.TT, 99)
        );

        var wi = createWorkInstruction("Y-PTM-1A25E4", "QC-01");

        var result = processor.enrichTemplates(1L, templates, wi);

        assertEquals(yardToBerth - 20, result.get(0).durationSeconds(), "QC standby = yardToBerth - 20");
        assertEquals(berthToStandby, result.get(1).durationSeconds(), "RTG standby = berthToStandby");
    }

    @Test
    void enrichTemplates_invalidPosition_passthrough() {
        loadSimpleMap();

        var templates = java.util.List.of(
                GraphScheduleBuilder.ActionTemplate.of(ActionType.TT_DRIVE_TO_QC_STANDBY, DeviceType.TT, 30));

        var wi = createWorkInstruction("INVALID", "QC-01");

        var result = processor.enrichTemplates(1L, templates, wi);
        assertEquals(30, result.get(0).durationSeconds());
    }

    @Test
    void enrichTemplates_noStandby_fallbackDuration() {
        // Map has 1A25 and B52 but no standby for 1A25
        loadMapWithRoute("1A25", "B52");

        var templates = java.util.List.of(
                GraphScheduleBuilder.ActionTemplate.of(ActionType.TT_DRIVE_TO_RTG_STANDBY, DeviceType.TT, 99)
        );

        var wi = createWorkInstruction("Y-PTM-1A25E4", "QC-01");

        var result = processor.enrichTemplates(1L, templates, wi);
        assertEquals(60, result.get(0).durationSeconds(), "Should use fallback when no standby");
    }

    @Test
    void toYardPoi_parsesBlockAndBay() {
        assertEquals("1L20", DigitalMapProcessor.toYardPoi("Y-PTM-1L20E4"));
        assertEquals("1A25", DigitalMapProcessor.toYardPoi("Y-PTM-1A25E4"));
        assertNull(DigitalMapProcessor.toYardPoi("INVALID"));
        assertNull(DigitalMapProcessor.toYardPoi(null));
    }

    // ── State capture/restore tests ──────────────────────────────────

    @Test
    void captureAndRestoreState() {
        loadSimpleMap();
        assertTrue(processor.isMapLoaded());
        int origDuration = processor.findPathDuration("A", "C");
        assertTrue(origDuration > 0);

        var state = processor.captureState();

        // Load a different map
        loadBidirectionalMap();
        assertEquals(-1, processor.findPathDuration("A", "C"), "Old path should not exist in new map");

        // Restore
        processor.restoreState(state);
        assertTrue(processor.isMapLoaded());
        assertEquals(origDuration, processor.findPathDuration("A", "C"));
    }

    // ── Integration test: pipeline in WorkQueueProcessor ─────────────

    @Test
    void pipelineIntegration_noMapLoaded_defaultDurations() {
        // No map loaded — processor should passthrough
        var workQueueProcessor = new WorkQueueProcessor(() -> 30, () -> 0, true);
        workQueueProcessor.registerStep(processor);

        var engine = new EventPropagatingEngine(new EventProcessingEngine());
        engine.register(workQueueProcessor);

        Instant moveTime = Instant.parse("2024-01-01T00:00:00Z");

        engine.processEvent(new WorkInstructionEvent(
                1L, 1L, "QC-01", MoveStage.PLANNED,
                moveTime, 120, 60, "",
                false, false, false, 0, "Y-PTM-1A25E4", "MAEU1234567"));

        var sideEffects = engine.processEvent(
                new WorkQueueMessage(1L, WorkQueueStatus.ACTIVE, 0, LoadMode.DSCH));

        var scheduleCreated = sideEffects.stream()
                .filter(se -> se instanceof ScheduleCreated)
                .map(se -> (ScheduleCreated) se)
                .findFirst()
                .orElseThrow();

        // Should still produce a valid schedule with default durations
        assertFalse(scheduleCreated.takts().isEmpty());
    }

    @Test
    void pipelineIntegration_realMapFile() throws Exception {
        // Load the actual digital map from resources
        var is = getClass().getResourceAsStream("/digitalmap.json");
        if (is == null) {
            return; // Skip if file not available
        }

        String mapJson = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        processor.process(new DigitalMapEvent(mapJson));

        assertTrue(processor.isMapLoaded(), "Real map should load successfully");

        // Test pathfinding with real POI names
        int duration = processor.findPathDuration("1A25", "B52");
        assertTrue(duration > 0, "Should find path from 1A25 to B52, got: " + duration);
        assertTrue(duration < 600, "Path should be under 10 minutes, got: " + duration + "s");
    }

    @Test
    void mapUpdate_replacesExistingMap() {
        loadSimpleMap();
        assertTrue(processor.isMapLoaded());

        loadBidirectionalMap();
        assertTrue(processor.isMapLoaded());

        // Old map paths should not work
        assertEquals(-1, processor.findPathDuration("A", "C"));
        // New map paths should work
        assertTrue(processor.findPathDuration("X", "Y") > 0);
    }

    // ── Standby location tests ─────────────────────────────────────

    @Test
    void findStandbyLocation_returnsDeclaredStandby() {
        loadMapWithStandby();
        assertEquals("A-SB", processor.findStandbyLocation("A"));
    }

    @Test
    void findStandbyLocation_noStandbyDeclared() {
        loadMapWithStandby();
        assertNull(processor.findStandbyLocation("A-SB"));
    }

    @Test
    void findStandbyLocation_unknownPoi() {
        loadMapWithStandby();
        assertNull(processor.findStandbyLocation("UNKNOWN"));
    }

    @Test
    void findStandbyLocation_noMapLoaded() {
        assertNull(processor.findStandbyLocation("A"));
    }

    @Test
    void findStandbyLocation_realMap() throws Exception {
        var is = getClass().getResourceAsStream("/digitalmap.json");
        if (is == null) return;
        String mapJson = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        processor.process(new DigitalMapEvent(mapJson));

        // 4D11 should have a standby location in the real map
        String standby = processor.findStandbyLocation("4D11");
        assertNotNull(standby, "4D11 should have a standby location");
        assertFalse(standby.isEmpty());
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private void loadSimpleMap() {
        String osm = """
                <?xml version='1.0' encoding='UTF-8'?>
                <osm version="0.6">
                  <node id="1" lat="35.887" lon="-5.494" version="1">
                    <tag k="amenity" v="apmt_poi_destination" />
                    <tag k="name" v="A" />
                  </node>
                  <node id="2" lat="35.888" lon="-5.493" version="1">
                    <tag k="amenity" v="apmt_poi_destination" />
                    <tag k="name" v="B" />
                  </node>
                  <node id="3" lat="35.889" lon="-5.492" version="1">
                    <tag k="amenity" v="apmt_poi_destination" />
                    <tag k="name" v="C" />
                  </node>
                  <node id="10" lat="35.887" lon="-5.494" version="1" />
                  <node id="11" lat="35.888" lon="-5.493" version="1" />
                  <node id="12" lat="35.889" lon="-5.492" version="1" />
                  <way id="100" version="1">
                    <nd ref="10" />
                    <nd ref="11" />
                    <nd ref="12" />
                    <tag k="highway" v="service" />
                    <tag k="apmt_average_speed" v="20" />
                  </way>
                </osm>
                """;
        processor.process(new DigitalMapEvent(createMapPayload(osm)));
    }

    private void loadBidirectionalMap() {
        String osm = """
                <?xml version='1.0' encoding='UTF-8'?>
                <osm version="0.6">
                  <node id="1" lat="35.887" lon="-5.494" version="1">
                    <tag k="amenity" v="apmt_poi_destination" />
                    <tag k="name" v="X" />
                  </node>
                  <node id="2" lat="35.889" lon="-5.492" version="1">
                    <tag k="amenity" v="apmt_poi_destination" />
                    <tag k="name" v="Y" />
                  </node>
                  <node id="10" lat="35.887" lon="-5.494" version="1" />
                  <node id="11" lat="35.889" lon="-5.492" version="1" />
                  <way id="100" version="1">
                    <nd ref="10" />
                    <nd ref="11" />
                    <tag k="highway" v="service" />
                    <tag k="apmt_average_speed" v="20" />
                    <tag k="oneway" v="no" />
                  </way>
                </osm>
                """;
        processor.process(new DigitalMapEvent(createMapPayload(osm)));
    }

    private void loadOnewayMap() {
        String osm = """
                <?xml version='1.0' encoding='UTF-8'?>
                <osm version="0.6">
                  <node id="1" lat="35.887" lon="-5.494" version="1">
                    <tag k="amenity" v="apmt_poi_destination" />
                    <tag k="name" v="P" />
                  </node>
                  <node id="2" lat="35.889" lon="-5.492" version="1">
                    <tag k="amenity" v="apmt_poi_destination" />
                    <tag k="name" v="Q" />
                  </node>
                  <node id="10" lat="35.887" lon="-5.494" version="1" />
                  <node id="11" lat="35.889" lon="-5.492" version="1" />
                  <way id="100" version="1">
                    <nd ref="10" />
                    <nd ref="11" />
                    <tag k="highway" v="service" />
                    <tag k="apmt_average_speed" v="20" />
                    <tag k="oneway" v="yes" />
                  </way>
                </osm>
                """;
        processor.process(new DigitalMapEvent(createMapPayload(osm)));
    }

    private void loadMapWithRoute(String fromName, String toName) {
        String osm = """
                <?xml version='1.0' encoding='UTF-8'?>
                <osm version="0.6">
                  <node id="1" lat="35.887" lon="-5.494" version="1">
                    <tag k="amenity" v="apmt_poi_destination" />
                    <tag k="name" v="%s" />
                  </node>
                  <node id="2" lat="35.889" lon="-5.492" version="1">
                    <tag k="amenity" v="apmt_poi_destination" />
                    <tag k="name" v="%s" />
                  </node>
                  <node id="10" lat="35.887" lon="-5.494" version="1" />
                  <node id="11" lat="35.889" lon="-5.492" version="1" />
                  <way id="100" version="1">
                    <nd ref="10" />
                    <nd ref="11" />
                    <tag k="highway" v="service" />
                    <tag k="apmt_average_speed" v="20" />
                  </way>
                </osm>
                """.formatted(fromName, toName);
        processor.process(new DigitalMapEvent(createMapPayload(osm)));
    }

    private void loadMapWithRouteAndStandby(String fromName, String toName, String standbyName) {
        String osm = """
                <?xml version='1.0' encoding='UTF-8'?>
                <osm version="0.6">
                  <node id="1" lat="35.887" lon="-5.494" version="1">
                    <tag k="amenity" v="apmt_poi_destination" />
                    <tag k="name" v="%s" />
                    <tag k="apmt_poi_standby_bay" v="%s" />
                  </node>
                  <node id="2" lat="35.889" lon="-5.492" version="1">
                    <tag k="amenity" v="apmt_poi_destination" />
                    <tag k="name" v="%s" />
                  </node>
                  <node id="3" lat="35.890" lon="-5.491" version="1">
                    <tag k="amenity" v="apmt_poi_destination" />
                    <tag k="name" v="%s" />
                  </node>
                  <node id="10" lat="35.887" lon="-5.494" version="1" />
                  <node id="11" lat="35.889" lon="-5.492" version="1" />
                  <node id="12" lat="35.890" lon="-5.491" version="1" />
                  <way id="100" version="1">
                    <nd ref="10" />
                    <nd ref="11" />
                    <nd ref="12" />
                    <tag k="highway" v="service" />
                    <tag k="apmt_average_speed" v="20" />
                    <tag k="oneway" v="no" />
                  </way>
                </osm>
                """.formatted(fromName, standbyName, toName, standbyName);
        processor.process(new DigitalMapEvent(createMapPayload(osm)));
    }

    private void loadMapWithStandby() {
        String osm = """
                <?xml version='1.0' encoding='UTF-8'?>
                <osm version="0.6">
                  <node id="1" lat="35.887" lon="-5.494" version="1">
                    <tag k="amenity" v="apmt_poi_destination" />
                    <tag k="name" v="A" />
                    <tag k="apmt_poi_standby_bay" v="A-SB" />
                  </node>
                  <node id="2" lat="35.888" lon="-5.493" version="1">
                    <tag k="amenity" v="apmt_poi_destination" />
                    <tag k="name" v="A-SB" />
                  </node>
                  <node id="10" lat="35.887" lon="-5.494" version="1" />
                  <node id="11" lat="35.888" lon="-5.493" version="1" />
                  <way id="100" version="1">
                    <nd ref="10" />
                    <nd ref="11" />
                    <tag k="highway" v="service" />
                    <tag k="apmt_average_speed" v="20" />
                  </way>
                </osm>
                """;
        processor.process(new DigitalMapEvent(createMapPayload(osm)));
    }

    /**
     * Creates a digital map JSON payload wrapping base64-gzipped OSM XML.
     */
    static String createMapPayload(String osmXml) {
        try {
            var baos = new ByteArrayOutputStream();
            try (var gzos = new GZIPOutputStream(baos)) {
                gzos.write(osmXml.getBytes(StandardCharsets.UTF_8));
            }
            String b64 = Base64.getEncoder().encodeToString(baos.toByteArray());
            return "{\"terminalCode\":\"TEST\",\"terminalLayout\":\"" + b64 + "\"}";
        } catch (Exception e) {
            throw new RuntimeException("Failed to create test map payload", e);
        }
    }

    private WorkInstructionEvent createWorkInstruction(String toPosition, String fetchChe) {
        return new WorkInstructionEvent(
                1L, 1L, fetchChe, MoveStage.PLANNED,
                Instant.parse("2024-01-01T00:00:00Z"), 120, 60, "",
                false, false, false, 0, toPosition, "MAEU1234567");
    }
}
