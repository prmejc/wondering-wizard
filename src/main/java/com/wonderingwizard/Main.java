package com.wonderingwizard;

import com.wonderingwizard.domain.takt.Takt;
import com.wonderingwizard.engine.EventProcessingEngine;
import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.SetTimeAlarm;
import com.wonderingwizard.events.TimeEvent;
import com.wonderingwizard.events.WorkInstructionEvent;
import com.wonderingwizard.events.WorkInstructionStatus;
import com.wonderingwizard.events.WorkQueueMessage;
import com.wonderingwizard.events.WorkQueueStatus;
import com.wonderingwizard.processors.ScheduleRunnerProcessor;
import com.wonderingwizard.processors.TimeAlarmProcessor;
import com.wonderingwizard.processors.WorkQueueProcessor;
import com.wonderingwizard.sideeffects.ScheduleCreated;

import java.time.Instant;
import java.util.List;
import java.util.logging.Logger;

/**
 * Main entry point demonstrating the event processing engine with various demos.
 */
public class Main {

    static {
        try (var is = Main.class.getResourceAsStream("/logging.properties")) {
            if (is != null) {
                java.util.logging.LogManager.getLogManager().readConfiguration(is);
            }
        } catch (Exception e) {
            // Ignore - use default logging configuration
        }
    }

    private static final Logger logger = Logger.getLogger(Main.class.getName());

    public static void main(String[] args) {
        runWorkQueueDemo();
    }

    /**
     * Demonstrates the time alarm functionality of the event processing engine.
     */
    public static void runTimeAlarmDemo() {
        logger.info("=== Time Alarm Demo ===");

        // Create and configure the engine
        EventProcessingEngine engine = new EventProcessingEngine();
        engine.register(new TimeAlarmProcessor());

        // Get current time as reference point
        Instant now = Instant.now();

        // Process first TimeEvent - should produce no side effects (no alarms set yet)
        logger.info("--- Processing TimeEvent (no alarms set) ---");
        List<SideEffect> sideEffects1 = engine.processEvent(new TimeEvent(now));
        logger.info("sideEffects1 is empty: " + sideEffects1.isEmpty());

        // Set an alarm for 15 seconds in the future - should produce AlarmSet side effect
        logger.info("--- Setting alarm 'alarm a' for now + 15 seconds ---");
        Instant alarmTime = now.plusSeconds(15);
        List<SideEffect> sideEffects2 = engine.processEvent(new SetTimeAlarm("alarm a", alarmTime));
        logger.info("sideEffects2 contains alarm set: " + !sideEffects2.isEmpty());

        // Process TimeEvent at now + 20 seconds - should trigger the alarm
        logger.info("--- Processing TimeEvent at now + 20 seconds ---");
        Instant futureTime = now.plusSeconds(20);
        List<SideEffect> sideEffects3 = engine.processEvent(new TimeEvent(futureTime));
        logger.info("sideEffects3 contains triggered alarm: " + !sideEffects3.isEmpty());

        // Summary
        logger.info("=== Summary ===");
        logger.info("sideEffects1 (should be empty): " + sideEffects1);
        logger.info("sideEffects2 (should contain AlarmSet): " + sideEffects2);
        logger.info("sideEffects3 (should contain AlarmTriggered for 'alarm a'): " + sideEffects3);
    }

    /**
     * Demonstrates the work queue functionality with schedule creation and action activation.
     */
    public static void runWorkQueueDemo() {
        logger.info("=== Work Queue Demo ===");

        // Create and configure the engine
        EventProcessingEngine engine = new EventProcessingEngine();
        WorkQueueProcessor workQueueProcessor = new WorkQueueProcessor();
        ScheduleRunnerProcessor scheduleRunnerProcessor = new ScheduleRunnerProcessor();
        engine.register(workQueueProcessor);
        engine.register(scheduleRunnerProcessor);

        // Get current time as reference point
        Instant now = Instant.now();
        Instant estimatedMoveTime = now.plusSeconds(10);

        String workQueueId = "WQ-001";

        // Step 1: Register two work instructions
        logger.info("--- Registering Work Instruction 1 ---");
        List<SideEffect> sideEffects1 = engine.processEvent(new WorkInstructionEvent(
                "WI-001",
                workQueueId,
                "RTG-01",
                WorkInstructionStatus.PENDING,
                estimatedMoveTime
        ));
        logger.info("WorkInstructionEvent 1 side effects: " + sideEffects1);

        logger.info("--- Registering Work Instruction 2 ---");
        List<SideEffect> sideEffects2 = engine.processEvent(new WorkInstructionEvent(
                "WI-002",
                workQueueId,
                "RTG-02",
                WorkInstructionStatus.PENDING,
                estimatedMoveTime.plusSeconds(5)
        ));
        logger.info("WorkInstructionEvent 2 side effects: " + sideEffects2);

        // Step 2: Activate the work queue - should produce ScheduleCreated
        logger.info("--- Activating Work Queue ---");
        List<SideEffect> sideEffects3 = engine.processEvent(new WorkQueueMessage(workQueueId, WorkQueueStatus.ACTIVE));
        logger.info("WorkQueueMessage ACTIVE side effects: " + sideEffects3);

        // Initialize the schedule runner with the created schedule
        for (SideEffect sideEffect : sideEffects3) {
            if (sideEffect instanceof ScheduleCreated scheduleCreated) {
                List<Takt> takts = scheduleCreated.takts();
                logger.info("Schedule created with " + takts.size() + " takts");
                scheduleRunnerProcessor.initializeSchedule(workQueueId, takts, estimatedMoveTime);
            }
        }

        // Step 3: Process TimeEvent at estimated move time to trigger action activation
        logger.info("--- Processing TimeEvent at estimatedMoveTime ---");
        List<SideEffect> sideEffects4 = engine.processEvent(new TimeEvent(estimatedMoveTime));
        logger.info("TimeEvent side effects (should contain ActionActivated): " + sideEffects4);

        // Summary
        logger.info("=== Summary ===");
        logger.info("WorkInstructionEvent 1 (should be empty): " + sideEffects1);
        logger.info("WorkInstructionEvent 2 (should be empty): " + sideEffects2);
        logger.info("WorkQueueMessage ACTIVE (should contain ScheduleCreated): " + sideEffects3);
        logger.info("TimeEvent (should contain ActionActivated): " + sideEffects4);
    }
}
