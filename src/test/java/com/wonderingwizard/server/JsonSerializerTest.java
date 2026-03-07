package com.wonderingwizard.server;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.DeviceType;
import com.wonderingwizard.domain.takt.Takt;
import com.wonderingwizard.events.ActionCompletedEvent;
import com.wonderingwizard.events.TimeEvent;
import com.wonderingwizard.events.WorkInstructionEvent;
import com.wonderingwizard.events.WorkInstructionStatus;
import com.wonderingwizard.events.WorkQueueMessage;
import com.wonderingwizard.events.WorkQueueStatus;
import com.wonderingwizard.sideeffects.ActionActivated;
import com.wonderingwizard.sideeffects.ActionCompleted;
import com.wonderingwizard.sideeffects.AlarmSet;
import com.wonderingwizard.sideeffects.AlarmTriggered;
import com.wonderingwizard.sideeffects.ScheduleAborted;
import com.wonderingwizard.sideeffects.ScheduleCreated;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JsonSerializer Tests")
class JsonSerializerTest {

    @Nested
    @DisplayName("Primitive types")
    class PrimitiveTests {

        @Test
        @DisplayName("Should serialize null")
        void serializesNull() {
            assertEquals("null", JsonSerializer.serialize(null));
        }

        @Test
        @DisplayName("Should serialize strings with escaping")
        void serializesStrings() {
            assertEquals("\"hello\"", JsonSerializer.serialize("hello"));
            assertEquals("\"hello\\\"world\"", JsonSerializer.serialize("hello\"world"));
            assertEquals("\"line1\\nline2\"", JsonSerializer.serialize("line1\nline2"));
        }

        @Test
        @DisplayName("Should serialize numbers")
        void serializesNumbers() {
            assertEquals("42", JsonSerializer.serialize(42));
            assertEquals("3.14", JsonSerializer.serialize(3.14));
        }

        @Test
        @DisplayName("Should serialize booleans")
        void serializesBooleans() {
            assertEquals("true", JsonSerializer.serialize(true));
            assertEquals("false", JsonSerializer.serialize(false));
        }

        @Test
        @DisplayName("Should serialize Instant")
        void serializesInstant() {
            Instant instant = Instant.parse("2024-01-01T10:00:00Z");
            assertEquals("\"2024-01-01T10:00:00Z\"", JsonSerializer.serialize(instant));
        }

        @Test
        @DisplayName("Should serialize UUID")
        void serializesUuid() {
            UUID uuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
            assertEquals("\"550e8400-e29b-41d4-a716-446655440000\"", JsonSerializer.serialize(uuid));
        }

        @Test
        @DisplayName("Should serialize enums")
        void serializesEnums() {
            assertEquals("\"RTG\"", JsonSerializer.serialize(DeviceType.RTG));
            assertEquals("\"ACTIVE\"", JsonSerializer.serialize(WorkQueueStatus.ACTIVE));
        }
    }

    @Nested
    @DisplayName("Collections")
    class CollectionTests {

        @Test
        @DisplayName("Should serialize lists")
        void serializesLists() {
            assertEquals("[1,2,3]", JsonSerializer.serialize(List.of(1, 2, 3)));
            assertEquals("[\"a\",\"b\"]", JsonSerializer.serialize(List.of("a", "b")));
        }

        @Test
        @DisplayName("Should serialize empty list")
        void serializesEmptyList() {
            assertEquals("[]", JsonSerializer.serialize(List.of()));
        }

        @Test
        @DisplayName("Should serialize maps")
        void serializesMaps() {
            String json = JsonSerializer.serialize(Map.of("key", "value"));
            assertTrue(json.contains("\"key\":\"value\""));
        }
    }

    @Nested
    @DisplayName("Event serialization")
    class EventTests {

        @Test
        @DisplayName("Should serialize TimeEvent")
        void serializesTimeEvent() {
            TimeEvent event = new TimeEvent(Instant.parse("2024-01-01T10:00:00Z"));
            String json = JsonSerializer.serialize(event);
            assertTrue(json.contains("\"type\":\"TimeEvent\""));
            assertTrue(json.contains("\"timestamp\":\"2024-01-01T10:00:00Z\""));
        }

        @Test
        @DisplayName("Should serialize WorkQueueMessage")
        void serializesWorkQueueMessage() {
            WorkQueueMessage event = new WorkQueueMessage("WQ-001", WorkQueueStatus.ACTIVE);
            String json = JsonSerializer.serialize(event);
            assertTrue(json.contains("\"type\":\"WorkQueueMessage\""));
            assertTrue(json.contains("\"workQueueId\":\"WQ-001\""));
            assertTrue(json.contains("\"status\":\"ACTIVE\""));
        }

        @Test
        @DisplayName("Should serialize WorkInstructionEvent")
        void serializesWorkInstructionEvent() {
            WorkInstructionEvent event = new WorkInstructionEvent(
                    "WI-001", "WQ-001", "RTG-01", WorkInstructionStatus.PENDING,
                    Instant.parse("2024-01-01T10:00:00Z"));
            String json = JsonSerializer.serialize(event);
            assertTrue(json.contains("\"type\":\"WorkInstructionEvent\""));
            assertTrue(json.contains("\"workInstructionId\":\"WI-001\""));
            assertTrue(json.contains("\"estimatedMoveTime\":\"2024-01-01T10:00:00Z\""));
        }

        @Test
        @DisplayName("Should serialize ActionCompletedEvent")
        void serializesActionCompletedEvent() {
            UUID id = UUID.randomUUID();
            ActionCompletedEvent event = new ActionCompletedEvent(id, "WQ-001");
            String json = JsonSerializer.serialize(event);
            assertTrue(json.contains("\"type\":\"ActionCompletedEvent\""));
            assertTrue(json.contains("\"actionId\":\"" + id + "\""));
        }
    }

    @Nested
    @DisplayName("SideEffect serialization")
    class SideEffectTests {

        @Test
        @DisplayName("Should serialize AlarmSet")
        void serializesAlarmSet() {
            AlarmSet se = new AlarmSet("alarm1", Instant.parse("2024-01-01T10:00:00Z"));
            String json = JsonSerializer.serialize(se);
            assertTrue(json.contains("\"type\":\"AlarmSet\""));
            assertTrue(json.contains("\"alarmName\":\"alarm1\""));
        }

        @Test
        @DisplayName("Should serialize AlarmTriggered")
        void serializesAlarmTriggered() {
            AlarmTriggered se = new AlarmTriggered("alarm1", Instant.parse("2024-01-01T10:00:00Z"));
            String json = JsonSerializer.serialize(se);
            assertTrue(json.contains("\"type\":\"AlarmTriggered\""));
        }

        @Test
        @DisplayName("Should serialize ScheduleCreated")
        void serializesScheduleCreated() {
            ScheduleCreated se = new ScheduleCreated("WQ-001", List.of(),
                    Instant.parse("2024-01-01T10:00:00Z"));
            String json = JsonSerializer.serialize(se);
            assertTrue(json.contains("\"type\":\"ScheduleCreated\""));
            assertTrue(json.contains("\"workQueueId\":\"WQ-001\""));
            assertTrue(json.contains("\"takts\":[]"));
        }

        @Test
        @DisplayName("Should serialize ScheduleAborted")
        void serializesScheduleAborted() {
            ScheduleAborted se = new ScheduleAborted("WQ-001");
            String json = JsonSerializer.serialize(se);
            assertTrue(json.contains("\"type\":\"ScheduleAborted\""));
            assertTrue(json.contains("\"workQueueId\":\"WQ-001\""));
        }

        @Test
        @DisplayName("Should serialize ActionActivated")
        void serializesActionActivated() {
            UUID id = UUID.randomUUID();
            ActionActivated se = new ActionActivated(id, "WQ-001", "TAKT100",
                    "lift container", Instant.parse("2024-01-01T10:00:00Z"));
            String json = JsonSerializer.serialize(se);
            assertTrue(json.contains("\"type\":\"ActionActivated\""));
            assertTrue(json.contains("\"taktName\":\"TAKT100\""));
            assertTrue(json.contains("\"actionDescription\":\"lift container\""));
        }

        @Test
        @DisplayName("Should serialize ActionCompleted")
        void serializesActionCompleted() {
            UUID id = UUID.randomUUID();
            ActionCompleted se = new ActionCompleted(id, "WQ-001", "TAKT100",
                    "lift container", Instant.parse("2024-01-01T10:00:00Z"));
            String json = JsonSerializer.serialize(se);
            assertTrue(json.contains("\"type\":\"ActionCompleted\""));
            assertTrue(json.contains("\"taktName\":\"TAKT100\""));
        }
    }

    @Nested
    @DisplayName("Domain type serialization")
    class DomainTests {

        @Test
        @DisplayName("Should serialize Action")
        void serializesAction() {
            UUID id = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
            Action action = new Action(id, DeviceType.RTG, "lift container", Set.of());
            String json = JsonSerializer.serialize(action);
            assertTrue(json.contains("\"id\":\"550e8400-e29b-41d4-a716-446655440000\""));
            assertTrue(json.contains("\"deviceType\":\"RTG\""));
            assertTrue(json.contains("\"description\":\"lift container\""));
            assertTrue(json.contains("\"dependsOn\":[]"));
        }

        @Test
        @DisplayName("Should serialize Takt")
        void serializesTakt() {
            Action action = Action.create(DeviceType.QC, "test action");
            Instant time = Instant.parse("2024-01-01T10:00:00Z");
            Takt takt = new Takt("TAKT100", List.of(action), time, time);
            String json = JsonSerializer.serialize(takt);
            assertTrue(json.contains("\"name\":\"TAKT100\""));
            assertTrue(json.contains("\"plannedStartTime\":\"2024-01-01T10:00:00Z\""));
            assertTrue(json.contains("\"estimatedStartTime\":\"2024-01-01T10:00:00Z\""));
            assertTrue(json.contains("\"actions\":["));
        }

        @Test
        @DisplayName("Should serialize ScheduleCreated with full takts")
        void serializesScheduleCreatedWithTakts() {
            Action a1 = Action.create(DeviceType.RTG, "action1");
            Action a2 = new Action(UUID.randomUUID(), DeviceType.QC, "action2", Set.of(a1.id()));
            Instant time = Instant.parse("2024-01-01T10:00:00Z");
            Takt takt = new Takt("TAKT100", List.of(a1, a2), time, time);
            ScheduleCreated se = new ScheduleCreated("WQ-001", List.of(takt),
                    Instant.parse("2024-01-01T10:00:00Z"));
            String json = JsonSerializer.serialize(se);
            assertTrue(json.contains("\"name\":\"TAKT100\""));
            assertTrue(json.contains("\"action1\""));
            assertTrue(json.contains("\"action2\""));
            assertTrue(json.contains(a1.id().toString()));
        }
    }
}
