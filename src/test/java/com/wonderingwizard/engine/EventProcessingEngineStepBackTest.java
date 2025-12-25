package com.wonderingwizard.engine;

import com.wonderingwizard.events.SetTimeAlarm;
import com.wonderingwizard.events.TimeEvent;
import com.wonderingwizard.events.WorkQueueMessage;
import com.wonderingwizard.events.WorkQueueStatus;
import com.wonderingwizard.processors.TimeAlarmProcessor;
import com.wonderingwizard.processors.WorkQueueProcessor;
import com.wonderingwizard.sideeffects.AlarmSet;
import com.wonderingwizard.sideeffects.AlarmTriggered;
import com.wonderingwizard.sideeffects.ScheduleCreated;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("EventProcessingEngine Step Back")
class EventProcessingEngineStepBackTest {

    private EventProcessingEngine engine;
    private TimeAlarmProcessor timeAlarmProcessor;
    private WorkQueueProcessor workQueueProcessor;

    @BeforeEach
    void setUp() {
        engine = new EventProcessingEngine();
        timeAlarmProcessor = new TimeAlarmProcessor();
        workQueueProcessor = new WorkQueueProcessor();
        engine.register(timeAlarmProcessor);
        engine.register(workQueueProcessor);
    }

    @Nested
    @DisplayName("Basic Step Back Operations")
    class BasicStepBackOperations {

        @Test
        @DisplayName("stepBack returns false when no history exists")
        void stepBackWithNoHistory() {
            assertFalse(engine.stepBack());
            assertEquals(0, engine.getHistorySize());
        }

        @Test
        @DisplayName("stepBack returns true and reverts state after one event")
        void stepBackAfterOneEvent() {
            Instant triggerTime = Instant.parse("2024-01-01T12:00:00Z");
            engine.processEvent(new SetTimeAlarm("alarm1", triggerTime));

            assertEquals(1, engine.getHistorySize());

            // Step back should succeed
            assertTrue(engine.stepBack());
            assertEquals(0, engine.getHistorySize());

            // Processing a time event should not trigger the alarm (it was reverted)
            Instant afterTrigger = Instant.parse("2024-01-01T13:00:00Z");
            List<SideEffect> sideEffects = engine.processEvent(new TimeEvent(afterTrigger));

            // No AlarmTriggered because the alarm was reverted
            assertTrue(sideEffects.stream().noneMatch(se -> se instanceof AlarmTriggered));
        }

        @Test
        @DisplayName("stepBack reverts multiple events one at a time")
        void stepBackMultipleEvents() {
            engine.processEvent(new SetTimeAlarm("alarm1", Instant.parse("2024-01-01T12:00:00Z")));
            engine.processEvent(new SetTimeAlarm("alarm2", Instant.parse("2024-01-01T13:00:00Z")));
            engine.processEvent(new SetTimeAlarm("alarm3", Instant.parse("2024-01-01T14:00:00Z")));

            assertEquals(3, engine.getHistorySize());

            // Step back once - removes alarm3
            assertTrue(engine.stepBack());
            assertEquals(2, engine.getHistorySize());

            // Step back again - removes alarm2
            assertTrue(engine.stepBack());
            assertEquals(1, engine.getHistorySize());

            // Process time event - only alarm1 should trigger
            Instant afterAll = Instant.parse("2024-01-01T15:00:00Z");
            List<SideEffect> sideEffects = engine.processEvent(new TimeEvent(afterAll));

            List<AlarmTriggered> triggered = sideEffects.stream()
                    .filter(se -> se instanceof AlarmTriggered)
                    .map(se -> (AlarmTriggered) se)
                    .toList();

            assertEquals(1, triggered.size());
            assertEquals("alarm1", triggered.get(0).alarmName());
        }
    }

    @Nested
    @DisplayName("Step Back with TimeAlarmProcessor")
    class StepBackWithTimeAlarmProcessor {

        @Test
        @DisplayName("stepBack reverts alarm creation")
        void stepBackRevertsAlarmCreation() {
            Instant triggerTime = Instant.parse("2024-01-01T12:00:00Z");
            List<SideEffect> createEffects = engine.processEvent(new SetTimeAlarm("alarm1", triggerTime));

            // Alarm was set
            assertTrue(createEffects.stream().anyMatch(se -> se instanceof AlarmSet));

            // Step back
            engine.stepBack();

            // Trigger time passed - no alarm should fire
            List<SideEffect> triggerEffects = engine.processEvent(new TimeEvent(Instant.parse("2024-01-01T13:00:00Z")));
            assertTrue(triggerEffects.stream().noneMatch(se -> se instanceof AlarmTriggered));
        }

        @Test
        @DisplayName("stepBack restores triggered alarms")
        void stepBackRestoresTriggeredAlarms() {
            Instant triggerTime = Instant.parse("2024-01-01T12:00:00Z");
            engine.processEvent(new SetTimeAlarm("alarm1", triggerTime));

            // Trigger the alarm
            Instant afterTrigger = Instant.parse("2024-01-01T13:00:00Z");
            List<SideEffect> triggerEffects = engine.processEvent(new TimeEvent(afterTrigger));
            assertTrue(triggerEffects.stream().anyMatch(se -> se instanceof AlarmTriggered));

            // Step back - the alarm should be pending again
            engine.stepBack();

            // Trigger again - alarm should fire again
            List<SideEffect> retriggerEffects = engine.processEvent(new TimeEvent(afterTrigger));
            assertTrue(retriggerEffects.stream().anyMatch(se -> se instanceof AlarmTriggered));
        }
    }

    @Nested
    @DisplayName("Step Back with WorkQueueProcessor")
    class StepBackWithWorkQueueProcessor {

        @Test
        @DisplayName("stepBack reverts schedule creation")
        void stepBackRevertsScheduleCreation() {
            List<SideEffect> createEffects = engine.processEvent(
                    new WorkQueueMessage("queue1", WorkQueueStatus.ACTIVE));

            assertTrue(createEffects.stream().anyMatch(se -> se instanceof ScheduleCreated));

            // Step back
            engine.stepBack();

            // Creating the same schedule should produce ScheduleCreated again (not idempotent)
            List<SideEffect> recreateEffects = engine.processEvent(
                    new WorkQueueMessage("queue1", WorkQueueStatus.ACTIVE));

            assertTrue(recreateEffects.stream().anyMatch(se -> se instanceof ScheduleCreated));
        }

        @Test
        @DisplayName("stepBack reverts schedule abortion")
        void stepBackRevertsScheduleAbortion() {
            // Create schedule
            engine.processEvent(new WorkQueueMessage("queue1", WorkQueueStatus.ACTIVE));

            // Abort schedule
            engine.processEvent(new WorkQueueMessage("queue1", WorkQueueStatus.INACTIVE));

            // Step back - schedule should be active again
            engine.stepBack();

            // Trying to create again should be idempotent (no side effect)
            List<SideEffect> recreateEffects = engine.processEvent(
                    new WorkQueueMessage("queue1", WorkQueueStatus.ACTIVE));

            assertTrue(recreateEffects.stream().noneMatch(se -> se instanceof ScheduleCreated));
        }
    }

    @Nested
    @DisplayName("Step Back with Multiple Processors")
    class StepBackWithMultipleProcessors {

        @Test
        @DisplayName("stepBack reverts state in all processors simultaneously")
        void stepBackRevertsAllProcessors() {
            // Set an alarm and create a schedule in one go (process both events)
            engine.processEvent(new SetTimeAlarm("alarm1", Instant.parse("2024-01-01T12:00:00Z")));
            engine.processEvent(new WorkQueueMessage("queue1", WorkQueueStatus.ACTIVE));

            assertEquals(2, engine.getHistorySize());

            // Step back once - reverts schedule creation
            engine.stepBack();

            // Step back again - reverts alarm creation
            engine.stepBack();

            // Both should be gone
            List<SideEffect> effects = engine.processEvent(new TimeEvent(Instant.parse("2024-01-01T13:00:00Z")));
            assertTrue(effects.stream().noneMatch(se -> se instanceof AlarmTriggered));

            effects = engine.processEvent(new WorkQueueMessage("queue1", WorkQueueStatus.ACTIVE));
            assertTrue(effects.stream().anyMatch(se -> se instanceof ScheduleCreated));
        }
    }

    @Nested
    @DisplayName("History Management")
    class HistoryManagement {

        @Test
        @DisplayName("getHistorySize returns correct count")
        void getHistorySizeReturnsCorrectCount() {
            assertEquals(0, engine.getHistorySize());

            engine.processEvent(new SetTimeAlarm("alarm1", Instant.now()));
            assertEquals(1, engine.getHistorySize());

            engine.processEvent(new SetTimeAlarm("alarm2", Instant.now()));
            assertEquals(2, engine.getHistorySize());

            engine.stepBack();
            assertEquals(1, engine.getHistorySize());
        }

        @Test
        @DisplayName("clearHistory removes all history")
        void clearHistoryRemovesAllHistory() {
            engine.processEvent(new SetTimeAlarm("alarm1", Instant.now()));
            engine.processEvent(new SetTimeAlarm("alarm2", Instant.now()));
            engine.processEvent(new SetTimeAlarm("alarm3", Instant.now()));

            assertEquals(3, engine.getHistorySize());

            engine.clearHistory();

            assertEquals(0, engine.getHistorySize());
            assertFalse(engine.stepBack());
        }

        @Test
        @DisplayName("clearHistory does not affect current state")
        void clearHistoryDoesNotAffectCurrentState() {
            Instant triggerTime = Instant.parse("2024-01-01T12:00:00Z");
            engine.processEvent(new SetTimeAlarm("alarm1", triggerTime));

            engine.clearHistory();

            // Alarm should still be there and trigger
            Instant afterTrigger = Instant.parse("2024-01-01T13:00:00Z");
            List<SideEffect> effects = engine.processEvent(new TimeEvent(afterTrigger));

            assertTrue(effects.stream().anyMatch(se -> se instanceof AlarmTriggered));
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("multiple stepBacks followed by events work correctly")
        void multipleStepBacksThenEvents() {
            engine.processEvent(new SetTimeAlarm("alarm1", Instant.parse("2024-01-01T10:00:00Z")));
            engine.processEvent(new SetTimeAlarm("alarm2", Instant.parse("2024-01-01T11:00:00Z")));

            // Step back twice
            engine.stepBack();
            engine.stepBack();

            // Add new alarms
            engine.processEvent(new SetTimeAlarm("alarm3", Instant.parse("2024-01-01T12:00:00Z")));

            // Only alarm3 should trigger
            List<SideEffect> effects = engine.processEvent(new TimeEvent(Instant.parse("2024-01-01T13:00:00Z")));
            List<AlarmTriggered> triggered = effects.stream()
                    .filter(se -> se instanceof AlarmTriggered)
                    .map(se -> (AlarmTriggered) se)
                    .toList();

            assertEquals(1, triggered.size());
            assertEquals("alarm3", triggered.get(0).alarmName());
        }

        @Test
        @DisplayName("stepBack with no-op events still creates history")
        void stepBackWithNoOpEvents() {
            // Process an event that produces no side effects
            engine.processEvent(new WorkQueueMessage("queue1", WorkQueueStatus.INACTIVE));

            assertEquals(1, engine.getHistorySize());
            assertTrue(engine.stepBack());
            assertEquals(0, engine.getHistorySize());
        }
    }
}
