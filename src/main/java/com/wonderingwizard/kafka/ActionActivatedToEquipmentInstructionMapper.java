package com.wonderingwizard.kafka;

import com.wonderingwizard.domain.takt.ActionType;
import com.wonderingwizard.kafka.messages.EquipmentInstructionKafkaMessage;
import com.wonderingwizard.kafka.messages.EquipmentInstructionKafkaMessage.Container;
import com.wonderingwizard.sideeffects.ActionActivated;
import com.wonderingwizard.sideeffects.WorkInstruction;
import org.apache.avro.generic.GenericRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Maps {@link ActionActivated} side effects to {@link EquipmentInstructionKafkaMessage} records,
 * then converts to Avro {@link GenericRecord} for Kafka publishing.
 * <p>
 * Only maps actions whose {@link ActionType} is {@link ActionType#RTG_DRIVE}.
 * All other action types return {@code null} (skipped).
 */
public class ActionActivatedToEquipmentInstructionMapper implements SideEffectMapper<ActionActivated> {

    private static final Logger logger = Logger.getLogger(ActionActivatedToEquipmentInstructionMapper.class.getName());
    private static final String EVENT_SOURCE = "wondering-wizard";

    private final String terminalCode;

    public ActionActivatedToEquipmentInstructionMapper(String terminalCode) {
        this.terminalCode = terminalCode;
    }

    @Override
    public GenericRecord map(ActionActivated activated) {
        EquipmentInstructionKafkaMessage message = mapToMessage(activated);
        return message != null ? message.toAvro() : null;
    }

    /**
     * Maps an {@link ActionActivated} to a typed {@link EquipmentInstructionKafkaMessage},
     * or returns {@code null} if this side effect should be skipped.
     */
    EquipmentInstructionKafkaMessage mapToMessage(ActionActivated activated) {
        if (activated.actionType() != ActionType.RTG_DRIVE) {
            return null;
        }

        if (activated.workInstructions() == null || activated.workInstructions().isEmpty()) {
            logger.fine("Skipping ActionActivated without work instructions: " + activated.actionId());
            return null;
        }

        WorkInstruction firstWi = activated.workInstructions().getFirst();
        String recipientChe = resolveRecipientChe(activated);

        return new EquipmentInstructionKafkaMessage(
                activated.actionDescription(),
                activated.actionId().toString(),
                activated.actionDescription(),
                firstWi.toPosition() != null ? firstWi.toPosition() : "",
                firstWi.toPosition() != null ? firstWi.toPosition() : "",
                activated.activatedAt().toEpochMilli(),
                recipientChe,
                firstWi.putChe() != null ? firstWi.putChe() : "",
                activated.deviceType() != null ? activated.deviceType().name() : "",
                false,
                "",
                buildContainers(activated),
                firstWi.fetchChe(),
                null,
                firstWi.putChe(),
                EVENT_SOURCE,
                activated.activatedAt().toEpochMilli(),
                terminalCode
        );
    }

    private String resolveRecipientChe(ActionActivated activated) {
        if (activated.workInstructions().isEmpty()) {
            return "";
        }
        WorkInstruction wi = activated.workInstructions().getFirst();
        return switch (activated.deviceType()) {
            case QC -> wi.fetchChe() != null ? wi.fetchChe() : "";
            case TT -> wi.fetchChe() != null ? wi.fetchChe() : "";
            case RTG -> wi.putChe() != null ? wi.putChe() : "";
            case null -> "";
        };
    }

    private List<Container> buildContainers(ActionActivated activated) {
        List<Container> containers = new ArrayList<>();
        long sequence = 1;

        for (WorkInstruction wi : activated.workInstructions()) {
            containers.add(new Container(
                    sequence++,
                    "",
                    List.of(activated.actionDescription()),
                    String.valueOf(wi.workQueueId()),
                    wi.workInstructionId(),
                    "",
                    "",
                    "",
                    "",
                    wi.toPosition() != null ? wi.toPosition() : ""
            ));
        }

        return containers;
    }
}
