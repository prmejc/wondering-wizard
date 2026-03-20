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

    EquipmentInstructionKafkaMessage mapToMessage(ActionActivated activated) {
        if (activated.actionType() == null || !supportedActionTypes.contains(activated.actionType())) {
            return null;
        }

        if (activated.workInstructions() == null || activated.workInstructions().isEmpty()) {
            logger.fine("Skipping ActionActivated without work instructions: " + activated.actionId());
            return null;
        }

        WorkInstructionEvent firstWi = activated.workInstructions().getFirst();

        return new EquipmentInstructionKafkaMessage(
                resolveEquipmentInstructionType(activated),
                activated.actionId().toString(),
                resolveEquipmentInstructionText(activated),
                resolveDestinationNodeId(activated, firstWi),
                resolveDestinationNodeName(activated, firstWi),
                activated.activatedAt().toEpochMilli(),
                resolveRecipientChe(activated, firstWi),
                resolveDestinationChe(activated, firstWi),
                activated.deviceType() != null ? activated.deviceType().name() : "",
                false,
                firstWi.moveKind() != null ? firstWi.moveKind() : "",
                buildContainers(activated),
                resolveFetchChe(activated, firstWi),
                activated.cheShortName(),
                resolvePutChe(activated, firstWi),
                eventSource,
                activated.activatedAt().toEpochMilli(),
                terminalCode
        );
    }

    // ── equipmentInstructionType ──

    private String resolveEquipmentInstructionType(ActionActivated activated) {
        return switch (activated.actionType()) {
            case QC_LIFT -> "LIFT";
            case QC_PLACE -> "PLACE";
            case RTG_DRIVE -> "SELECT_JOB";
            case RTG_WAIT_FOR_TRUCK -> "WAIT_FOR_TT";
            case RTG_FETCH, RTG_LIFT_FROM_TT -> "LIFT";
            case RTG_HANDOVER_TO_TT, RTG_PLACE_ON_YARD -> "PLACE";
            default -> activated.actionDescription();
        };
    }

    // ── equipmentInstructionText ──

    private String resolveEquipmentInstructionText(ActionActivated activated) {
        return switch (activated.actionType()) {
            case QC_LIFT -> "Lift";
            case QC_PLACE -> "Place container";
            case RTG_DRIVE -> "Select job";
            case RTG_WAIT_FOR_TRUCK -> "Wait for TT";
            case RTG_FETCH, RTG_LIFT_FROM_TT -> "Lift container";
            case RTG_HANDOVER_TO_TT -> "Place on TT";
            case RTG_PLACE_ON_YARD -> "Place on yard";
            default -> activated.actionDescription();
        };
    }

    // ── destinationNodeId / destinationNodeName ──

    private String resolveDestinationNodeId(ActionActivated activated, WorkInstructionEvent wi) {
        return switch (activated.actionType()) {
            case QC_LIFT, QC_PLACE, RTG_WAIT_FOR_TRUCK -> "";
            default -> wi.toPosition() != null ? wi.toPosition() : "";
        };
    }

    private String resolveDestinationNodeName(ActionActivated activated, WorkInstructionEvent wi) {
        return switch (activated.actionType()) {
            case QC_LIFT -> "";
            case QC_PLACE -> "";
            case RTG_WAIT_FOR_TRUCK -> "";
            default -> wi.toPosition() != null ? wi.toPosition() : "";
        };
    }

    // ── destinationCHEShortName ──

    private String resolveDestinationChe(ActionActivated activated, WorkInstructionEvent wi) {
        return switch (activated.actionType()) {
            case QC_PLACE -> activated.cheShortName() != null ? activated.cheShortName() : "";
            case QC_LIFT -> "";
            case TT_DRIVE_TO_QC_PULL, TT_DRIVE_TO_QC_STANDBY, TT_DRIVE_UNDER_QC,
                 TT_HANDOVER_TO_QC, TT_HANDOVER_FROM_QC ->
                    wi.fetchChe() != null ? wi.fetchChe() : "";
            case TT_DRIVE_TO_RTG_PULL, TT_DRIVE_TO_RTG_STANDBY, TT_DRIVE_TO_RTG_UNDER,
                 TT_HANDOVER_FROM_RTG, TT_HANDOVER_TO_RTG ->
                    wi.putChe() != null ? wi.putChe() : "";
            // RTG actions — destination CHE is always empty per samples
            case RTG_DRIVE, RTG_FETCH, RTG_WAIT_FOR_TRUCK, RTG_HANDOVER_TO_TT,
                 RTG_LIFT_FROM_TT, RTG_PLACE_ON_YARD -> "";
            default -> "";
        };
    }

    // ── recipientCHEShortName ──

    private String resolveRecipientChe(ActionActivated activated, WorkInstructionEvent wi) {
        return switch (activated.deviceType()) {
            case QC -> wi.fetchChe() != null ? wi.fetchChe() : "";
            case TT -> activated.cheShortName() != null ? activated.cheShortName() : "";
            case RTG -> "LOAD".equalsIgnoreCase(wi.moveKind())
                    ? (wi.fetchChe() != null ? wi.fetchChe() : "")
                    : (wi.putChe() != null ? wi.putChe() : "");
            case null -> "";
        };
    }

    // ── fetchCHEShortName / putCHEShortName ──
    // For RTG instructions: fetchCHE = the RTG itself, putCHE = null
    // For QC/TT: fetchCHE = wi.fetchChe (QC), putCHE = wi.putChe (RTG)

    private String resolveFetchChe(ActionActivated activated, WorkInstructionEvent wi) {
        return switch (activated.deviceType()) {
            case RTG -> "LOAD".equalsIgnoreCase(wi.moveKind())
                    ? (wi.fetchChe() != null ? wi.fetchChe() : "")
                    : (wi.putChe() != null ? wi.putChe() : "");
            default -> wi.fetchChe();
        };
    }

    private String resolvePutChe(ActionActivated activated, WorkInstructionEvent wi) {
        return switch (activated.deviceType()) {
            case RTG -> null;
            default -> wi.putChe();
        };
    }

    // ── containers ──

    private List<Container> buildContainers(ActionActivated activated) {
        List<Container> containers = new ArrayList<>();
        long sequence = 1;

        for (WorkInstructionEvent wi : activated.workInstructions()) {
            containers.add(new Container(
                    sequence++,
                    wi.containerId() != null ? wi.containerId() : "",
                    resolveInstructionDetails(activated, wi),
                    String.valueOf(wi.workQueueId()),
                    wi.workInstructionId(),
                    wi.isoType() != null ? wi.isoType() : "",
                    wi.jobPosition() != null ? wi.jobPosition() : "",
                    wi.freightKind() != null ? wi.freightKind() : "",
                    wi.fromPosition() != null ? wi.fromPosition() : "",
                    wi.toPosition() != null ? wi.toPosition() : ""
            ));
        }

        return containers;
    }

    private List<String> resolveInstructionDetails(ActionActivated activated, WorkInstructionEvent wi) {
        return switch (activated.actionType()) {
            case QC_LIFT, QC_PLACE, RTG_DRIVE, RTG_FETCH, RTG_WAIT_FOR_TRUCK,
                 RTG_HANDOVER_TO_TT, RTG_LIFT_FROM_TT, RTG_PLACE_ON_YARD -> {
                String pinning = wi.pinning();
                yield (pinning != null && !pinning.isEmpty()) ? List.of("GO_PINNING") : List.of("SKIP_PINNING");
            }
            default -> List.of(activated.actionDescription());
        };
    }
}
