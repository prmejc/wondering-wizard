# Event Processing Engine Architecture

## Overview

The Event Processing Engine is a plugin-based system for processing events and producing side effects. It follows a clean event-driven architecture where all processing is synchronous and deterministic.

## Core Components

### Event (Interface)
```
com.wonderingwizard.engine.Event
```
Marker interface for all events. Enables pattern matching in processors.

**Implementations:**
- `TimeEvent` - Represents a point in time
- `SetTimeAlarm` - Request to set a time-based alarm
- `WorkQueueMessage` - Work queue message with status for schedule management
- `WorkInstructionEvent` - Work instruction with workQueueId association and estimatedMoveTime
- `ActionCompletedEvent` - Notification that an action has been completed (with UUID validation)

### SideEffect (Sealed Interface)
```
com.wonderingwizard.engine.SideEffect
```
Marker interface for all side effects produced by event processing. Side effects represent actions to be taken as a result of processing.

**Implementations:**
- `AlarmSet` - Indicates an alarm was successfully set
- `AlarmTriggered` - Indicates an alarm was triggered
- `ScheduleCreated` - Indicates a schedule was created for a work queue (includes takts)
- `ScheduleAborted` - Indicates a schedule was aborted for a work queue
- `ActionActivated` - Indicates an action has been activated and is ready for execution
- `ActionCompleted` - Indicates an action has been completed
- `TaktActivated` - Indicates a takt has been activated (also implements Event for BFS propagation)
- `TaktCompleted` - Indicates a takt has been completed (also implements Event for BFS propagation)
- `DelayUpdated` - Indicates a schedule's total delay has changed

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
├── domain/
│   └── takt/
│       ├── Takt.java                # Takt containing actions (named TAKT100, TAKT101, etc.)
│       ├── Action.java              # Action with UUID, deviceType, description, and dependsOn set
│       ├── DeviceType.java          # Device type enum (RTG, TT, QC)
│       ├── DeviceActionTemplate.java # Template for device actions in workflow
│       └── ContainerWorkflow.java   # Defines RTG→TT→QC workflow with takt offsets
├── engine/
│   ├── Event.java                   # Interface for events
│   ├── SideEffect.java              # Sealed interface for side effects
│   ├── Engine.java                  # Engine interface (processEvent, stepBack, etc.)
│   ├── EventProcessor.java          # Processor plugin interface
│   ├── EventProcessingEngine.java   # Main engine with state history (Memento)
│   └── EventPropagatingEngine.java  # Decorator: BFS processing of side-effects-as-events
├── events/
│   ├── TimeEvent.java               # Time tick event
│   ├── SetTimeAlarm.java            # Alarm setting event
│   ├── WorkQueueMessage.java        # Work queue status message
│   ├── WorkQueueStatus.java         # Work queue status enum
│   ├── WorkInstructionEvent.java    # Work instruction event (with estimatedMoveTime)
│   ├── WorkInstructionStatus.java   # Work instruction status enum
│   └── ActionCompletedEvent.java    # Action completion event (with UUID)
├── sideeffects/
│   ├── AlarmSet.java                # Alarm set confirmation
│   ├── AlarmTriggered.java          # Alarm trigger notification
│   ├── ScheduleCreated.java         # Schedule creation (includes takts; also implements Event)
│   ├── ScheduleAborted.java         # Schedule abortion notification
│   ├── WorkInstruction.java         # Work instruction data (with estimatedMoveTime)
│   ├── ActionActivated.java         # Action activation notification
│   ├── ActionCompleted.java         # Action completion notification
│   ├── TaktActivated.java           # Takt activation (also implements Event)
│   ├── TaktCompleted.java           # Takt completion (also implements Event)
│   └── DelayUpdated.java            # Schedule delay change notification
├── processors/
│   ├── TimeAlarmProcessor.java      # Time alarm handling
│   ├── WorkQueueProcessor.java      # Work queue schedule and takt generation
│   ├── GraphScheduleBuilder.java    # Graph-based takt generation (feature-flagged alternative)
│   ├── ResourceAction.java          # Legacy action template for imperative takt generation
│   ├── ScheduleRunnerProcessor.java # Schedule execution and action state management
│   └── DelayProcessor.java         # Schedule delay tracking and calculation
├── kafka/
│   ├── KafkaConfiguration.java      # Top-level Kafka connection config (broker, SASL, schema registry)
│   ├── ConsumerConfiguration.java   # Per-topic consumer config (topic, group, message type)
│   ├── EventMapper.java             # Functional interface: GenericRecord → Event
│   ├── KafkaEventConsumer.java      # Generic consumer: poll → map → engine (virtual thread)
│   ├── KafkaConsumerManager.java    # Lifecycle manager for all Kafka consumers
│   ├── WorkQueueEventMapper.java    # Maps WorkQueue Avro → WorkQueueMessage event
│   └── messages/
│       └── WorkQueueKafkaMessage.java # WorkQueue Avro schema as Java record
├── server/
│   ├── DemoServer.java              # HTTP demo server with REST API (JDK HttpServer)
│   ├── JsonSerializer.java          # Hand-rolled JSON serializer (no external libs)
│   └── JsonParser.java              # Minimal JSON parser for request bodies
├── Main.java                        # Entry point (starts DemoServer or runs demo)
└── resources/
    └── index.html                   # Schedule Viewer UI (Web Components, vanilla JS)
```

## HTTP Demo Server (F-7)

The `DemoServer` class provides an embedded HTTP server using JDK `com.sun.net.httpserver.HttpServer` with zero external dependencies. It wraps an `EventPropagatingEngine` and exposes a REST API.

### Key Concepts

- **Step Tracking**: Each user-initiated event is recorded as a numbered `Step` with its resulting side effects and the `engineHistoryDelta` (number of engine history entries consumed, accounting for EventPropagatingEngine expansion)
- **Step-Back Accounting**: When stepping back, the server uses `engineHistoryDelta` to issue the correct number of `engine.stepBack()` calls
- **Schedule View Derivation**: Current schedule state is derived from accumulated side effects (ScheduleCreated, ScheduleAborted, ActionActivated, ActionCompleted) rather than exposing internal processor state
- **Simulated Time**: Time starts at `2024-01-01T00:00:00Z` and advances via tick events

### JSON Serialization (M-3)

`JsonSerializer` uses pattern matching on sealed types for exhaustive serialization with zero external libraries. `JsonParser` provides minimal flat-object parsing for request bodies.

### Schedule Viewer UI (M-2)

A single `index.html` file using Web Components (extending `HTMLElement`) with vanilla JavaScript:
- `<schedule-header>` — Clock display + tick buttons
- `<event-panel>` — Event input forms
- `<schedule-view>` — Takt/action visualization with color-coded status
- `<timeline-panel>` — Clickable event history for time travel
- `<side-effects-log>` — Color-coded side effects log

## Kafka Event Consumer Infrastructure (F-11)

The `kafka` package provides a generic framework for consuming messages from Kafka topics and feeding them into the event processing engine.

### Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                     KafkaConsumerManager                          │
│                                                                   │
│  ┌─────────────────────┐  ┌─────────────────────┐               │
│  │ KafkaEventConsumer   │  │ KafkaEventConsumer   │  ...         │
│  │ (WorkQueue topic)    │  │ (future topic)       │              │
│  │                      │  │                      │              │
│  │  ┌───────────────┐  │  │  ┌───────────────┐  │              │
│  │  │ Avro Deser.   │  │  │  │ Avro Deser.   │  │              │
│  │  └───────┬───────┘  │  │  └───────┬───────┘  │              │
│  │          ▼           │  │          ▼           │              │
│  │  ┌───────────────┐  │  │  ┌───────────────┐  │              │
│  │  │ EventMapper   │  │  │  │ EventMapper   │  │              │
│  │  │ (WorkQueue)   │  │  │  │ (YourType)    │  │              │
│  │  └───────┬───────┘  │  │  └───────┬───────┘  │              │
│  │          ▼           │  │          ▼           │              │
│  │  Engine.processEvent │  │  Engine.processEvent │              │
│  └─────────────────────┘  └─────────────────────┘               │
└──────────────────────────────────────────────────────────────────┘
```

### Key Concepts

- **KafkaConfiguration**: Shared connection settings (broker, SASL, schema registry)
- **ConsumerConfiguration**: Per-topic settings (topic name, group ID, Avro/JSON type, offset reset)
- **EventMapper<E>**: Functional interface that transforms an Avro `GenericRecord` to an engine `Event`
- **KafkaEventConsumer<E>**: Generic consumer running on a virtual thread — polls, deserializes, maps, and processes
- **KafkaConsumerManager**: Registers and manages the lifecycle of all consumers

### Adding a New Kafka Topic Consumer

1. Create a message record in `kafka/messages/` matching the Avro schema
2. Create a mapper class implementing `EventMapper<YourEvent>`
3. Register with `KafkaConsumerManager` using the topic's `ConsumerConfiguration`

### Dependencies

- `org.apache.kafka:kafka-clients` — Kafka consumer client
- `org.apache.avro:avro` — Avro record types
- `io.confluent:kafka-avro-serializer` — Schema Registry–aware Avro deserializer

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

### Adding a New Kafka Topic Consumer
1. Create a message record in `com.wonderingwizard.kafka.messages` matching the Avro schema
2. Create a mapper class in `com.wonderingwizard.kafka` implementing `EventMapper<YourEvent>`
3. Create a `ConsumerConfiguration` with the topic name, group ID, and Avro message type
4. Register with `KafkaConsumerManager`: `manager.register(config, new YourEventMapper())`
