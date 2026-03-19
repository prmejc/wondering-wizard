package com.wonderingwizard.kafka;

import com.wonderingwizard.domain.takt.ActionType;
import com.wonderingwizard.kafka.messages.EquipmentInstructionKafkaMessage;
import com.wonderingwizard.kafka.messages.EquipmentInstructionKafkaMessage.Container;
import com.wonderingwizard.events.WorkInstructionEvent;
import com.wonderingwizard.sideeffects.ActionActivated;
import org.apache.avro.generic.GenericRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Maps {@link ActionActivated} side effects to {@link EquipmentInstructionKafkaMessage} records,
 * then converts to Avro {@link GenericRecord} for Kafka publishing.
 * <p>
 * Only maps actions whose {@link ActionType} is in the configured set of supported action types.
 * All other action types return {@code null} (skipped).
 */
public class ActionActivatedToEquipmentInstructionMapper implements SideEffectMapper<ActionActivated> {

    private static final Logger logger = Logger.getLogger(ActionActivatedToEquipmentInstructionMapper.class.getName());

    private final String terminalCode;
    private final String eventSource;
    private final Set<ActionType> supportedActionTypes;

    public ActionActivatedToEquipmentInstructionMapper(String terminalCode, String eventSource,
                                                       Set<ActionType> supportedActionTypes) {
        this.terminalCode = terminalCode;
        this.eventSource = eventSource;
        this.supportedActionTypes = supportedActionTypes;
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
        if (activated.actionType() == null || !supportedActionTypes.contains(activated.actionType())) {
            return null;
        }

        if (activated.workInstructions() == null || activated.workInstructions().isEmpty()) {
            logger.fine("Skipping ActionActivated without work instructions: " + activated.actionId());
            return null;
        }

        WorkInstructionEvent firstWi = activated.workInstructions().getFirst();
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
                eventSource,
                activated.activatedAt().toEpochMilli(),
                terminalCode
        );
    }

    private String resolveRecipientChe(ActionActivated activated) {
        if (activated.workInstructions().isEmpty()) {
            return "";
        }
        WorkInstructionEvent wi = activated.workInstructions().getFirst();
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

        for (WorkInstructionEvent wi : activated.workInstructions()) {
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
