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
- `WorkQueueMessage` - Work queue message with status and load mode for schedule management
- `WorkInstructionEvent` - Work instruction with workQueueId association and estimatedMoveTime
- `ActionCompletedEvent` - Notification that an action has been completed (with UUID validation)
- `NukeWorkQueueEvent` - Deletes all data for a work queue (WQ, WIs, schedule)
- `DigitalMapEvent` - Digital map update with edges and travel durations for pathfinding
- `ContainerHandlingEquipmentEvent` - CHE state update (matching ContainerHandlingEquipment.avro schema)

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
- `ActionActivated` - Indicates an action has been activated and is ready for execution (includes deviceType and workInstructions)
- `ActionCompleted` - Indicates an action has been completed
- `TaktActivated` - Indicates a takt has been activated (also implements Event for BFS propagation)
- `TaktCompleted` - Indicates a takt has been completed (also implements Event for BFS propagation)
- `DelayUpdated` - Indicates a schedule's total delay has changed
- `TTStateUpdated` - Indicates a terminal truck's state has been updated
- `TruckAssigned` - Indicates a truck has been assigned to a TT action
- `TruckUnassigned` - Indicates a truck has been unassigned from a TT action (due to TT becoming unavailable)

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
│       ├── CompletionReason.java    # Enum for force-completion reasons (TT_UNAVAILABLE)
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
│   ├── LoadMode.java                # Load mode enum (LOAD, DSCH) for template selection
│   ├── WorkInstructionEvent.java    # Work instruction event (with estimatedMoveTime)
│   ├── WorkInstructionStatus.java   # Work instruction status enum
│   ├── ActionCompletedEvent.java    # Action completion event (with UUID)
│   ├── DigitalMapEvent.java         # Digital map event (edges with travel durations)
│   └── ContainerHandlingEquipmentEvent.java # CHE state update (matching Avro schema)
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
│   ├── DelayUpdated.java            # Schedule delay change notification
│   ├── TTStateUpdated.java          # Terminal truck state update notification
│   ├── TruckAssigned.java          # Truck assigned to TT action notification
│   └── TruckUnassigned.java        # Truck unassigned from TT action notification
├── processors/
│   ├── TimeAlarmProcessor.java      # Time alarm handling
│   ├── WorkQueueProcessor.java      # Work queue schedule and takt generation (with pipeline)
│   ├── GraphScheduleBuilder.java    # Graph-based takt generation (supports pipeline steps)
│   ├── SchedulePipelineStep.java    # Interface for schedule creation pipeline steps
│   ├── DigitalMapProcessor.java     # Digital map parsing + pathfinding (EventProcessor + SchedulePipelineStep)
│   ├── ResourceAction.java          # Legacy action template for imperative takt generation
│   ├── ScheduleRunnerProcessor.java # Schedule execution and action state management
│   ├── DelayProcessor.java         # Schedule delay tracking and calculation
│   ├── EventLogProcessor.java      # Records all events for export/import
│   ├── TTStateProcessor.java       # Terminal truck state management (implements TTAllocationStrategy)
│   ├── TTAllocationStrategy.java   # Interface for truck allocation to TT actions
│   ├── ScheduleSubProcessor.java   # Interface for sub-processors registered with ScheduleRunnerProcessor
│   ├── ScheduleContext.java        # Controlled access to schedule state for sub-processors
│   └── TTUnavailableHandler.java   # Handles TT unavailable events (implements ScheduleSubProcessor)
├── kafka/
│   ├── KafkaConfiguration.java      # Top-level Kafka connection config (broker, SASL, schema registry)
│   ├── ConsumerConfiguration.java   # Per-topic consumer config (topic, group, message type)
│   ├── ProducerConfiguration.java   # Per-topic producer config (topic)
│   ├── EventMapper.java             # Functional interface: GenericRecord → Event (inbound, Avro)
│   ├── JsonEventMapper.java         # Functional interface: String → Event (inbound, JSON)
│   ├── SideEffectMapper.java        # Functional interface: SideEffect → GenericRecord (outbound)
│   ├── KafkaEventConsumer.java      # Generic Avro consumer: poll → map → engine (virtual thread)
│   ├── KafkaJsonEventConsumer.java  # Generic JSON consumer: poll → map → engine (virtual thread)
│   ├── KafkaSideEffectPublisher.java # Generic publisher: side effects → map → Kafka
│   ├── ActionActivatedToEquipmentInstructionMapper.java # Maps ActionActivated → EquipmentInstruction Avro
│   ├── AssetEventMapper.java        # Maps JSON AssetEvent → AssetEvent engine event
│   ├── KafkaConsumerManager.java    # Lifecycle manager for all Kafka consumers (Avro and JSON)
│   ├── WorkQueueEventMapper.java    # Maps WorkQueue Avro → WorkQueueMessage event
│   └── messages/
│       └── WorkQueueKafkaMessage.java # WorkQueue Avro schema as Java record
├── server/
│   ├── DemoServer.java              # HTTP demo server with REST API (JDK HttpServer)
│   ├── JsonSerializer.java          # Hand-rolled JSON serializer (no external libs)
│   ├── JsonParser.java              # Minimal JSON parser for request bodies
│   └── EventDeserializer.java       # Event deserialization from JSON (for import)
├── Main.java                        # Entry point (starts DemoServer or runs demo)
├── e2e/                             # End-to-end tests (run against live server)
│   └── ScheduleCreationPerformanceE2ETest.java # Schedule creation throughput test
└── resources/
    ├── index.html                   # Schedule Viewer UI (Web Components, vanilla JS)
    ├── editor.html                  # Export Editor UI (bulk edit WorkInstruction exports)
    ├── workinstructions.html        # Work Instructions UI (live view/edit of all WIs)
    └── trucks.html                  # Trucks UI (TT state management)
```

## Schedule Creation Pipeline (F-16)

The `WorkQueueProcessor` supports a pluggable pipeline for schedule creation. Pipeline steps are registered dynamically and execute between template creation and takt fitting.

### Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                    Schedule Creation Pipeline                       │
│                                                                     │
│  Step 1: GraphScheduleBuilder.buildContainerBlueprint()             │
│    → ActionTemplate list (default durations)                        │
│                          │                                          │
│  Step 2: SchedulePipelineStep.enrichTemplates() [per registered]    │
│    → ActionTemplate list (modified durations)                       │
│    ┌─────────────────────────┐                                     │
│    │ DigitalMapProcessor     │  pathfind(from, to) → adjust TT     │
│    │ (future enrichers...)   │  durations based on map              │
│    └─────────────────────────┘                                     │
│                          │                                          │
│  Step 3: GraphScheduleBuilder.placeContainerActions()               │
│    → Takt list (actions fitted, extra takts if needed)              │
└──────────────────────────────────────────────────────────────────┘
```

### Key Concepts

- **SchedulePipelineStep**: Interface for enrichment steps. Each step receives action templates for one container and may modify durations or other properties.
- **DigitalMapProcessor**: Dual-role processor — receives `DigitalMapEvent` as `EventProcessor` to store map state, and enriches TT drive durations as `SchedulePipelineStep` during schedule creation.
- **Registration**: Steps are registered via `WorkQueueProcessor.registerStep()` at startup.
- **Passthrough**: If no steps are registered or a step has no applicable data, templates pass through unchanged.

### Conditional Actions (skipWhenGatesSatisfied)

Actions can be marked with `skipWhenGatesSatisfied` to create conditional behavior. Unlike normal event gates (which block activation until satisfied), these gates define a **skip condition**:

- **Gates NOT satisfied at activation time** → action activates normally
- **Gates already satisfied at activation time** → action auto-completes (skipped)

This is used for the conditional `TT_DRIVE_TO_BUFFER` action in discharge templates: the TT drives to buffer only when `QC_DISCHARGED_CONTAINER` events haven't arrived yet. If discharge is already complete, the buffer step is skipped and the TT proceeds directly to RTG.

#### Event Gate Auto-Satisfaction on Reschedule

When a reschedule occurs (e.g., WIs discharged out of order), `transferEventGateState` transfers armed and satisfied gate state from the old schedule to the new one by matching actions via `(actionType, containerIndex, deviceIndex)`. After transferring, it auto-satisfies armed gates whose WorkInstructionEvents already carry the required `eventType`. This handles the case where discharge events were processed against the old schedule (where the WIs didn't match the gates) but the rescheduled actions now reference WIs that have already been discharged.

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
- **Sound system** (`_sound` module) — Web Audio API tone generation for auditory feedback:
  - Action completions play G major notes (octave by device type: QC=1, RTG=2, TT=3; note by action index within container+device)
  - Every 30s, a delay check plays G major triad (on time) or G minor intervals (delay severity mapped to terca/quarta/kvinta/sexta/septima/septakord)
  - 5-second mute on page open to avoid startling

### Work Instructions UI (F-15)

A standalone page at `/workinstructions` that displays all distinct work instructions with their latest state, updated live via SSE. The page:
- Extracts `WorkInstructionEvent` events from the step history
- Deduplicates by `workInstructionId`, keeping only the latest state
- Displays all fields in an editable table (same field set as the Export Editor)
- Each row has a "Send" button that POSTs to `/api/work-instruction` to re-send the (potentially edited) work instruction

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

## Version API and Release Notes (F-23)

The `DemoServer` provides a `/api/version` endpoint that reads `release-notes.html` from the classpath and parses the first `<h2>v...</h2>` tag to extract the latest version. The `/release-notes` endpoint serves the HTML page directly.

### Key Concepts

- **Version Parsing**: Uses regex `<h2>v([^<]+)</h2>` to extract the first version from release-notes.html
- **Release Notes Structure**: Each release is a `<section>` with `id="v{version}"` and an `<h2>v{version}</h2>` heading
- **Frontend Integration**: All GUI pages fetch `/api/version` on load and display "FES v{version}" in the header as a clickable link to `/release-notes`

## Event Log Export/Import (F-12)

The `EventLogProcessor` records every event passing through the engine. Combined with export/import API endpoints, this enables saving and restoring system state.

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                       DemoServer                              │
│                                                               │
│  GET /api/event-log/export                                    │
│    Steps → JSON array [{description, event}, ...]             │
│    → Browser downloads event-log.json                         │
│                                                               │
│  POST /api/event-log/import                                   │
│    JSON array → Reset engine → Replay each step               │
│    EventDeserializer: JSON → Event record                     │
│    processStep(description, event) for each entry             │
│    → State fully restored, SSE broadcast to clients           │
└─────────────────────────────────────────────────────────────┘
```

### Key Concepts

- **EventLogProcessor**: Passive processor that records all events, produces no side effects. Supports state capture/restore for step-back.
- **EventDeserializer**: Reconstructs typed `Event` records from flat JSON maps using the `type` discriminator field.
- **Export Format**: JSON array where each element has `description` (string) and `event` (serialized event object with `type` field).
- **Import**: Resets engine to initial state, then replays events in order via `processStep()`. Deterministic replay produces identical side effects and schedules.

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

### Adding a New Kafka Side Effect Publisher
1. Create a mapper class in `com.wonderingwizard.kafka` implementing `SideEffectMapper<YourSideEffect>`
2. Register with `KafkaSideEffectPublisher`: `publisher.registerMapper(YourSideEffect.class, "topic-name", new YourMapper())`
3. Call `publisher.publish(sideEffects)` after `engine.processEvent()` returns

## Kafka Side Effect Publisher Infrastructure (F-13)

The `kafka` package provides a symmetric outbound framework for publishing side effects to Kafka topics.

### Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                   KafkaSideEffectPublisher                         │
│                                                                    │
│  List<SideEffect> ──▶ Match registered mappers by type             │
│                                                                    │
│  ┌─────────────────────────────┐  ┌─────────────────────────────┐ │
│  │ ActionActivated mapper       │  │ (future side effect mapper)  │ │
│  │                              │  │                              │ │
│  │  SideEffectMapper<AA>        │  │  SideEffectMapper<?>         │ │
│  │  → GenericRecord             │  │  → GenericRecord             │ │
│  │  → KafkaProducer.send()      │  │  → KafkaProducer.send()      │ │
│  │  → EquipmentInstruction topic│  │  → other topic               │ │
│  └─────────────────────────────┘  └─────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────┘
```

### Key Concepts

- **SideEffectMapper<S>**: Functional interface that transforms a `SideEffect` to an Avro `GenericRecord` (outbound counterpart to `EventMapper`)
- **KafkaSideEffectPublisher**: Manages registered mappers, matches side effects by type, and publishes to Kafka (outbound counterpart to `KafkaEventConsumer`)
- **ProducerConfiguration**: Per-topic producer settings
- **ActionActivatedToEquipmentInstructionMapper**: Maps `ActionActivated` (enriched with `DeviceType` and `List<WorkInstruction>`) to the EquipmentInstruction Avro schema. Parameterized with a `Set<ActionType>` to filter which action types to publish. Registered three times: for RTG actions (→ `equipmentinstruction.rubbertyredgantry` topic), TT actions (→ `equipmentinstruction.terminaltruck` topic), and QC actions (→ `equipmentinstruction.quaycrane` topic)
