package com.wonderingwizard.processors;

import com.wonderingwizard.engine.Event;
import com.wonderingwizard.engine.EventProcessingEngine;
import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.TimeEvent;
import com.wonderingwizard.events.WorkInstructionEvent;

import com.wonderingwizard.events.WorkQueueMessage;
import com.wonderingwizard.events.WorkQueueStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for F-12: Event Log Processor feature.
 *
 * @see <a href="docs/requirements.md">F-12 Requirements</a>
 */
@DisplayName("F-12: Event Log Processor")
class EventLogProcessorTest {

    private EventLogProcessor processor;
    private EventProcessingEngine engine;
    private Instant now;

    @BeforeEach
    void setUp() {
        processor = new EventLogProcessor();
        engine = new EventProcessingEngine();
        engine.register(processor);
        now = Instant.parse("2025-01-01T12:00:00Z");
    }

    @Nested
    @DisplayName("F-12.1: Event recording")
    class EventRecording {

        @Test
        @DisplayName("Should return empty side effects for every event")
        void producesNoSideEffects() {
            List<SideEffect> effects = engine.processEvent(new TimeEvent(now));

            assertTrue(effects.isEmpty());
        }

        @Test
        @DisplayName("Should record every event in order")
        void recordsEventsInOrder() {
            TimeEvent event1 = new TimeEvent(now);
            WorkQueueMessage event2 = new WorkQueueMessage(1L, WorkQueueStatus.ACTIVE, 0, null);
            TimeEvent event3 = new TimeEvent(now.plusSeconds(5));

            engine.processEvent(event1);
            engine.processEvent(event2);
            engine.processEvent(event3);

            List<Event> log = processor.getEventLog();
            assertEquals(3, log.size());
            assertEquals(event1, log.get(0));
            assertEquals(event2, log.get(1));
            assertEquals(event3, log.get(2));
        }

        @Test
        @DisplayName("Should return empty log initially")
        void emptyLogInitially() {
            assertTrue(processor.getEventLog().isEmpty());
        }
    }

    @Nested
    @DisplayName("F-12.2: State capture and restore")
    class StateManagement {

        @Test
        @DisplayName("Should capture and restore event log state")
        void captureAndRestore() {
            engine.processEvent(new TimeEvent(now));
            engine.processEvent(new WorkQueueMessage(1L, WorkQueueStatus.ACTIVE, 0, null));

            Object state = processor.captureState();
            assertEquals(2, processor.getEventLog().size());

            engine.processEvent(new TimeEvent(now.plusSeconds(10)));
            assertEquals(3, processor.getEventLog().size());

            processor.restoreState(state);
            assertEquals(2, processor.getEventLog().size());
        }

        @Test
        @DisplayName("Should support step-back via engine")
        void stepBackRestoresLog() {
            engine.snapshot();
            engine.processEvent(new TimeEvent(now));
            engine.snapshot();
            engine.processEvent(new TimeEvent(now.plusSeconds(5)));

            assertEquals(2, processor.getEventLog().size());

            engine.stepBack();
            assertEquals(1, processor.getEventLog().size());
        }

        @Test
        @DisplayName("Should throw for invalid state type")
        void throwsForInvalidState() {
            assertThrows(IllegalArgumentException.class,
                    () -> processor.restoreState("invalid"));
        }
    }

    @Nested
    @DisplayName("F-12.3: Clear functionality")
    class ClearLog {

        @Test
        @DisplayName("Should clear event log")
        void clearsLog() {
            engine.processEvent(new TimeEvent(now));
            engine.processEvent(new TimeEvent(now.plusSeconds(5)));
            assertEquals(2, processor.getEventLog().size());

            processor.clear();
            assertTrue(processor.getEventLog().isEmpty());
        }
    }

    @Nested
    @DisplayName("F-12.4: Log immutability")
    class LogImmutability {

        @Test
        @DisplayName("Should return defensive copy from getEventLog")
        void returnsDefensiveCopy() {
            engine.processEvent(new TimeEvent(now));

            List<Event> log = processor.getEventLog();
            assertEquals(1, log.size());

            assertThrows(UnsupportedOperationException.class,
                    () -> log.add(new TimeEvent(now.plusSeconds(10))));
        }
    }
}
