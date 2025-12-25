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

### SideEffect (Sealed Interface)
```
com.wonderingwizard.engine.SideEffect
```
Marker interface for all side effects produced by event processing. Side effects represent actions to be taken as a result of processing.

**Implementations:**
- `AlarmSet` - Indicates an alarm was successfully set
- `AlarmTriggered` - Indicates an alarm was triggered

### EventProcessor (Interface)
```
com.wonderingwizard.engine.EventProcessor
```
Interface for processor plugins. Each processor:
- Receives events via `process(Event event)`
- Returns a list of `SideEffect` (may be empty)
- Has a name for logging purposes

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
│   └── SetTimeAlarm.java       # Alarm setting event
├── sideeffects/
│   ├── AlarmSet.java           # Alarm set confirmation
│   └── AlarmTriggered.java     # Alarm trigger notification
├── processors/
│   └── TimeAlarmProcessor.java # Time alarm handling
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
