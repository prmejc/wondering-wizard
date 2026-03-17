package com.wonderingwizard.server;

import com.wonderingwizard.engine.Event;
import com.wonderingwizard.events.ActionCompletedEvent;
import com.wonderingwizard.events.LoadMode;
import com.wonderingwizard.events.OverrideActionConditionEvent;
import com.wonderingwizard.events.OverrideConditionEvent;
import com.wonderingwizard.events.TimeEvent;
import com.wonderingwizard.events.WorkInstructionEvent;
import com.wonderingwizard.events.MoveStage;
import com.wonderingwizard.events.WorkQueueMessage;
import com.wonderingwizard.events.WorkQueueStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("EventDeserializer Tests")
class EventDeserializerTest {

    @Nested
    @DisplayName("TimeEvent deserialization")
    class TimeEventTests {

        @Test
        @DisplayName("Should deserialize TimeEvent")
        void deserializesTimeEvent() {
            Map<String, String> fields = Map.of(
                    "type", "TimeEvent",
                    "timestamp", "2025-01-01T12:00:00Z");

            Event event = EventDeserializer.deserialize(fields);

            assertInstanceOf(TimeEvent.class, event);
            assertEquals(Instant.parse("2025-01-01T12:00:00Z"), ((TimeEvent) event).timestamp());
        }
    }

    @Nested
    @DisplayName("WorkQueueMessage deserialization")
    class WorkQueueMessageTests {

        @Test
        @DisplayName("Should deserialize WorkQueueMessage with loadMode")
        void deserializesWorkQueueMessage() {
            Map<String, String> fields = Map.of(
                    "type", "WorkQueueMessage",
                    "workQueueId", "42",
                    "status", "ACTIVE",
                    "qcMudaSeconds", "5",
                    "loadMode", "DSCH");

            Event event = EventDeserializer.deserialize(fields);

            assertInstanceOf(WorkQueueMessage.class, event);
            WorkQueueMessage wqm = (WorkQueueMessage) event;
            assertEquals(42L, wqm.workQueueId());
            assertEquals(WorkQueueStatus.ACTIVE, wqm.status());
            assertEquals(5, wqm.qcMudaSeconds());
            assertEquals(LoadMode.DSCH, wqm.loadMode());
        }

        @Test
        @DisplayName("Should handle null loadMode")
        void handlesNullLoadMode() {
            Map<String, String> fields = new HashMap<>();
            fields.put("type", "WorkQueueMessage");
            fields.put("workQueueId", "1");
            fields.put("status", "INACTIVE");
            fields.put("loadMode", null);

            Event event = EventDeserializer.deserialize(fields);

            assertInstanceOf(WorkQueueMessage.class, event);
            assertNull(((WorkQueueMessage) event).loadMode());
        }
    }

    @Nested
    @DisplayName("WorkInstructionEvent deserialization")
    class WorkInstructionEventTests {

        @Test
        @DisplayName("Should deserialize WorkInstructionEvent with all fields")
        void deserializesWorkInstructionEvent() {
            Map<String, String> fields = Map.ofEntries(
                    Map.entry("type", "WorkInstructionEvent"),
                    Map.entry("workInstructionId", "100"),
                    Map.entry("workQueueId", "1"),
                    Map.entry("fetchChe", "RTG-01"),
                    Map.entry("workInstructionMoveStage", "Planned"),
                    Map.entry("estimatedMoveTime", "2025-01-01T12:30:00Z"),
                    Map.entry("estimatedCycleTimeSeconds", "120"),
                    Map.entry("estimatedRtgCycleTimeSeconds", "60"),
                    Map.entry("putChe", "QC-01"),
                    Map.entry("isTwinFetch", "true"),
                    Map.entry("isTwinPut", "false"),
                    Map.entry("isTwinCarry", "false"),
                    Map.entry("twinCompanionWorkInstruction", "101"),
                    Map.entry("toPosition", "Y-PTM-1L20E4"));

            Event event = EventDeserializer.deserialize(fields);

            assertInstanceOf(WorkInstructionEvent.class, event);
            WorkInstructionEvent wie = (WorkInstructionEvent) event;
            assertEquals(100L, wie.workInstructionId());
            assertEquals(1L, wie.workQueueId());
            assertEquals("RTG-01", wie.fetchChe());
            assertEquals("Planned", wie.workInstructionMoveStage());
            assertEquals(Instant.parse("2025-01-01T12:30:00Z"), wie.estimatedMoveTime());
            assertEquals(120, wie.estimatedCycleTimeSeconds());
            assertEquals(60, wie.estimatedRtgCycleTimeSeconds());
            assertEquals("QC-01", wie.putChe());
            assertTrue(wie.isTwinFetch());
            assertFalse(wie.isTwinPut());
            assertEquals(101L, wie.twinCompanionWorkInstruction());
            assertEquals("Y-PTM-1L20E4", wie.toPosition());
        }

        @Test
        @DisplayName("Should deserialize WorkInstructionEvent with legacy 'status' key")
        void deserializesWorkInstructionEventWithLegacyStatusKey() {
            Map<String, String> fields = Map.ofEntries(
                    Map.entry("type", "WorkInstructionEvent"),
                    Map.entry("workInstructionId", "100"),
                    Map.entry("workQueueId", "1"),
                    Map.entry("fetchChe", "RTG-01"),
                    Map.entry("status", "Carry Underway"),
                    Map.entry("estimatedMoveTime", "2025-01-01T12:30:00Z"),
                    Map.entry("estimatedCycleTimeSeconds", "120"),
                    Map.entry("estimatedRtgCycleTimeSeconds", "60"));

            Event event = EventDeserializer.deserialize(fields);

            assertInstanceOf(WorkInstructionEvent.class, event);
            WorkInstructionEvent wie = (WorkInstructionEvent) event;
            assertEquals("Carry Underway", wie.workInstructionMoveStage());
        }
    }

    @Nested
    @DisplayName("ActionCompletedEvent deserialization")
    class ActionCompletedEventTests {

        @Test
        @DisplayName("Should deserialize ActionCompletedEvent")
        void deserializesActionCompletedEvent() {
            UUID actionId = UUID.randomUUID();
            Map<String, String> fields = Map.of(
                    "type", "ActionCompletedEvent",
                    "actionId", actionId.toString(),
                    "workQueueId", "1");

            Event event = EventDeserializer.deserialize(fields);

            assertInstanceOf(ActionCompletedEvent.class, event);
            ActionCompletedEvent ace = (ActionCompletedEvent) event;
            assertEquals(actionId, ace.actionId());
            assertEquals(1L, ace.workQueueId());
        }
    }

    @Nested
    @DisplayName("OverrideConditionEvent deserialization")
    class OverrideConditionEventTests {

        @Test
        @DisplayName("Should deserialize OverrideConditionEvent")
        void deserializesOverrideConditionEvent() {
            Map<String, String> fields = Map.of(
                    "type", "OverrideConditionEvent",
                    "workQueueId", "1",
                    "taktName", "TAKT100",
                    "conditionId", "time");

            Event event = EventDeserializer.deserialize(fields);

            assertInstanceOf(OverrideConditionEvent.class, event);
            OverrideConditionEvent oce = (OverrideConditionEvent) event;
            assertEquals(1L, oce.workQueueId());
            assertEquals("TAKT100", oce.taktName());
            assertEquals("time", oce.conditionId());
        }
    }

    @Nested
    @DisplayName("OverrideActionConditionEvent deserialization")
    class OverrideActionConditionEventTests {

        @Test
        @DisplayName("Should deserialize OverrideActionConditionEvent")
        void deserializesOverrideActionConditionEvent() {
            UUID actionId = UUID.randomUUID();
            Map<String, String> fields = Map.of(
                    "type", "OverrideActionConditionEvent",
                    "workQueueId", "1",
                    "actionId", actionId.toString(),
                    "conditionId", "action-dependencies");

            Event event = EventDeserializer.deserialize(fields);

            assertInstanceOf(OverrideActionConditionEvent.class, event);
            OverrideActionConditionEvent oace = (OverrideActionConditionEvent) event;
            assertEquals(1L, oace.workQueueId());
            assertEquals(actionId, oace.actionId());
            assertEquals("action-dependencies", oace.conditionId());
        }
    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("Should throw for missing type field")
        void throwsForMissingType() {
            Map<String, String> fields = Map.of("timestamp", "2025-01-01T12:00:00Z");

            assertThrows(IllegalArgumentException.class,
                    () -> EventDeserializer.deserialize(fields));
        }

        @Test
        @DisplayName("Should throw for unknown type")
        void throwsForUnknownType() {
            Map<String, String> fields = Map.of("type", "UnknownEvent");

            assertThrows(IllegalArgumentException.class,
                    () -> EventDeserializer.deserialize(fields));
        }
    }

    @Nested
    @DisplayName("Roundtrip: serialize then deserialize")
    class RoundtripTests {

        @Test
        @DisplayName("Should roundtrip TimeEvent through serialization")
        void roundtripTimeEvent() {
            TimeEvent original = new TimeEvent(Instant.parse("2025-01-01T12:00:00Z"));
            String json = JsonSerializer.serialize(original);
            Map<String, String> fields = JsonParser.parseObject(json);
            Event deserialized = EventDeserializer.deserialize(fields);

            assertEquals(original, deserialized);
        }

        @Test
        @DisplayName("Should roundtrip WorkQueueMessage through serialization")
        void roundtripWorkQueueMessage() {
            WorkQueueMessage original = new WorkQueueMessage(42L, WorkQueueStatus.ACTIVE, 5, LoadMode.DSCH);
            String json = JsonSerializer.serialize(original);
            Map<String, String> fields = JsonParser.parseObject(json);
            Event deserialized = EventDeserializer.deserialize(fields);

            assertEquals(original, deserialized);
        }

        @Test
        @DisplayName("Should roundtrip WorkInstructionEvent through serialization")
        void roundtripWorkInstructionEvent() {
            WorkInstructionEvent original = new WorkInstructionEvent(
                    100L, 1L, "RTG-01", MoveStage.PLANNED,
                    Instant.parse("2025-01-01T12:30:00Z"), 120, 60,
                    "QC-01", true, false, false, 101L, "Y-PTM-1L20E4");
            String json = JsonSerializer.serialize(original);
            Map<String, String> fields = JsonParser.parseObject(json);
            Event deserialized = EventDeserializer.deserialize(fields);

            assertEquals(original, deserialized);
        }
    }
}
