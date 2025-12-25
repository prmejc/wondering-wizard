package com.wonderingwizard;

import com.wonderingwizard.engine.EventProcessingEngine;
import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.SetTimeAlarm;
import com.wonderingwizard.events.TimeEvent;
import com.wonderingwizard.processors.TimeAlarmProcessor;

import java.time.Instant;
import java.util.List;
import java.util.logging.Logger;

/**
 * Main entry point demonstrating the event processing engine with time alarms.
 */
public class Main {

    private static final Logger logger = Logger.getLogger(Main.class.getName());

    public static void main(String[] args) {
        logger.info("=== Event Processing Engine Demo ===");

        // Create and configure the engine
        EventProcessingEngine engine = new EventProcessingEngine();
        engine.register(new TimeAlarmProcessor());

        // Get current time as reference point
        Instant now = Instant.now();

        // Process first TimeEvent - should produce no side effects (no alarms set yet)
        logger.info("\n--- Processing TimeEvent (no alarms set) ---");
        List<SideEffect> sideEffects1 = engine.processEvent(new TimeEvent(now));
        logger.info("sideEffects1 is empty: " + sideEffects1.isEmpty());

        // Set an alarm for 15 seconds in the future - should produce AlarmSet side effect
        logger.info("\n--- Setting alarm 'alarm a' for now + 15 seconds ---");
        Instant alarmTime = now.plusSeconds(15);
        List<SideEffect> sideEffects2 = engine.processEvent(new SetTimeAlarm("alarm a", alarmTime));
        logger.info("sideEffects2 contains alarm set: " + !sideEffects2.isEmpty());

        // Process TimeEvent at now + 20 seconds - should trigger the alarm
        logger.info("\n--- Processing TimeEvent at now + 20 seconds ---");
        Instant futureTime = now.plusSeconds(20);
        List<SideEffect> sideEffects3 = engine.processEvent(new TimeEvent(futureTime));
        logger.info("sideEffects3 contains triggered alarm: " + !sideEffects3.isEmpty());

        // Summary
        logger.info("\n=== Summary ===");
        logger.info("sideEffects1 (should be empty): " + sideEffects1);
        logger.info("sideEffects2 (should contain AlarmSet): " + sideEffects2);
        logger.info("sideEffects3 (should contain AlarmTriggered for 'alarm a'): " + sideEffects3);
    }
}
