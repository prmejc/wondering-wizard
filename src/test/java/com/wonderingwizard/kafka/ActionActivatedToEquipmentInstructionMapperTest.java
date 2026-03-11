package com.wonderingwizard.kafka;

import com.wonderingwizard.domain.takt.ActionType;
import com.wonderingwizard.domain.takt.DeviceType;
import com.wonderingwizard.events.WorkInstructionStatus;
import com.wonderingwizard.kafka.messages.EquipmentInstructionKafkaMessage;
import com.wonderingwizard.kafka.messages.EquipmentInstructionKafkaMessage.Container;
import com.wonderingwizard.sideeffects.ActionActivated;
import com.wonderingwizard.sideeffects.WorkInstruction;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ActionActivatedToEquipmentInstructionMapper Tests")
class ActionActivatedToEquipmentInstructionMapperTest {

    private static final String TERMINAL_CODE = "ECTDELTA";
    private static final Instant ACTIVATED_AT = Instant.parse("2024-01-01T10:00:00Z");

    private ActionActivatedToEquipmentInstructionMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ActionActivatedToEquipmentInstructionMapper(TERMINAL_CODE);
    }

    @Test
    @DisplayName("Should return null for non-RTG_DRIVE action types")
    void returnsNullForNonRtgDrive() {
        ActionActivated activated = new ActionActivated(
                UUID.randomUUID(), 1L, "TAKT100", ActionType.TT_DRIVE_TO_RTG_PULL, "drive to RTG pull",
                ACTIVATED_AT, DeviceType.TT, List.of()
        );

        assertNull(mapper.mapToMessage(activated));
    }

    @Test
    @DisplayName("Should return null when ActionActivated uses backward-compatible constructor")
    void returnsNullForBackwardCompatibleConstructor() {
        ActionActivated activated = new ActionActivated(
                UUID.randomUUID(), 1L, "TAKT100", "drive to RTG pull", ACTIVATED_AT
        );

        assertNull(mapper.mapToMessage(activated));
    }

    @Test
    @DisplayName("Should return null when RTG_DRIVE has no work instructions")
    void returnsNullForEmptyWorkInstructions() {
        ActionActivated activated = new ActionActivated(
                UUID.randomUUID(), 1L, "TAKT100", ActionType.RTG_DRIVE, "drive",
                ACTIVATED_AT, DeviceType.RTG, List.of()
        );

        assertNull(mapper.mapToMessage(activated));
    }

    @Test
    @DisplayName("Should map RTG_DRIVE with single work instruction to typed record")
    void mapsSingleWorkInstruction() {
        UUID actionId = UUID.randomUUID();
        WorkInstruction wi = new WorkInstruction(
                100L, 1L, "QC01", WorkInstructionStatus.PENDING,
                ACTIVATED_AT, 120, 60, "RTG05",
                false, false, false, 0, "Y01.01.01"
        );

        ActionActivated activated = new ActionActivated(
                actionId, 1L, "TAKT100", ActionType.RTG_DRIVE, "drive",
                ACTIVATED_AT, DeviceType.RTG, List.of(wi)
        );

        EquipmentInstructionKafkaMessage message = mapper.mapToMessage(activated);

        assertNotNull(message);
        assertEquals("drive", message.equipmentInstructionType());
        assertEquals(actionId.toString(), message.equipmentInstructionId());
        assertEquals("Y01.01.01", message.destinationNodeId());
        assertEquals(ACTIVATED_AT.toEpochMilli(), message.targetTime());
        assertEquals("RTG05", message.recipientCHEShortName());
        assertEquals("RTG05", message.destinationCHEShortName());
        assertEquals("RTG", message.recipientCHEKind());
        assertEquals(TERMINAL_CODE, message.terminalCode());
        assertEquals("wondering-wizard", message.eventSource());
    }

    @Test
    @DisplayName("Should populate container records from work instructions")
    void populatesContainerRecords() {
        WorkInstruction wi1 = new WorkInstruction(
                100L, 1L, "QC01", WorkInstructionStatus.PENDING,
                ACTIVATED_AT, 120, 60, "RTG05",
                false, false, false, 0, "Y01.01.01"
        );
        WorkInstruction wi2 = new WorkInstruction(
                101L, 1L, "QC01", WorkInstructionStatus.PENDING,
                ACTIVATED_AT, 120, 60, "RTG05",
                true, true, true, 100, "Y01.01.02"
        );

        ActionActivated activated = new ActionActivated(
                UUID.randomUUID(), 1L, "TAKT100", ActionType.RTG_DRIVE, "drive",
                ACTIVATED_AT, DeviceType.RTG, List.of(wi1, wi2)
        );

        EquipmentInstructionKafkaMessage message = mapper.mapToMessage(activated);
        List<Container> containers = message.containers();

        assertEquals(2, containers.size());

        Container c1 = containers.get(0);
        assertEquals(1L, c1.sequence());
        assertEquals(100L, c1.workInstructionId());
        assertEquals("Y01.01.01", c1.toPosition());
        assertEquals("1", c1.frozenWorkQueueId());

        Container c2 = containers.get(1);
        assertEquals(2L, c2.sequence());
        assertEquals(101L, c2.workInstructionId());
        assertEquals("Y01.01.02", c2.toPosition());
    }

    @Test
    @DisplayName("Should use putChe as recipientCHE for RTG device type")
    void usePutCheForRtg() {
        WorkInstruction wi = new WorkInstruction(
                100L, 1L, "QC01", WorkInstructionStatus.PENDING,
                ACTIVATED_AT, 120, 60, "RTG05",
                false, false, false, 0, "Y01.01.01"
        );

        ActionActivated activated = new ActionActivated(
                UUID.randomUUID(), 1L, "TAKT100", ActionType.RTG_DRIVE, "drive",
                ACTIVATED_AT, DeviceType.RTG, List.of(wi)
        );

        EquipmentInstructionKafkaMessage message = mapper.mapToMessage(activated);

        assertEquals("RTG05", message.recipientCHEShortName());
    }

    @Test
    @DisplayName("Should return null for non-RTG_DRIVE action types like QC_LIFT")
    void returnsNullForNonRtgDriveActionType() {
        WorkInstruction wi = new WorkInstruction(
                100L, 1L, "QC01", WorkInstructionStatus.PENDING,
                ACTIVATED_AT, 120, 60, "RTG05",
                false, false, false, 0, "Y01.01.01"
        );

        ActionActivated activated = new ActionActivated(
                UUID.randomUUID(), 1L, "TAKT100", ActionType.QC_LIFT, "QC Lift",
                ACTIVATED_AT, DeviceType.QC, List.of(wi)
        );

        assertNull(mapper.mapToMessage(activated));
    }

    @Test
    @DisplayName("Should produce valid Avro GenericRecord via toAvro()")
    void producesValidAvroRecord() {
        UUID actionId = UUID.randomUUID();
        WorkInstruction wi = new WorkInstruction(
                100L, 1L, "QC01", WorkInstructionStatus.PENDING,
                ACTIVATED_AT, 120, 60, "RTG05",
                false, false, false, 0, "Y01.01.01"
        );

        ActionActivated activated = new ActionActivated(
                actionId, 1L, "TAKT100", ActionType.RTG_DRIVE, "drive",
                ACTIVATED_AT, DeviceType.RTG, List.of(wi)
        );

        GenericRecord avro = mapper.map(activated);

        assertNotNull(avro);
        assertEquals("drive", avro.get("equipmentInstructionType").toString());
        assertEquals(actionId.toString(), avro.get("equipmentInstructionId").toString());
        assertEquals(TERMINAL_CODE, avro.get("terminalCode").toString());
    }
}
