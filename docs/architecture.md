# Event Processing Engine Architecture

## Overview

The Event Processing Engine is a plugin-based system for processing events and producing side effects. It follows a clean event-driven architecture where all processing is synchronous and deterministic.

## Core Components

### Event (Sealed Interface)
```
com.wonderingwizard.engine.Event
```
Marker interface for all events. Uses Java 21 sealed types to restrict implementations to known event types, enabling exhaustive pattern matching in processors.

**Implementations:**
- `TimeEvent` - Represents a point in time
- `SetTimeAlarm` - Request to set a time-based alarm
- `WorkQueueMessage` - Work queue message with status for schedule management
- `WorkInstructionEvent` - Work instruction with workQueueId association

### SideEffect (Sealed Interface)
```
com.wonderingwizard.engine.SideEffect
```
Marker interface for all side effects produced by event processing. Side effects represent actions to be taken as a result of processing.

**Implementations:**
- `AlarmSet` - Indicates an alarm was successfully set
- `AlarmTriggered` - Indicates an alarm was triggered
- `ScheduleCreated` - Indicates a schedule was created for a work queue
- `ScheduleAborted` - Indicates a schedule was aborted for a work queue

### EventProcessor (Interface)
```
com.wonderingwizard.engine.EventProcessor
```
Interface for processor plugins. Each processor:
- Receives events via `process(Event event)`
- Returns a list of `SideEffect` (may be empty)
- Has a name for logging purposes
- Supports state capture via `captureState()` for undo functionality
- Supports state restoration via `restoreState(Object state)`

### EventProcessingEngine
```
com.wonderingwizard.engine.EventProcessingEngine
```
The main coordinator that:
- Maintains a list of registered processors
- Routes events to all processors
- Logs all incoming events
- Logs all resulting side effects
- Aggregates side effects from all processors
- Maintains state history for step-back/undo functionality

## Data Flow

```
┌─────────────────────────────────────────────────────────────┐
│                   EventProcessingEngine                      │
│                                                              │
│  ┌──────────┐    ┌─────────────────────────────────────┐    │
│  │          │    │         Registered Processors        │    │
│  │  Event   │───▶│  ┌───────────┐  ┌───────────┐       │    │
│  │  (input) │    │  │Processor 1│  │Processor 2│  ...  │    │
│  │          │    │  └─────┬─────┘  └─────┬─────┘       │    │
│  └──────────┘    │        │              │              │    │
│       │          └────────┼──────────────┼──────────────┘    │
│       │                   │              │                   │
│       ▼                   ▼              ▼                   │
│   [LOG EVENT]      [Side Effects] [Side Effects]            │
│                           │              │                   │
│                           └──────┬───────┘                   │
│                                  ▼                           │
│                         [Aggregate & LOG]                    │
│                                  │                           │
│                                  ▼                           │
│                       List<SideEffect> (output)              │
└─────────────────────────────────────────────────────────────┘
```

## Design Principles

1. **No Blocking Operations**: Event processing must be non-blocking. No `Thread.sleep()` or delays.

2. **Time via Events**: Time-based logic uses `TimeEvent` rather than direct time lookups (`System.currentTimeMillis()`, `Instant.now()`).

3. **Immutable Events**: All events are implemented as Java records, making them immutable.

4. **Type Safety**: Sealed interfaces enable exhaustive pattern matching with compile-time guarantees.

5. **Observable**: All events and side effects are logged for debugging and monitoring.

6. **Reversible**: State can be reverted using the step-back functionality (Memento pattern).

## Step-Back (Undo) Architecture

The engine supports reverting to previous states using the Memento design pattern.

### How It Works

```
┌─────────────────────────────────────────────────────────────────┐
│                    EventProcessingEngine                         │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                    State History                          │   │
│  │  [Snapshot 0] → [Snapshot 1] → [Snapshot 2] → ...        │   │
│  └──────────────────────────────────────────────────────────┘   │
│                              ▲                                   │
│                              │ captureState() before each event  │
│                              │                                   │
│  ┌──────────┐    ┌───────────────────────────────────────┐      │
│  │  Event   │───▶│   Process Event (state captured first) │      │
│  └──────────┘    └───────────────────────────────────────┘      │
│                                                                  │
│  ┌──────────┐    ┌───────────────────────────────────────┐      │
│  │ stepBack │───▶│   Restore last snapshot, remove it     │      │
│  └──────────┘    └───────────────────────────────────────┘      │
└─────────────────────────────────────────────────────────────────┘
```

### State Snapshot Structure

Each snapshot contains the state of all registered processors:

```
Map<EventProcessor, Object> snapshot = {
    TimeAlarmProcessor  → Map<String, Instant> pendingAlarms,
    WorkQueueProcessor  → Map<String, Boolean> activeSchedules,
    ...
}
```

### Implementing State Methods in a Processor

```java
public class MyProcessor implements EventProcessor {
    private final Map<String, MyData> state = new HashMap<>();

    @Override
    public Object captureState() {
        return new HashMap<>(state);  // Defensive copy
    }

    @Override
    @SuppressWarnings("unchecked")
    public void restoreState(Object snapshot) {
        if (!(snapshot instanceof Map)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        state.clear();
        state.putAll((Map<String, MyData>) snapshot);
    }
}
```

### Memory Considerations

- Each processed event adds a snapshot to history
- Use `clearHistory()` to free memory when undo is not needed
- Consider implementing a max history depth for long-running systems

## Package Structure

```
com.wonderingwizard
├── engine/
│   ├── Event.java              # Sealed interface for events
│   ├── SideEffect.java         # Sealed interface for side effects
│   ├── EventProcessor.java     # Processor plugin interface
│   └── EventProcessingEngine.java  # Main engine
├── events/
│   ├── TimeEvent.java          # Time tick event
│   ├── SetTimeAlarm.java       # Alarm setting event
│   ├── WorkQueueMessage.java   # Work queue status message
│   ├── WorkQueueStatus.java    # Work queue status enum
│   ├── WorkInstructionEvent.java # Work instruction event
│   └── WorkInstructionStatus.java # Work instruction status enum
├── sideeffects/
│   ├── AlarmSet.java           # Alarm set confirmation
│   ├── AlarmTriggered.java     # Alarm trigger notification
│   ├── ScheduleCreated.java    # Schedule creation confirmation (includes work instructions)
│   ├── ScheduleAborted.java    # Schedule abortion notification
│   └── WorkInstruction.java    # Work instruction data for ScheduleCreated
├── processors/
│   ├── TimeAlarmProcessor.java # Time alarm handling
│   └── WorkQueueProcessor.java # Work queue schedule handling
└── Main.java                   # Demo entry point
```

## Adding New Features

### Adding a New Event Type
1. Create a record in `com.wonderingwizard.events` implementing `Event`
2. Add the new type to the `permits` clause in `Event.java`
3. Update relevant processors to handle the new event type

### Adding a New Side Effect Type
1. Create a record in `com.wonderingwizard.sideeffects` implementing `SideEffect`
2. Add the new type to the `permits` clause in `SideEffect.java`

### Adding a New Processor
1. Create a class in `com.wonderingwizard.processors` implementing `EventProcessor`
2. Register it with the engine: `engine.register(new MyProcessor())`
