package com.wonderingwizard.processors;

import com.wonderingwizard.events.WorkInstructionEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static com.wonderingwizard.domain.takt.ActionType.*;
import static com.wonderingwizard.domain.takt.DeviceType.RTG;
import static com.wonderingwizard.domain.takt.DeviceType.TT;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RtgWaitDurationStepTest {

    private RtgWaitDurationStep step;
    private WorkInstructionEvent workInstruction;

    @BeforeEach
    void setUp() {
        step = new RtgWaitDurationStep();
        workInstruction = new WorkInstructionEvent(1L, 1L, "RTG01", "PLANNED", Instant.EPOCH, 120);
    }

    @Nested
    @DisplayName("Fetch time subtraction")
    class FetchTimeSubtraction {

        @Test
        @DisplayName("Should subtract RTG_DRIVE duration from RTG_WAIT_FOR_TRUCK")
        void subtractsFetchDurationFromWait() {
            // TT actions: HANDOVER_FROM_QC(0s) -> DRIVE(30s) -> HANDOVER_TO_RTG(5s)
            // RTG_DRIVE = 10s
            // Wait = 30 (TT travel) - 10 (fetch) = 20
            List<GraphScheduleBuilder.ActionTemplate> templates = List.of(
                    GraphScheduleBuilder.ActionTemplate.of(RTG_DRIVE, RTG, 10),
                    GraphScheduleBuilder.ActionTemplate.of(RTG_WAIT_FOR_TRUCK, RTG, 0),
                    GraphScheduleBuilder.ActionTemplate.of(TT_HANDOVER_FROM_QC, TT, 0),
                    GraphScheduleBuilder.ActionTemplate.of(TT_DRIVE_TO_RTG_PULL, TT, 30),
                    GraphScheduleBuilder.ActionTemplate.of(TT_HANDOVER_TO_RTG, TT, 5)
            );

            List<GraphScheduleBuilder.ActionTemplate> result = step.enrichTemplates(1L, templates, workInstruction);

            int waitDuration = result.stream()
                    .filter(t -> t.actionType() == RTG_WAIT_FOR_TRUCK)
                    .findFirst()
                    .orElseThrow()
                    .durationSeconds();
            assertEquals(20, waitDuration);
        }

        @Test
        @DisplayName("Should clamp wait to zero when fetch exceeds TT travel time")
        void clampsToZeroWhenFetchExceedsTravelTime() {
            // TT travel = 10s, RTG_DRIVE = 50s -> wait should be 0, not negative
            List<GraphScheduleBuilder.ActionTemplate> templates = List.of(
                    GraphScheduleBuilder.ActionTemplate.of(RTG_DRIVE, RTG, 50),
                    GraphScheduleBuilder.ActionTemplate.of(RTG_WAIT_FOR_TRUCK, RTG, 0),
                    GraphScheduleBuilder.ActionTemplate.of(TT_HANDOVER_FROM_QC, TT, 0),
                    GraphScheduleBuilder.ActionTemplate.of(TT_DRIVE_TO_RTG_PULL, TT, 10),
                    GraphScheduleBuilder.ActionTemplate.of(TT_HANDOVER_TO_RTG, TT, 5)
            );

            List<GraphScheduleBuilder.ActionTemplate> result = step.enrichTemplates(1L, templates, workInstruction);

            int waitDuration = result.stream()
                    .filter(t -> t.actionType() == RTG_WAIT_FOR_TRUCK)
                    .findFirst()
                    .orElseThrow()
                    .durationSeconds();
            assertEquals(0, waitDuration);
        }

        @Test
        @DisplayName("Should handle no RTG_DRIVE in templates gracefully")
        void noFetchActionKeepsOriginalWait() {
            // No RTG_DRIVE -> wait durations are not reduced
            List<GraphScheduleBuilder.ActionTemplate> templates = List.of(
                    GraphScheduleBuilder.ActionTemplate.of(RTG_WAIT_FOR_TRUCK, RTG, 0),
                    GraphScheduleBuilder.ActionTemplate.of(TT_HANDOVER_FROM_QC, TT, 0),
                    GraphScheduleBuilder.ActionTemplate.of(TT_DRIVE_TO_RTG_PULL, TT, 30),
                    GraphScheduleBuilder.ActionTemplate.of(TT_HANDOVER_TO_RTG, TT, 5)
            );

            List<GraphScheduleBuilder.ActionTemplate> result = step.enrichTemplates(1L, templates, workInstruction);

            int waitDuration = result.stream()
                    .filter(t -> t.actionType() == RTG_WAIT_FOR_TRUCK)
                    .findFirst()
                    .orElseThrow()
                    .durationSeconds();
            assertEquals(30, waitDuration);
        }
    }

    @Nested
    @DisplayName("No matching actions")
    class NoMatchingActions {

        @Test
        @DisplayName("Should return templates unchanged when no HANDOVER_FROM_QC")
        void noHandoverFromQc_returnsUnchanged() {
            List<GraphScheduleBuilder.ActionTemplate> templates = List.of(
                    GraphScheduleBuilder.ActionTemplate.of(TT_DRIVE_TO_RTG_PULL, TT, 30),
                    GraphScheduleBuilder.ActionTemplate.of(TT_HANDOVER_TO_RTG, TT, 5)
            );

            List<GraphScheduleBuilder.ActionTemplate> result = step.enrichTemplates(1L, templates, workInstruction);

            assertEquals(templates, result);
        }

        @Test
        @DisplayName("Should return templates unchanged when no HANDOVER_TO_RTG after FROM_QC")
        void noHandoverToRtg_returnsUnchanged() {
            List<GraphScheduleBuilder.ActionTemplate> templates = List.of(
                    GraphScheduleBuilder.ActionTemplate.of(TT_HANDOVER_FROM_QC, TT, 0),
                    GraphScheduleBuilder.ActionTemplate.of(TT_DRIVE_TO_RTG_PULL, TT, 30)
            );

            List<GraphScheduleBuilder.ActionTemplate> result = step.enrichTemplates(1L, templates, workInstruction);

            assertEquals(templates, result);
        }
    }
}
