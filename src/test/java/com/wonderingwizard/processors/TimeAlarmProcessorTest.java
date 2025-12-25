package com.wonderingwizard.processors;

import com.wonderingwizard.engine.EventProcessingEngine;
import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.SetTimeAlarm;
import com.wonderingwizard.events.TimeEvent;
import com.wonderingwizard.sideeffects.AlarmSet;
import com.wonderingwizard.sideeffects.AlarmTriggered;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for F-1: Time Alarm Processor feature.
 *
 * @see <a href="docs/requirements.md">F-1 Requirements</a>
 */
@DisplayName("F-1: Time Alarm Processor")
class TimeAlarmProcessorTest {

    private EventProcessingEngine engine;
    private Instant now;

    @BeforeEach
    void setUp() {
        engine = new EventProcessingEngine();
        engine.register(new TimeAlarmProcessor());
        now = Instant.parse("2025-01-01T12:00:00Z");
    }

    @Nested
    @DisplayName("F-1.1: TimeEvent with no alarms set")
    class NoAlarmsSet {

        @Test
        @DisplayName("Should return empty side effects when no alarms are set")
        void timeEventWithNoAlarms_returnsEmptySideEffects() {
            List<SideEffect> sideEffects = engine.processEvent(new TimeEvent(now));

            assertTrue(sideEffects.isEmpty(),
                    "sideEffects1 should be empty when no alarms are set");
        }

        @Test
        @DisplayName("Should return empty side effects for multiple TimeEvents with no alarms")
        void multipleTimeEventsWithNoAlarms_returnsEmptySideEffects() {
            List<SideEffect> sideEffects1 = engine.processEvent(new TimeEvent(now));
            List<SideEffect> sideEffects2 = engine.processEvent(new TimeEvent(now.plusSeconds(5)));
            List<SideEffect> sideEffects3 = engine.processEvent(new TimeEvent(now.plusSeconds(10)));

            assertTrue(sideEffects1.isEmpty());
            assertTrue(sideEffects2.isEmpty());
            assertTrue(sideEffects3.isEmpty());
        }
    }

    @Nested
    @DisplayName("F-1.2: SetTimeAlarm event")
    class SetAlarm {

        @Test
        @DisplayName("Should return AlarmSet side effect when alarm is set")
        void setTimeAlarm_returnsAlarmSetSideEffect() {
            Instant alarmTime = now.plusSeconds(15);

            List<SideEffect> sideEffects = engine.processEvent(
                    new SetTimeAlarm("alarm a", alarmTime));

            assertEquals(1, sideEffects.size(),
                    "sideEffects2 should contain exactly one side effect");
            assertInstanceOf(AlarmSet.class, sideEffects.get(0),
                    "Side effect should be AlarmSet");

            AlarmSet alarmSet = (AlarmSet) sideEffects.get(0);
            assertEquals("alarm a", alarmSet.alarmName());
            assertEquals(alarmTime, alarmSet.triggerTime());
        }

        @Test
        @DisplayName("Should return AlarmSet for each alarm when multiple alarms are set")
        void multipleSetTimeAlarms_returnsMultipleAlarmSetSideEffects() {
            List<SideEffect> sideEffects1 = engine.processEvent(
                    new SetTimeAlarm("alarm a", now.plusSeconds(10)));
            List<SideEffect> sideEffects2 = engine.processEvent(
                    new SetTimeAlarm("alarm b", now.plusSeconds(20)));

            assertEquals(1, sideEffects1.size());
            assertEquals(1, sideEffects2.size());
            assertInstanceOf(AlarmSet.class, sideEffects1.get(0));
            assertInstanceOf(AlarmSet.class, sideEffects2.get(0));
            assertEquals("alarm a", ((AlarmSet) sideEffects1.get(0)).alarmName());
            assertEquals("alarm b", ((AlarmSet) sideEffects2.get(0)).alarmName());
        }
    }

    @Nested
    @DisplayName("F-1.3: Alarm triggering")
    class AlarmTriggering {

        @Test
        @DisplayName("Should trigger alarm when TimeEvent passes alarm time")
        void timeEventAfterAlarmTime_triggersAlarm() {
            // Set alarm for now + 15 seconds
            Instant alarmTime = now.plusSeconds(15);
            engine.processEvent(new SetTimeAlarm("alarm a", alarmTime));

            // Process TimeEvent at now + 20 seconds (after alarm time)
            Instant futureTime = now.plusSeconds(20);
            List<SideEffect> sideEffects = engine.processEvent(new TimeEvent(futureTime));

            assertEquals(1, sideEffects.size(),
                    "sideEffects3 should contain exactly one side effect");
            assertInstanceOf(AlarmTriggered.class, sideEffects.get(0),
                    "Side effect should be AlarmTriggered for 'alarm a'");

            AlarmTriggered triggered = (AlarmTriggered) sideEffects.get(0);
            assertEquals("alarm a", triggered.alarmName());
            assertEquals(futureTime, triggered.triggeredAt());
        }

        @Test
        @DisplayName("Should trigger alarm exactly at alarm time")
        void timeEventExactlyAtAlarmTime_triggersAlarm() {
            Instant alarmTime = now.plusSeconds(15);
            engine.processEvent(new SetTimeAlarm("alarm a", alarmTime));

            List<SideEffect> sideEffects = engine.processEvent(new TimeEvent(alarmTime));

            assertEquals(1, sideEffects.size());
            assertInstanceOf(AlarmTriggered.class, sideEffects.get(0));
            assertEquals("alarm a", ((AlarmTriggered) sideEffects.get(0)).alarmName());
        }

        @Test
        @DisplayName("Should not trigger alarm when TimeEvent is before alarm time")
        void timeEventBeforeAlarmTime_doesNotTriggerAlarm() {
            Instant alarmTime = now.plusSeconds(15);
            engine.processEvent(new SetTimeAlarm("alarm a", alarmTime));

            // Process TimeEvent at now + 10 seconds (before alarm time)
            List<SideEffect> sideEffects = engine.processEvent(
                    new TimeEvent(now.plusSeconds(10)));

            assertTrue(sideEffects.isEmpty(),
                    "Should not trigger alarm before alarm time");
        }

        @Test
        @DisplayName("Should only trigger alarm once")
        void alarmShouldOnlyTriggerOnce() {
            Instant alarmTime = now.plusSeconds(15);
            engine.processEvent(new SetTimeAlarm("alarm a", alarmTime));

            // First TimeEvent triggers the alarm
            List<SideEffect> sideEffects1 = engine.processEvent(
                    new TimeEvent(now.plusSeconds(20)));
            // Second TimeEvent should not trigger again
            List<SideEffect> sideEffects2 = engine.processEvent(
                    new TimeEvent(now.plusSeconds(25)));

            assertEquals(1, sideEffects1.size());
            assertTrue(sideEffects2.isEmpty(),
                    "Alarm should not trigger twice");
        }

        @Test
        @DisplayName("Should trigger multiple alarms when their times pass")
        void multipleAlarms_triggeredWhenTimePasses() {
            engine.processEvent(new SetTimeAlarm("alarm a", now.plusSeconds(10)));
            engine.processEvent(new SetTimeAlarm("alarm b", now.plusSeconds(15)));
            engine.processEvent(new SetTimeAlarm("alarm c", now.plusSeconds(30)));

            // TimeEvent at +20 should trigger alarm a and alarm b
            List<SideEffect> sideEffects = engine.processEvent(
                    new TimeEvent(now.plusSeconds(20)));

            assertEquals(2, sideEffects.size());
            assertTrue(sideEffects.stream()
                    .filter(se -> se instanceof AlarmTriggered)
                    .map(se -> ((AlarmTriggered) se).alarmName())
                    .toList()
                    .containsAll(List.of("alarm a", "alarm b")));
        }
    }

    @Nested
    @DisplayName("F-1.4: Complete workflow as per requirements")
    class CompleteWorkflow {

        @Test
        @DisplayName("Should match exact behavior from F-1 requirements")
        void completeF1Workflow() {
            // Step 1: TimeEvent with no alarms - should be empty
            List<SideEffect> sideEffects1 = engine.processEvent(new TimeEvent(now));
            assertTrue(sideEffects1.isEmpty(),
                    "F-1 Requirement: sideEffects1 should be empty");

            // Step 2: SetTimeAlarm - should contain AlarmSet
            Instant alarmTime = now.plusSeconds(15);
            List<SideEffect> sideEffects2 = engine.processEvent(
                    new SetTimeAlarm("alarm a", alarmTime));
            assertEquals(1, sideEffects2.size(),
                    "F-1 Requirement: sideEffects2 should contain alarm set");
            assertInstanceOf(AlarmSet.class, sideEffects2.get(0));

            // Step 3: TimeEvent after alarm time - should trigger alarm
            Instant futureTime = now.plusSeconds(20);
            List<SideEffect> sideEffects3 = engine.processEvent(new TimeEvent(futureTime));
            assertEquals(1, sideEffects3.size(),
                    "F-1 Requirement: sideEffects3 should contain triggered alarm");
            assertInstanceOf(AlarmTriggered.class, sideEffects3.get(0));
            assertEquals("alarm a", ((AlarmTriggered) sideEffects3.get(0)).alarmName(),
                    "F-1 Requirement: 'alarm a' should be triggered");
        }
    }
}
