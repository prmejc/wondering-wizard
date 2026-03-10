package com.wonderingwizard.processors;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.Takt;
import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.TimeEvent;
import com.wonderingwizard.events.WorkQueueMessage;
import com.wonderingwizard.sideeffects.DelayUpdated;
import com.wonderingwizard.sideeffects.ScheduleCreated;
import com.wonderingwizard.sideeffects.TaktActivated;
import com.wonderingwizard.sideeffects.TaktCompleted;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static com.wonderingwizard.events.WorkQueueStatus.INACTIVE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DelayProcessor.
 * <p>
 * Tests the delay calculation logic including:
 * - No delay when takts complete on time
 * - Delay detection when active takt overruns its planned duration
 * - Delay decrease when takts complete faster than planned
 * - State capture and restore
 * - Cleanup on schedule deactivation
 */
@DisplayName("DelayProcessor Tests")
class DelayProcessorTest {

    private static final Instant BASE_TIME = Instant.parse("2024-01-01T10:00:00Z");
    private static final Instant EMT = BASE_TIME;

    private DelayProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new DelayProcessor();
    }

    private ScheduleCreated createSchedule(String workQueueId, int taktCount, int durationSeconds) {
        List<Takt> takts = new java.util.ArrayList<>();
        Instant startTime = BASE_TIME;
        for (int i = 0; i < taktCount; i++) {
            Action action = Action.create("action " + i);
            Takt takt = new Takt(i, List.of(action.withDependencies(Set.of())), startTime, startTime, durationSeconds);
            takts.add(takt);
            startTime = startTime.plusSeconds(durationSeconds);
        }
        return new ScheduleCreated(workQueueId, takts, EMT);
    }

    @Nested
    @DisplayName("No Delay Scenarios")
    class NoDelayTests {

        @Test
        @DisplayName("Should emit no delay when no schedules exist")
        void noDelayWithoutSchedules() {
            List<SideEffect> effects = processor.process(new TimeEvent(BASE_TIME.plusSeconds(60)));
            assertTrue(effects.isEmpty());
        }

        @Test
        @DisplayName("Should emit no delay when no takt has been activated")
        void noDelayBeforeActivation() {
            processor.process(createSchedule("queue-1", 2, 120));

            List<SideEffect> effects = processor.process(new TimeEvent(BASE_TIME.plusSeconds(60)));
            assertTrue(effects.isEmpty());
        }

        @Test
        @DisplayName("Should emit zero delay when takt is active but within its planned duration")
        void noDelayWhenTaktOnTime() {
            processor.process(createSchedule("queue-1", 2, 120));
            processor.process(new TaktActivated("queue-1", "TAKT100", BASE_TIME));

            // 60 seconds into a 120-second takt — no delay
            List<SideEffect> effects = processor.process(new TimeEvent(BASE_TIME.plusSeconds(60)));
            assertTrue(effects.isEmpty());
        }

        @Test
        @DisplayName("Should emit zero delay right at the planned end time")
        void noDelayExactlyAtPlannedEnd() {
            processor.process(createSchedule("queue-1", 2, 120));
            processor.process(new TaktActivated("queue-1", "TAKT100", BASE_TIME));

            // Exactly at planned end — delay is 0, but lastEmittedDelay was -1 so it emits
            List<SideEffect> effects = processor.process(new TimeEvent(BASE_TIME.plusSeconds(120)));
            assertEquals(1, effects.size());
            DelayUpdated updated = (DelayUpdated) effects.get(0);
            assertEquals(0, updated.totalDelaySeconds());
        }
    }

    @Nested
    @DisplayName("Delay Detection")
    class DelayDetectionTests {

        @Test
        @DisplayName("Should detect delay when active takt overruns its planned duration")
        void detectsDelayOnOverrun() {
            processor.process(createSchedule("queue-1", 2, 120));
            processor.process(new TaktActivated("queue-1", "TAKT100", BASE_TIME));

            // 150 seconds into a 120-second takt — 30 seconds delayed
            List<SideEffect> effects = processor.process(new TimeEvent(BASE_TIME.plusSeconds(150)));
            assertEquals(1, effects.size());

            DelayUpdated updated = (DelayUpdated) effects.get(0);
            assertEquals("queue-1", updated.workQueueId());
            assertEquals(30, updated.totalDelaySeconds());
        }

        @Test
        @DisplayName("Should increase delay as time progresses past planned end")
        void delayIncreasesOverTime() {
            processor.process(createSchedule("queue-1", 2, 120));
            processor.process(new TaktActivated("queue-1", "TAKT100", BASE_TIME));

            // 150s → 30s delay
            List<SideEffect> effects1 = processor.process(new TimeEvent(BASE_TIME.plusSeconds(150)));
            assertEquals(1, effects1.size());
            assertEquals(30, ((DelayUpdated) effects1.get(0)).totalDelaySeconds());

            // 180s → 60s delay
            List<SideEffect> effects2 = processor.process(new TimeEvent(BASE_TIME.plusSeconds(180)));
            assertEquals(1, effects2.size());
            assertEquals(60, ((DelayUpdated) effects2.get(0)).totalDelaySeconds());
        }

        @Test
        @DisplayName("Should not emit duplicate delay when value hasn't changed")
        void noDuplicateEmissions() {
            processor.process(createSchedule("queue-1", 2, 120));
            processor.process(new TaktActivated("queue-1", "TAKT100", BASE_TIME));

            // 150s → 30s delay
            processor.process(new TimeEvent(BASE_TIME.plusSeconds(150)));

            // Same second — same 30s delay, should not emit
            List<SideEffect> effects = processor.process(new TimeEvent(BASE_TIME.plusSeconds(150)));
            assertTrue(effects.isEmpty());
        }
    }

    @Nested
    @DisplayName("Delay from Completed Takts")
    class CompletedTaktDelayTests {

        @Test
        @DisplayName("Should calculate delay from last completed takt when no takt is active")
        void delayFromCompletedTakt() {
            processor.process(createSchedule("queue-1", 2, 120));
            processor.process(new TaktActivated("queue-1", "TAKT100", BASE_TIME));

            // Complete TAKT100 at 150s (30s overrun on a 120s takt)
            processor.process(new TaktCompleted("queue-1", "TAKT100", BASE_TIME.plusSeconds(150)));

            // No active takt now — delay should be based on completed takt
            List<SideEffect> effects = processor.process(new TimeEvent(BASE_TIME.plusSeconds(160)));
            assertEquals(1, effects.size());
            assertEquals(30, ((DelayUpdated) effects.get(0)).totalDelaySeconds());
        }

        @Test
        @DisplayName("Should show zero delay when completed takt finished on time")
        void noDelayFromOnTimeCompletion() {
            processor.process(createSchedule("queue-1", 2, 120));
            processor.process(new TaktActivated("queue-1", "TAKT100", BASE_TIME));

            // Complete TAKT100 at exactly 120s (on time)
            processor.process(new TaktCompleted("queue-1", "TAKT100", BASE_TIME.plusSeconds(120)));

            List<SideEffect> effects = processor.process(new TimeEvent(BASE_TIME.plusSeconds(130)));
            assertEquals(1, effects.size());
            assertEquals(0, ((DelayUpdated) effects.get(0)).totalDelaySeconds());
        }

        @Test
        @DisplayName("Should show zero delay when completed takt finished early")
        void noDelayFromEarlyCompletion() {
            processor.process(createSchedule("queue-1", 2, 120));
            processor.process(new TaktActivated("queue-1", "TAKT100", BASE_TIME));

            // Complete TAKT100 at 100s (20s early)
            processor.process(new TaktCompleted("queue-1", "TAKT100", BASE_TIME.plusSeconds(100)));

            List<SideEffect> effects = processor.process(new TimeEvent(BASE_TIME.plusSeconds(110)));
            assertEquals(1, effects.size());
            assertEquals(0, ((DelayUpdated) effects.get(0)).totalDelaySeconds());
        }
    }

    @Nested
    @DisplayName("Delay Decrease")
    class DelayDecreaseTests {

        @Test
        @DisplayName("Should decrease delay when second takt uses active takt's planned window")
        void delayDecreasesWithFasterTakt() {
            processor.process(createSchedule("queue-1", 3, 120));

            // TAKT100 starts on time, runs 150s (30s delay)
            processor.process(new TaktActivated("queue-1", "TAKT100", BASE_TIME));
            processor.process(new TaktCompleted("queue-1", "TAKT100", BASE_TIME.plusSeconds(150)));

            // TAKT101 starts at 150s (planned start was 120s)
            processor.process(new TaktActivated("queue-1", "TAKT101", BASE_TIME.plusSeconds(150)));

            // At 240s: TAKT101 planned end = 120+120=240s. Current time = 240s. Delay = 0.
            // But TAKT101 started at 150, so it's been running 90s of its 120s duration.
            // Delay = max(0, 240 - (120+120)) = 0
            List<SideEffect> effects = processor.process(new TimeEvent(BASE_TIME.plusSeconds(240)));
            assertEquals(1, effects.size());
            assertEquals(0, ((DelayUpdated) effects.get(0)).totalDelaySeconds());
        }
    }

    @Nested
    @DisplayName("Multiple Schedules")
    class MultipleScheduleTests {

        @Test
        @DisplayName("Should track delays independently for multiple schedules")
        void tracksMultipleSchedulesIndependently() {
            processor.process(createSchedule("queue-1", 2, 120));
            processor.process(createSchedule("queue-2", 2, 60));

            // Activate both
            processor.process(new TaktActivated("queue-1", "TAKT100", BASE_TIME));
            processor.process(new TaktActivated("queue-2", "TAKT100", BASE_TIME));

            // At 90s: queue-1 (120s takt) still within planned duration — no emission,
            // queue-2 (60s takt) 30s past planned end — emits delay
            List<SideEffect> effects = processor.process(new TimeEvent(BASE_TIME.plusSeconds(90)));
            assertEquals(1, effects.size());

            DelayUpdated q2Delay = (DelayUpdated) effects.get(0);
            assertEquals("queue-2", q2Delay.workQueueId());
            assertEquals(30, q2Delay.totalDelaySeconds());
        }
    }

    @Nested
    @DisplayName("Schedule Deactivation")
    class ScheduleDeactivationTests {

        @Test
        @DisplayName("Should stop tracking delay after schedule is deactivated")
        void stopsTrackingAfterDeactivation() {
            processor.process(createSchedule("queue-1", 2, 120));
            processor.process(new TaktActivated("queue-1", "TAKT100", BASE_TIME));

            // Deactivate
            processor.process(new WorkQueueMessage("queue-1", INACTIVE, 0, null));

            // No delay should be emitted
            List<SideEffect> effects = processor.process(new TimeEvent(BASE_TIME.plusSeconds(200)));
            assertTrue(effects.isEmpty());
        }
    }

    @Nested
    @DisplayName("State Management")
    class StateManagementTests {

        @Test
        @DisplayName("Should capture and restore state correctly")
        void capturesAndRestoresState() {
            processor.process(createSchedule("queue-1", 2, 120));
            processor.process(new TaktActivated("queue-1", "TAKT100", BASE_TIME));

            // Capture state before any delay
            Object state = processor.captureState();

            // Create a delay
            processor.process(new TimeEvent(BASE_TIME.plusSeconds(150)));

            // Restore state
            processor.restoreState(state);

            // Delay should be recalculated from restored state (no prior emission remembered beyond what was captured)
            List<SideEffect> effects = processor.process(new TimeEvent(BASE_TIME.plusSeconds(150)));
            assertEquals(1, effects.size());
            assertEquals(30, ((DelayUpdated) effects.get(0)).totalDelaySeconds());
        }

        @Test
        @DisplayName("Should throw on invalid state type")
        void throwsOnInvalidStateType() {
            assertThrows(IllegalArgumentException.class, () -> processor.restoreState("invalid"));
        }
    }
}
