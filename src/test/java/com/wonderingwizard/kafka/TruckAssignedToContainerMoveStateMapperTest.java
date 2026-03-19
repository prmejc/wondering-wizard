package com.wonderingwizard.kafka;

import com.wonderingwizard.events.WorkInstructionEvent;
import com.wonderingwizard.kafka.messages.ContainerMoveStateKafkaMessage;
import com.wonderingwizard.sideeffects.TruckAssigned;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TruckAssigned to ContainerMoveState Mapper")
class TruckAssignedToContainerMoveStateMapperTest {

    private TruckAssignedToContainerMoveStateMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new TruckAssignedToContainerMoveStateMapper("MAPTM", "FESv4.0.18");
    }

    @Test
    @DisplayName("Should map TruckAssigned with work instructions to ContainerMoveState")
    void shouldMapWithWorkInstructions() {
        var wi = new WorkInstructionEvent(
                "", 30836858L, 100L, "RTZ11", "Planned", null,
                0, 60, "QCZ9", false, false, false, 0,
                "V-FES1234-780678", "MAROC456321");
        var assigned = new TruckAssigned(UUID.randomUUID(), 100L, "TG67", 42L, List.of(wi));

        ContainerMoveStateKafkaMessage msg = mapper.mapToMessage(assigned);

        assertNotNull(msg);
        assertEquals("TT_ASSIGNED", msg.containerMoveState());
        assertEquals("RTZ11", msg.fetchCHEName());
        assertEquals("TG67", msg.carryCHEName());
        assertEquals("QCZ9", msg.putCHEName());
        assertEquals(30836858L, msg.workInstructionId());
        assertEquals("V-FES1234-780678", msg.toPosition());
        assertEquals("MAROC456321", msg.containerId());
        assertEquals("MAPTM", msg.terminalCode());
        assertEquals("FESv4.0.18", msg.eventSource());
        assertEquals(0, msg.messageSequenceNumber());
    }

    @Test
    @DisplayName("Should return null when no work instructions")
    void shouldReturnNullWithoutWI() {
        var assigned = new TruckAssigned(UUID.randomUUID(), 100L, "TG67", 42L, List.of());

        assertNull(mapper.mapToMessage(assigned));
    }

    @Test
    @DisplayName("Should produce valid Avro record")
    void shouldProduceAvroRecord() {
        var wi = new WorkInstructionEvent(
                "", 123L, 100L, "RTZ11", "Planned", null,
                0, 60, "QCZ9", false, false, false, 0,
                "V-FES-001", "MSKU123");
        var assigned = new TruckAssigned(UUID.randomUUID(), 100L, "TG04", 1L, List.of(wi));

        var record = mapper.map(assigned);

        assertNotNull(record);
        assertEquals("TT_ASSIGNED", record.get("containerMoveState").toString());
        assertEquals("TG04", record.get("carryCHEName").toString());
        assertEquals(123L, record.get("workInstructionId"));
    }
}
