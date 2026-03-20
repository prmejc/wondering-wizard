package com.wonderingwizard.kafka;

import com.wonderingwizard.domain.takt.ActionType;
import com.wonderingwizard.domain.takt.DeviceType;
import com.wonderingwizard.events.MoveStage;
import com.wonderingwizard.kafka.messages.EquipmentInstructionKafkaMessage;
import com.wonderingwizard.kafka.messages.EquipmentInstructionKafkaMessage.Container;
import com.wonderingwizard.events.WorkInstructionEvent;
import com.wonderingwizard.sideeffects.ActionActivated;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ActionActivatedToEquipmentInstructionMapper Tests")
class ActionActivatedToEquipmentInstructionMapperTest {

    private static final String TERMINAL_CODE = "ECTDELTA";
    private static final Instant ACTIVATED_AT = Instant.parse("2024-01-01T10:00:00Z");

    private ActionActivatedToEquipmentInstructionMapper rtgMapper;
    private ActionActivatedToEquipmentInstructionMapper ttMapper;
    private ActionActivatedToEquipmentInstructionMapper qcMapper;

    @BeforeEach
    void setUp() {
        rtgMapper = new ActionActivatedToEquipmentInstructionMapper(TERMINAL_CODE, "FESv4.0.18",
                Set.of(ActionType.RTG_DRIVE, ActionType.RTG_FETCH,
                        ActionType.RTG_HANDOVER_TO_TT, ActionType.RTG_LIFT_FROM_TT,
                        ActionType.RTG_PLACE_ON_YARD));
        ttMapper = new ActionActivatedToEquipmentInstructionMapper(TERMINAL_CODE, "FESv4.0.18",
                Set.of(ActionType.TT_DRIVE_TO_RTG_PULL, ActionType.TT_DRIVE_TO_RTG_STANDBY,
                        ActionType.TT_DRIVE_TO_RTG_UNDER, ActionType.TT_HANDOVER_FROM_RTG,
                        ActionType.TT_DRIVE_TO_QC_PULL, ActionType.TT_DRIVE_TO_QC_STANDBY,
                        ActionType.TT_DRIVE_UNDER_QC, ActionType.TT_HANDOVER_TO_QC,
                        ActionType.TT_HANDOVER_FROM_QC, ActionType.TT_HANDOVER_TO_RTG,
                        ActionType.TT_DRIVE_TO_BUFFER));
        qcMapper = new ActionActivatedToEquipmentInstructionMapper(TERMINAL_CODE, "FESv4.0.18",
                Set.of(ActionType.QC_LIFT, ActionType.QC_PLACE));
    }

    @Test
    @DisplayName("RTG mapper should return null for TT action types")
    void rtgMapperReturnsNullForTtActions() {
        ActionActivated activated = new ActionActivated(
                UUID.randomUUID(), 1L, "TAKT100", ActionType.TT_DRIVE_TO_RTG_PULL, "drive to RTG pull",
                ACTIVATED_AT, DeviceType.TT, List.of()
        );

        assertNull(rtgMapper.mapToMessage(activated));
    }

    @Test
    @DisplayName("Should return null when ActionActivated uses backward-compatible constructor")
    void returnsNullForBackwardCompatibleConstructor() {
        ActionActivated activated = new ActionActivated(
                UUID.randomUUID(), 1L, "TAKT100", "drive to RTG pull", ACTIVATED_AT
        );

        assertNull(rtgMapper.mapToMessage(activated));
    }

    @Test
    @DisplayName("Should return null when RTG_DRIVE has no work instructions")
    void returnsNullForEmptyWorkInstructions() {
        ActionActivated activated = new ActionActivated(
                UUID.randomUUID(), 1L, "TAKT100", ActionType.RTG_DRIVE, "drive",
                ACTIVATED_AT, DeviceType.RTG, List.of()
        );

        assertNull(rtgMapper.mapToMessage(activated));
    }

    @Test
    @DisplayName("Should map RTG_DRIVE with single work instruction to typed record")
    void mapsSingleWorkInstruction() {
        UUID actionId = UUID.randomUUID();
        WorkInstructionEvent wi = new WorkInstructionEvent(
                100L, 1L, "QC01", MoveStage.PLANNED,
                ACTIVATED_AT, 120, 60, "RTG05",
                false, false, false, 0, "Y01.01.01"
        );

        ActionActivated activated = new ActionActivated(
                actionId, 1L, "TAKT100", ActionType.RTG_DRIVE, "drive",
                ACTIVATED_AT, DeviceType.RTG, List.of(wi)
        );

        EquipmentInstructionKafkaMessage message = rtgMapper.mapToMessage(activated);

        assertNotNull(message);
        assertEquals("drive", message.equipmentInstructionType());
        assertEquals(actionId.toString(), message.equipmentInstructionId());
        assertEquals("Y01.01.01", message.destinationNodeId());
        assertEquals(ACTIVATED_AT.toEpochMilli(), message.targetTime());
        assertEquals("RTG05", message.recipientCHEShortName());
        assertEquals("RTG05", message.destinationCHEShortName());
        assertEquals("RTG", message.recipientCHEKind());
        assertEquals(TERMINAL_CODE, message.terminalCode());
        assertEquals("FESv4.0.18", message.eventSource());
    }

    @Test
    @DisplayName("Should populate container records from work instructions")
    void populatesContainerRecords() {
        WorkInstructionEvent wi1 = new WorkInstructionEvent(
                100L, 1L, "QC01", MoveStage.PLANNED,
                ACTIVATED_AT, 120, 60, "RTG05",
                false, false, false, 0, "Y01.01.01"
        );
        WorkInstructionEvent wi2 = new WorkInstructionEvent(
                101L, 1L, "QC01", MoveStage.PLANNED,
                ACTIVATED_AT, 120, 60, "RTG05",
                true, true, true, 100, "Y01.01.02"
        );

        ActionActivated activated = new ActionActivated(
                UUID.randomUUID(), 1L, "TAKT100", ActionType.RTG_DRIVE, "drive",
                ACTIVATED_AT, DeviceType.RTG, List.of(wi1, wi2)
        );

        EquipmentInstructionKafkaMessage message = rtgMapper.mapToMessage(activated);
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
        WorkInstructionEvent wi = new WorkInstructionEvent(
                100L, 1L, "QC01", MoveStage.PLANNED,
                ACTIVATED_AT, 120, 60, "RTG05",
                false, false, false, 0, "Y01.01.01"
        );

        ActionActivated activated = new ActionActivated(
                UUID.randomUUID(), 1L, "TAKT100", ActionType.RTG_DRIVE, "drive",
                ACTIVATED_AT, DeviceType.RTG, List.of(wi)
        );

        EquipmentInstructionKafkaMessage message = rtgMapper.mapToMessage(activated);

        assertEquals("RTG05", message.recipientCHEShortName());
    }

    @Test
    @DisplayName("RTG mapper should map RTG_FETCH action")
    void rtgMapperMapsRtgFetch() {
        UUID actionId = UUID.randomUUID();
        WorkInstructionEvent wi = new WorkInstructionEvent(
                100L, 1L, "QC01", MoveStage.PLANNED,
                ACTIVATED_AT, 120, 60, "RTG05",
                false, false, false, 0, "Y01.01.01"
        );

        ActionActivated activated = new ActionActivated(
                actionId, 1L, "TAKT100", ActionType.RTG_FETCH, "fetch",
                ACTIVATED_AT, DeviceType.RTG, List.of(wi)
        );

        EquipmentInstructionKafkaMessage message = rtgMapper.mapToMessage(activated);

        assertNotNull(message);
        assertEquals("fetch", message.equipmentInstructionType());
        assertEquals(actionId.toString(), message.equipmentInstructionId());
        assertEquals("RTG", message.recipientCHEKind());
    }

    @Test
    @DisplayName("RTG mapper should map RTG_PLACE_ON_YARD action")
    void rtgMapperMapsPlaceOnYard() {
        UUID actionId = UUID.randomUUID();
        WorkInstructionEvent wi = new WorkInstructionEvent(
                100L, 1L, "QC01", MoveStage.PLANNED,
                ACTIVATED_AT, 120, 60, "RTG05",
                false, false, false, 0, "Y01.01.01"
        );

        ActionActivated activated = new ActionActivated(
                actionId, 1L, "TAKT100", ActionType.RTG_PLACE_ON_YARD, "place on yard",
                ACTIVATED_AT, DeviceType.RTG, List.of(wi)
        );

        EquipmentInstructionKafkaMessage message = rtgMapper.mapToMessage(activated);

        assertNotNull(message);
        assertEquals("place on yard", message.equipmentInstructionType());
    }

    @Test
    @DisplayName("RTG mapper should return null for QC_LIFT action types")
    void returnsNullForNonRtgDriveActionType() {
        WorkInstructionEvent wi = new WorkInstructionEvent(
                100L, 1L, "QC01", MoveStage.PLANNED,
                ACTIVATED_AT, 120, 60, "RTG05",
                false, false, false, 0, "Y01.01.01"
        );

        ActionActivated activated = new ActionActivated(
                UUID.randomUUID(), 1L, "TAKT100", ActionType.QC_LIFT, "QC Lift",
                ACTIVATED_AT, DeviceType.QC, List.of(wi)
        );

        assertNull(rtgMapper.mapToMessage(activated));
    }

    @Test
    @DisplayName("Should produce valid Avro GenericRecord via toAvro()")
    void producesValidAvroRecord() {
        UUID actionId = UUID.randomUUID();
        WorkInstructionEvent wi = new WorkInstructionEvent(
                100L, 1L, "QC01", MoveStage.PLANNED,
                ACTIVATED_AT, 120, 60, "RTG05",
                false, false, false, 0, "Y01.01.01"
        );

        ActionActivated activated = new ActionActivated(
                actionId, 1L, "TAKT100", ActionType.RTG_DRIVE, "drive",
                ACTIVATED_AT, DeviceType.RTG, List.of(wi)
        );

        GenericRecord avro = rtgMapper.map(activated);

        assertNotNull(avro);
        assertEquals("drive", avro.get("equipmentInstructionType").toString());
        assertEquals(actionId.toString(), avro.get("equipmentInstructionId").toString());
        assertEquals(TERMINAL_CODE, avro.get("terminalCode").toString());
    }

    // ── TT mapper tests ─────────────────────────────────────────────────────

    @Test
    @DisplayName("TT mapper should map TT_DRIVE_TO_RTG_PULL action")
    void ttMapperMapsDriveToRtgPull() {
        UUID actionId = UUID.randomUUID();
        WorkInstructionEvent wi = new WorkInstructionEvent(
                100L, 1L, "QC01", MoveStage.PLANNED,
                ACTIVATED_AT, 120, 60, "RTG05",
                false, false, false, 0, "Y01.01.01"
        );

        ActionActivated activated = new ActionActivated(
                actionId, 1L, "TAKT100", ActionType.TT_DRIVE_TO_RTG_PULL, "drive to RTG pull",
                ACTIVATED_AT, DeviceType.TT, List.of(wi)
        );

        EquipmentInstructionKafkaMessage message = ttMapper.mapToMessage(activated);

        assertNotNull(message);
        assertEquals("drive to RTG pull", message.equipmentInstructionType());
        assertEquals(actionId.toString(), message.equipmentInstructionId());
        assertEquals("TT", message.recipientCHEKind());
        assertEquals("QC01", message.recipientCHEShortName());
        assertEquals(TERMINAL_CODE, message.terminalCode());
    }

    @Test
    @DisplayName("TT mapper should map TT_HANDOVER_TO_QC action")
    void ttMapperMapsHandoverToQc() {
        UUID actionId = UUID.randomUUID();
        WorkInstructionEvent wi = new WorkInstructionEvent(
                100L, 1L, "QC01", MoveStage.PLANNED,
                ACTIVATED_AT, 120, 60, "RTG05",
                false, false, false, 0, "Y01.01.01"
        );

        ActionActivated activated = new ActionActivated(
                actionId, 1L, "TAKT100", ActionType.TT_HANDOVER_TO_QC, "handover to QC",
                ACTIVATED_AT, DeviceType.TT, List.of(wi)
        );

        EquipmentInstructionKafkaMessage message = ttMapper.mapToMessage(activated);

        assertNotNull(message);
        assertEquals("handover to QC", message.equipmentInstructionType());
    }

    @Test
    @DisplayName("TT mapper should return null for RTG_DRIVE action types")
    void ttMapperReturnsNullForRtgDrive() {
        WorkInstructionEvent wi = new WorkInstructionEvent(
                100L, 1L, "QC01", MoveStage.PLANNED,
                ACTIVATED_AT, 120, 60, "RTG05",
                false, false, false, 0, "Y01.01.01"
        );

        ActionActivated activated = new ActionActivated(
                UUID.randomUUID(), 1L, "TAKT100", ActionType.RTG_DRIVE, "drive",
                ACTIVATED_AT, DeviceType.RTG, List.of(wi)
        );

        assertNull(ttMapper.mapToMessage(activated));
    }

    @Test
    @DisplayName("TT mapper should return null when TT action has no work instructions")
    void ttMapperReturnsNullForEmptyWorkInstructions() {
        ActionActivated activated = new ActionActivated(
                UUID.randomUUID(), 1L, "TAKT100", ActionType.TT_DRIVE_TO_BUFFER, "drive to buffer",
                ACTIVATED_AT, DeviceType.TT, List.of()
        );

        assertNull(ttMapper.mapToMessage(activated));
    }

    @Test
    @DisplayName("TT mapper should use fetchChe as recipientCHE for TT device type")
    void ttMapperUsesFetchCheAsRecipient() {
        WorkInstructionEvent wi = new WorkInstructionEvent(
                100L, 1L, "QC01", MoveStage.PLANNED,
                ACTIVATED_AT, 120, 60, "RTG05",
                false, false, false, 0, "Y01.01.01"
        );

        ActionActivated activated = new ActionActivated(
                UUID.randomUUID(), 1L, "TAKT100", ActionType.TT_DRIVE_TO_QC_PULL, "drive to QC pull",
                ACTIVATED_AT, DeviceType.TT, List.of(wi)
        );

        EquipmentInstructionKafkaMessage message = ttMapper.mapToMessage(activated);

        assertEquals("QC01", message.recipientCHEShortName());
    }

    @Test
    @DisplayName("TT mapper should produce valid Avro GenericRecord")
    void ttMapperProducesValidAvroRecord() {
        UUID actionId = UUID.randomUUID();
        WorkInstructionEvent wi = new WorkInstructionEvent(
                100L, 1L, "QC01", MoveStage.PLANNED,
                ACTIVATED_AT, 120, 60, "RTG05",
                false, false, false, 0, "Y01.01.01"
        );

        ActionActivated activated = new ActionActivated(
                actionId, 1L, "TAKT100", ActionType.TT_DRIVE_TO_RTG_UNDER, "drive to RTG under",
                ACTIVATED_AT, DeviceType.TT, List.of(wi)
        );

        GenericRecord avro = ttMapper.map(activated);

        assertNotNull(avro);
        assertEquals("drive to RTG under", avro.get("equipmentInstructionType").toString());
        assertEquals(actionId.toString(), avro.get("equipmentInstructionId").toString());
        assertEquals(TERMINAL_CODE, avro.get("terminalCode").toString());
    }

    // ── QC mapper tests ─────────────────────────────────────────────────────

    @Test
    @DisplayName("QC mapper should map QC_LIFT action")
    void qcMapperMapsQcLift() {
        UUID actionId = UUID.randomUUID();
        WorkInstructionEvent wi = new WorkInstructionEvent(
                100L, 1L, "QC01", MoveStage.PLANNED,
                ACTIVATED_AT, 120, 60, "RTG05",
                false, false, false, 0, "Y01.01.01"
        );

        ActionActivated activated = new ActionActivated(
                actionId, 1L, "TAKT100", ActionType.QC_LIFT, "QC Lift",
                ACTIVATED_AT, DeviceType.QC, List.of(wi)
        );

        EquipmentInstructionKafkaMessage message = qcMapper.mapToMessage(activated);

        assertNotNull(message);
        assertEquals("LIFT", message.equipmentInstructionType());
        assertEquals("Lift", message.equipmentInstructionText());
        assertEquals(actionId.toString(), message.equipmentInstructionId());
        assertEquals("QC", message.recipientCHEKind());
        assertEquals("QC01", message.recipientCHEShortName());
        assertEquals("", message.destinationNodeId());
        assertEquals("", message.destinationNodeName());
        assertEquals(TERMINAL_CODE, message.terminalCode());
        // Default pinning is empty → SKIP_PINNING
        assertEquals(List.of("SKIP_PINNING"), message.containers().getFirst().instructionDetails());
    }

    @Test
    @DisplayName("QC mapper should map QC_PLACE action")
    void qcMapperMapsQcPlace() {
        UUID actionId = UUID.randomUUID();
        WorkInstructionEvent wi = new WorkInstructionEvent(
                100L, 1L, "QC01", MoveStage.PLANNED,
                ACTIVATED_AT, 120, 60, "RTG05",
                false, false, false, 0, "Y01.01.01"
        );

        ActionActivated activated = new ActionActivated(
                actionId, 1L, "TAKT100", ActionType.QC_PLACE, "QC Place",
                ACTIVATED_AT, DeviceType.QC, List.of(wi)
        );

        EquipmentInstructionKafkaMessage message = qcMapper.mapToMessage(activated);

        assertNotNull(message);
        assertEquals("PLACE", message.equipmentInstructionType());
        assertEquals("Place container", message.equipmentInstructionText());
        assertEquals("", message.destinationNodeId());
    }

    @Test
    @DisplayName("QC mapper should return null for RTG action types")
    void qcMapperReturnsNullForRtgActions() {
        WorkInstructionEvent wi = new WorkInstructionEvent(
                100L, 1L, "QC01", MoveStage.PLANNED,
                ACTIVATED_AT, 120, 60, "RTG05",
                false, false, false, 0, "Y01.01.01"
        );

        ActionActivated activated = new ActionActivated(
                UUID.randomUUID(), 1L, "TAKT100", ActionType.RTG_DRIVE, "drive",
                ACTIVATED_AT, DeviceType.RTG, List.of(wi)
        );

        assertNull(qcMapper.mapToMessage(activated));
    }

    @Test
    @DisplayName("QC mapper should produce valid Avro GenericRecord")
    void qcMapperProducesValidAvroRecord() {
        UUID actionId = UUID.randomUUID();
        WorkInstructionEvent wi = new WorkInstructionEvent(
                100L, 1L, "QC01", MoveStage.PLANNED,
                ACTIVATED_AT, 120, 60, "RTG05",
                false, false, false, 0, "Y01.01.01"
        );

        ActionActivated activated = new ActionActivated(
                actionId, 1L, "TAKT100", ActionType.QC_LIFT, "QC Lift",
                ACTIVATED_AT, DeviceType.QC, List.of(wi)
        );

        GenericRecord avro = qcMapper.map(activated);

        assertNotNull(avro);
        assertEquals("LIFT", avro.get("equipmentInstructionType").toString());
        assertEquals(actionId.toString(), avro.get("equipmentInstructionId").toString());
        assertEquals(TERMINAL_CODE, avro.get("terminalCode").toString());
    }
}
