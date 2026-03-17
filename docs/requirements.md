# Requirements

## Features

### F-1: Time Alarm Processor

**Status:** Implemented

**Description:**
Implement a TimeAlarmProcessor that can be registered with the event processing engine to handle time-based alarms. The processor should:

1. Accept `SetTimeAlarm` events to schedule alarms with a name and trigger time
2. Accept `TimeEvent` events representing the current time
3. Trigger alarms when the time from a `TimeEvent` reaches or passes the scheduled alarm time

**Requested Behavior:**

```java
engine.register(new TimeAlarmProcessor());

var sideEffects1 = engine.processEvent(new TimeEvent());
var sideEffects2 = engine.processEvent(new SetTimeAlarm("alarm a", now + 15 seconds));
var sideEffects3 = engine.processEvent(new TimeEvent(now + 20 seconds));
```

**Expected Results:**
- `sideEffects1` should be empty (no alarms set yet)
- `sideEffects2` should contain a side effect indicating the alarm has been set
- `sideEffects3` should contain a side effect indicating "alarm a" has been triggered

**Additional Requirements:**
- All events going into the engine should be logged
- All resulting side effects should be logged

**Verification:**

| Step | Action | Expected Result |
|------|--------|-----------------|
| 1 | Register `TimeAlarmProcessor` with engine | Processor registered successfully, logged |
| 2 | Process `TimeEvent` with no alarms set | Empty side effects list, event logged |
| 3 | Process `SetTimeAlarm("alarm a", now + 15s)` | Returns `[AlarmSet]`, event and side effect logged |
| 4 | Process `TimeEvent(now + 20s)` | Returns `[AlarmTriggered]` for "alarm a", event and side effect logged |

**Test Execution:**
```bash
# Compile
javac --enable-preview --source 21 -d out src/main/java/**/*.java

# Run
java --enable-preview -cp out com.wonderingwizard.Main
```

**Expected Output:**
```
INFO: Processing event: TimeEvent[timestamp=...]
INFO: No side effects produced

INFO: Processing event: SetTimeAlarm[alarmName=alarm a, triggerTime=...]
INFO: Side effect: AlarmSet[alarmName=alarm a, triggerTime=...]

INFO: Processing event: TimeEvent[timestamp=...]
INFO: Side effect: AlarmTriggered[alarmName=alarm a, triggeredAt=...]
```

**Implementation Files:**
- `src/main/java/com/wonderingwizard/events/TimeEvent.java`
- `src/main/java/com/wonderingwizard/events/SetTimeAlarm.java`
- `src/main/java/com/wonderingwizard/sideeffects/AlarmSet.java`
- `src/main/java/com/wonderingwizard/sideeffects/AlarmTriggered.java`
- `src/main/java/com/wonderingwizard/processors/TimeAlarmProcessor.java`
- `src/main/java/com/wonderingwizard/engine/EventProcessingEngine.java`

---

### F-2: Schedule Creation

**Status:** Implemented

**Description:**
Implement a WorkQueueProcessor that manages schedule creation based on work queue messages. The processor should:

1. Accept `WorkQueueMessage` events with a `workQueueId`, `status` (using `WorkQueueStatus` enum), and `loadMode` (using `LoadMode` enum: `LOAD` or `DSCH`) field
2. Create a new schedule when a message with status `ACTIVE` is received for a new workQueueId
3. Be idempotent for duplicate `ACTIVE` messages with the same workQueueId
4. Abort the schedule when a message with status `INACTIVE` is received for an existing workQueueId

**Requested Behavior:**

```java
import static com.wonderingwizard.events.WorkQueueStatus.ACTIVE;
import static com.wonderingwizard.events.WorkQueueStatus.INACTIVE;

engine.register(new WorkQueueProcessor());

var sideEffects1 = engine.processEvent(new WorkQueueMessage("queue-1", ACTIVE));
var sideEffects2 = engine.processEvent(new WorkQueueMessage("queue-1", ACTIVE));
var sideEffects3 = engine.processEvent(new WorkQueueMessage("queue-1", INACTIVE));
```

**Expected Results:**
- `sideEffects1` should contain a `ScheduleCreated` side effect for "queue-1"
- `sideEffects2` should be empty (idempotent - schedule already exists)
- `sideEffects3` should contain a `ScheduleAborted` side effect for "queue-1"

**Additional Requirements:**
- All events going into the engine should be logged
- All resulting side effects should be logged
- Multiple workQueueIds should be handled independently
- Schedules can be reactivated after being aborted

**Verification:**

| Step | Action | Expected Result |
|------|--------|-----------------|
| 1 | Register `WorkQueueProcessor` with engine | Processor registered successfully, logged |
| 2 | Process `WorkQueueMessage("queue-1", ACTIVE)` | Returns `[ScheduleCreated]`, event and side effect logged |
| 3 | Process `WorkQueueMessage("queue-1", ACTIVE)` (duplicate) | Empty side effects list (idempotent), event logged |
| 4 | Process `WorkQueueMessage("queue-1", INACTIVE)` | Returns `[ScheduleAborted]`, event and side effect logged |

**Test Execution:**
```bash
# Run tests
mvn test -Dtest=WorkQueueProcessorTest
```

**Expected Output:**
```
INFO: Processing event: WorkQueueMessage[workQueueId=queue-1, status=ACTIVE]
INFO: Side effect: ScheduleCreated[workQueueId=queue-1]

INFO: Processing event: WorkQueueMessage[workQueueId=queue-1, status=ACTIVE]
INFO: No side effects produced

INFO: Processing event: WorkQueueMessage[workQueueId=queue-1, status=INACTIVE]
INFO: Side effect: ScheduleAborted[workQueueId=queue-1]
```

**Implementation Files:**
- `src/main/java/com/wonderingwizard/events/WorkQueueMessage.java`
- `src/main/java/com/wonderingwizard/events/WorkQueueStatus.java`
- `src/main/java/com/wonderingwizard/events/LoadMode.java`
- `src/main/java/com/wonderingwizard/sideeffects/ScheduleCreated.java`
- `src/main/java/com/wonderingwizard/sideeffects/ScheduleAborted.java`
- `src/main/java/com/wonderingwizard/processors/WorkQueueProcessor.java`

---

### F-3: Step Back (Undo)

**Status:** Implemented

**Description:**
Implement step-back functionality using the Memento pattern to allow reverting the engine to the state before the last processed event. This enables undo capability across all registered processors.

1. Each processor must support state capture and restoration
2. The engine captures state snapshots before processing each event
3. Calling `stepBack()` restores all processors to their previous state
4. Multiple step-backs are supported (full history)

**Requested Behavior:**

```java
engine.register(new TimeAlarmProcessor());
engine.register(new WorkQueueProcessor());

var effects1 = engine.processEvent(new SetTimeAlarm("alarm1", triggerTime));
var effects2 = engine.processEvent(new SetTimeAlarm("alarm2", triggerTime));

// Oops, didn't want alarm2
boolean success = engine.stepBack();  // Reverts alarm2 creation

// Check history depth
int available = engine.getHistorySize();  // Returns 1

// Trigger time - only alarm1 fires
var effects3 = engine.processEvent(new TimeEvent(afterTriggerTime));
```

**Expected Results:**
- `effects1` should contain `AlarmSet` for "alarm1"
- `effects2` should contain `AlarmSet` for "alarm2"
- `success` should be `true` (step back succeeded)
- `available` should be `1` (one more undo available)
- `effects3` should contain `AlarmTriggered` only for "alarm1" (alarm2 was reverted)

**Additional Requirements:**
- State snapshots are captured automatically before each `processEvent()` call
- `stepBack()` returns `false` when no history is available
- `clearHistory()` frees memory by removing all snapshots
- Processors must implement `captureState()` and `restoreState(Object state)`
- State objects are opaque and only used for restoration

**Verification:**

| Step | Action | Expected Result |
|------|--------|-----------------|
| 1 | Process `SetTimeAlarm("alarm1", time)` | Returns `[AlarmSet]`, history size = 1 |
| 2 | Process `SetTimeAlarm("alarm2", time)` | Returns `[AlarmSet]`, history size = 2 |
| 3 | Call `stepBack()` | Returns `true`, history size = 1 |
| 4 | Process `TimeEvent(afterTime)` | Returns `[AlarmTriggered]` for alarm1 only |
| 5 | Call `stepBack()` twice | First returns `true`, second returns `false` |

**Test Execution:**
```bash
# Run tests
mvn test -Dtest=EventProcessingEngineStepBackTest
```

**Expected Output:**
```
INFO: Processing event: SetTimeAlarm[alarmName=alarm1, triggerTime=...]
INFO: Side effect: AlarmSet[alarmName=alarm1, triggerTime=...]

INFO: Processing event: SetTimeAlarm[alarmName=alarm2, triggerTime=...]
INFO: Side effect: AlarmSet[alarmName=alarm2, triggerTime=...]

INFO: Stepped back to previous state

INFO: Processing event: TimeEvent[timestamp=...]
INFO: Side effect: AlarmTriggered[alarmName=alarm1, triggeredAt=...]
```

**API Reference:**

| Method | Description |
|--------|-------------|
| `stepBack()` | Reverts to state before last event. Returns `true` on success, `false` if no history. |
| `getHistorySize()` | Returns number of available undo steps. |
| `clearHistory()` | Clears all snapshots to free memory. |
| `captureState()` | (EventProcessor) Returns opaque state snapshot. |
| `restoreState(Object)` | (EventProcessor) Restores processor to captured state. |

**Implementation Files:**
- `src/main/java/com/wonderingwizard/engine/EventProcessor.java` (interface changes)
- `src/main/java/com/wonderingwizard/engine/EventProcessingEngine.java` (history and stepBack)
- `src/main/java/com/wonderingwizard/processors/TimeAlarmProcessor.java` (state methods)
- `src/main/java/com/wonderingwizard/processors/WorkQueueProcessor.java` (state methods)
- `src/test/java/com/wonderingwizard/engine/EventProcessingEngineStepBackTest.java` (tests)

---

### F-4: Work Instruction Event

**Status:** Implemented

**Description:**
Extend the WorkQueueProcessor to handle work instruction events that are associated with work queues. When a work queue is activated, the resulting `ScheduleCreated` side effect should include all work instructions registered for that work queue.

1. Accept `WorkInstructionEvent` events with `workInstructionId`, `workQueueId`, `fetchChe`, and `status` (using `WorkInstructionStatus` enum)
2. Store work instructions associated with each workQueueId
3. When a `WorkQueueMessage` with status `ACTIVE` is processed, include matching work instructions in the `ScheduleCreated` side effect

**Requested Behavior:**

```java
import static com.wonderingwizard.events.WorkQueueStatus.ACTIVE;
import static com.wonderingwizard.events.WorkInstructionStatus.PENDING;

engine.register(new WorkQueueProcessor());

// Register work instructions before activating the queue
var effects1 = engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING));
var effects2 = engine.processEvent(new WorkInstructionEvent("wi-2", "queue-1", "CHE-002", PENDING));
var effects3 = engine.processEvent(new WorkInstructionEvent("wi-3", "queue-2", "CHE-003", PENDING));

// Activate work queue - should include associated work instructions
var effects4 = engine.processEvent(new WorkQueueMessage("queue-1", ACTIVE));
```

**Expected Results:**
- `effects1`, `effects2`, `effects3` should be empty (work instructions are registered but don't produce side effects)
- `effects4` should contain a `ScheduleCreated` side effect for "queue-1" with a list containing work instructions "wi-1" and "wi-2" (not "wi-3" which belongs to "queue-2")

**Additional Requirements:**
- Work instructions are stored internally until the associated work queue is activated
- Work instructions for a work queue are included in the `ScheduleCreated` side effect
- The `ScheduleCreated` record is extended to include a `List<WorkInstruction>` field
- Work instructions registered after a schedule is already active should be retrievable on reactivation
- When a schedule is aborted (INACTIVE), work instructions remain stored for potential reactivation
- When a `WorkInstructionEvent` with an existing `workInstructionId` is processed with a different `workQueueId`, the instruction is moved to the new queue
- When a `WorkInstructionEvent` with an existing `workInstructionId` is processed with the same `workQueueId`, the instruction is updated (fetchChe, status)

**Verification:**

| Step | Action | Expected Result |
|------|--------|-----------------|
| 1 | Register `WorkQueueProcessor` with engine | Processor registered successfully, logged |
| 2 | Process `WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING)` | Empty side effects, work instruction stored |
| 3 | Process `WorkInstructionEvent("wi-2", "queue-1", "CHE-002", PENDING)` | Empty side effects, work instruction stored |
| 4 | Process `WorkQueueMessage("queue-1", ACTIVE)` | Returns `[ScheduleCreated]` with workInstructions list containing wi-1, wi-2 |
| 5 | Process `WorkQueueMessage("queue-1", INACTIVE)` | Returns `[ScheduleAborted]`, work instructions still stored |
| 6 | Process `WorkInstructionEvent("wi-4", "queue-1", "CHE-004", PENDING)` | Empty side effects, new instruction stored |
| 7 | Process `WorkQueueMessage("queue-1", ACTIVE)` | Returns `[ScheduleCreated]` with workInstructions containing wi-1, wi-2, wi-4 |

**Test Execution:**
```bash
# Run tests
mvn test -Dtest=WorkQueueProcessorTest
```

**Expected Output:**
```
INFO: Processing event: WorkInstructionEvent[workInstructionId=wi-1, workQueueId=queue-1, fetchChe=CHE-001, status=PENDING]
INFO: No side effects produced

INFO: Processing event: WorkInstructionEvent[workInstructionId=wi-2, workQueueId=queue-1, fetchChe=CHE-002, status=PENDING]
INFO: No side effects produced

INFO: Processing event: WorkQueueMessage[workQueueId=queue-1, status=ACTIVE]
INFO: Side effect: ScheduleCreated[workQueueId=queue-1, workInstructions=[WorkInstruction[...], WorkInstruction[...]]]
```

**Implementation Files:**
- `src/main/java/com/wonderingwizard/events/WorkInstructionEvent.java`
- `src/main/java/com/wonderingwizard/events/WorkInstructionStatus.java`
- `src/main/java/com/wonderingwizard/sideeffects/WorkInstruction.java`
- `src/main/java/com/wonderingwizard/sideeffects/ScheduleCreated.java` (modified)
- `src/main/java/com/wonderingwizard/processors/WorkQueueProcessor.java` (modified)
- `src/test/java/com/wonderingwizard/processors/WorkQueueProcessorTest.java` (modified)

---

### F-5: Takt Generation

**Status:** Implemented

**Description:**
Extend the WorkQueueProcessor to generate Takts from work instructions when a schedule is created. Each work instruction generates one Takt containing a sequence of actions. Takts are named sequentially starting from TAKT100.

1. Each work instruction generates exactly one Takt
2. Takts are named sequentially: TAKT100, TAKT101, TAKT102, etc.
3. Each Takt contains two actions: "QC lift container from truck" and "QC place container on vessel"
4. Actions have unique UUIDs and dependency relationships

**Requested Behavior:**

```java
import static com.wonderingwizard.events.WorkQueueStatus.ACTIVE;
import static com.wonderingwizard.events.WorkInstructionStatus.PENDING;

engine.register(new WorkQueueProcessor());

// Register 2 work instructions
engine.processEvent(new WorkInstructionEvent("wi-1", "queue-1", "CHE-001", PENDING, null));
engine.processEvent(new WorkInstructionEvent("wi-2", "queue-1", "CHE-002", PENDING, null));

// Activate - generates 2 takts
var effects = engine.processEvent(new WorkQueueMessage("queue-1", ACTIVE));
ScheduleCreated created = (ScheduleCreated) effects.get(0);
```

**Expected Results:**
- `created.takts()` contains 2 Takts
- First takt named "TAKT100" with 2 actions
- Second takt named "TAKT101" with 2 actions
- Each action has a UUID and dependencies on predecessor actions

**Verification:**

| Step | Action | Expected Result |
|------|--------|-----------------|
| 1 | Register 2 work instructions for queue-1 | Empty side effects |
| 2 | Activate queue-1 | `ScheduleCreated` with 2 takts |
| 3 | Check takt names | "TAKT100", "TAKT101" |
| 4 | Check actions per takt | 2 actions each |
| 5 | Check action descriptions | "QC lift container from truck", "QC place container on vessel" |

**Implementation Files:**
- `src/main/java/com/wonderingwizard/domain/takt/Takt.java`
- `src/main/java/com/wonderingwizard/domain/takt/Action.java`
- `src/main/java/com/wonderingwizard/processors/WorkQueueProcessor.java` (modified)
- `src/main/java/com/wonderingwizard/sideeffects/ScheduleCreated.java` (modified)

---

### F-6: Schedule Runner

**Status:** Implemented

**Description:**
Implement a ScheduleRunnerProcessor that executes schedules by activating and completing actions based on time and dependencies. Actions are activated when their dependencies are satisfied and completed via ActionCompletedEvent.

1. Actions have a `dependsOn` set of action UUIDs that must be completed before activation
2. Actions with no dependencies are activated when `TimeEvent.timestamp >= estimatedMoveTime`
3. When an action completes, any actions whose dependencies are now all satisfied are activated
4. Multiple actions can be active simultaneously if their dependencies allow
5. ActionCompletedEvent must match an active action's UUID to be processed

**Key Concepts:**

- **Dependency-based activation**: An action only activates when ALL actions it depends on are completed
- **Parallel execution**: Multiple independent actions can be active at the same time
- **UUID validation**: ActionCompletedEvent must match an active action's UUID

**Requested Behavior:**

```java
engine.register(new ScheduleRunnerProcessor());

// Initialize schedule with takts and estimatedMoveTime
Instant estimatedMoveTime = Instant.parse("2024-01-01T10:00:00Z");
List<Takt> takts = ...; // Takts with linked actions
processor.initializeSchedule("queue-1", takts, estimatedMoveTime);

// TimeEvent triggers first action (no dependencies)
var effects1 = engine.processEvent(new TimeEvent(Instant.parse("2024-01-01T10:00:01Z")));
// effects1 = [ActionActivated("QC lift container from truck")]

// Complete action triggers next
UUID firstActionId = ...;
var effects2 = engine.processEvent(new ActionCompletedEvent(firstActionId, "queue-1"));
// effects2 = [ActionCompleted, ActionActivated("QC place container on vessel")]
```

**Events:**

| Event | Description |
|-------|-------------|
| `TimeEvent` | Triggers action activation when `timestamp >= estimatedMoveTime` |
| `ActionCompletedEvent(actionId, workQueueId)` | Marks action as completed, triggers dependent actions |
| `WorkQueueMessage(workQueueId, INACTIVE)` | Deactivates schedule, stops processing |

**Side Effects:**

| Side Effect | Description |
|-------------|-------------|
| `ActionActivated(actionId, workQueueId, taktName, description, activatedAt)` | Action has started |
| `ActionCompleted(actionId, workQueueId, taktName, description, completedAt)` | Action has finished |

**Dependency Model:**

```
Sequential workflow (default):
  TAKT100.action1 (no deps)
      ↓
  TAKT100.action2 (depends on action1)
      ↓
  TAKT101.action1 (depends on TAKT100.action2)
      ↓
  TAKT101.action2 (depends on TAKT101.action1)

Parallel workflow (custom):
  action1 (no deps) ──┬──> action3 (depends on 1 & 2)
  action2 (no deps) ──┘
```

**Verification:**

| Step | Action | Expected Result |
|------|--------|-----------------|
| 1 | Initialize schedule with estimatedMoveTime | Schedule registered |
| 2 | Process TimeEvent before estimatedMoveTime | Empty side effects |
| 3 | Process TimeEvent at/after estimatedMoveTime | `[ActionActivated]` for first action |
| 4 | Process ActionCompletedEvent with correct UUID | `[ActionCompleted, ActionActivated]` for next action |
| 5 | Process ActionCompletedEvent with wrong UUID | Empty side effects (ignored) |
| 6 | Complete all actions | Final `[ActionCompleted]` with no activation |
| 7 | Deactivate schedule | Subsequent events ignored |

**Test Execution:**
```bash
# Run tests
mvn test -Dtest=ScheduleRunnerProcessorTest
```

**Multiple Dependencies Test:**
```java
// Given: action3 depends on BOTH action1 AND action2
Action action1 = Action.create("Action 1");  // no deps
Action action2 = Action.create("Action 2");  // no deps
Action action3 = new Action(UUID.randomUUID(), "Action 3", Set.of(action1.id(), action2.id()));

// TimeEvent activates action1 and action2 (both have no deps)
// Complete action1 -> action3 NOT activated yet
// Complete action2 -> action3 activated (all deps satisfied)
```

**Implementation Files:**
- `src/main/java/com/wonderingwizard/domain/takt/Action.java` (modified - added dependsOn)
- `src/main/java/com/wonderingwizard/events/ActionCompletedEvent.java`
- `src/main/java/com/wonderingwizard/sideeffects/ActionActivated.java`
- `src/main/java/com/wonderingwizard/sideeffects/ActionCompleted.java`
- `src/main/java/com/wonderingwizard/processors/ScheduleRunnerProcessor.java`
- `src/main/java/com/wonderingwizard/engine/Event.java` (modified - permits ActionCompletedEvent)
- `src/main/java/com/wonderingwizard/engine/SideEffect.java` (modified - permits ActionActivated, ActionCompleted)
- `src/test/java/com/wonderingwizard/processors/ScheduleRunnerProcessorTest.java`

---

### F-7: Schedule Viewer

**Status:** Implemented

**Description:**
Provide a web-based schedule viewer that displays the full schedule in a browser. The viewer connects to an embedded HTTP server (JDK `HttpServer`, zero external dependencies) that wraps the event processing engine and exposes its state as JSON.

1. An embedded HTTP server serves a single-page web application and a REST API
2. The browser displays all takts and their actions, grouped by work queue
3. Each action shows its current status: pending, active, or completed
4. Actions are color-coded by device type (RTG, TT, QC)
5. The schedule view refreshes after every user interaction

**REST API:**

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/` | Serve the schedule viewer (single `index.html`) |
| `GET` | `/api/state` | Return full engine state as JSON: current time, event history, schedules with action statuses, side effects |
| `POST` | `/api/work-instruction` | Send a `WorkInstructionEvent` |
| `POST` | `/api/work-queue` | Send a `WorkQueueMessage` (ACTIVE / INACTIVE) |
| `POST` | `/api/tick` | Advance simulated clock by N seconds, send `TimeEvent` |
| `POST` | `/api/action-completed` | Send an `ActionCompletedEvent` |
| `POST` | `/api/step-back-to` | Revert engine to a target step using `stepBack()` |

**Schedule View Layout:**
- **Header** — simulated clock display with tick buttons (+1 min, +5 min, +15 min)
- **Left panel** — event input forms (add work instruction, activate/deactivate work queue)
- **Center panel** — schedule visualization: takts as rows, actions as color-coded cards showing pending / active / completed status
- **Right panel** — event timeline with clickable entries for time travel
- **Bottom strip** — live side effects log

**Verification:**

| Step | Action | Expected Result |
|------|--------|-----------------|
| 1 | Start the demo server | HTTP server listening, browser loads schedule viewer |
| 2 | Add two work instructions via the UI | Events sent, no side effects, state updated |
| 3 | Activate the work queue | Schedule appears with takts and actions, all pending |
| 4 | Tick time past `estimatedMoveTime` | Root actions (no dependencies) change to active |
| 5 | Click timeline entry to revert | Engine steps back, schedule returns to earlier state |

**Frontend Requirements:**
- Single `index.html` file
- Web Components (extend `HTMLElement`), vanilla JS, no frameworks or external libraries
- Actions color-coded by device type: RTG blue, TT amber, QC green

---

### F-8: Complete and Restart Actions via Browser

**Status:** Partially implemented (Complete via browser is implemented; Restart uses timeline step-back)

**Description:**
The schedule viewer allows the user to complete or restart active actions directly from the browser. Completing an action sends an `ActionCompletedEvent` to the engine, which marks the action as completed and activates any dependent actions whose dependencies are now satisfied. Restarting an action reverts the engine to the state before that action was completed using `stepBack()`.

1. Active actions display a "Complete" button in the schedule viewer
2. Clicking "Complete" sends an `ActionCompletedEvent(actionId, workQueueId)` to the engine via the REST API
3. The engine produces `ActionCompleted` and `ActionActivated` side effects as per F-6
4. The schedule view updates to reflect the new action statuses
5. Completed actions display a "Restart" option that reverts the engine to the state before that completion

**Requested Behavior:**

```
User sees schedule:
  TAKT100
    RTG: lift container from yard     [ACTIVE]  [Complete]
    RTG: place container on truck     [PENDING]

User clicks "Complete" on the first action:
  → POST /api/action-completed { actionId, workQueueId }
  → Engine produces ActionCompleted + ActionActivated (next action)

Schedule updates:
  TAKT100
    RTG: lift container from yard     [COMPLETED]
    RTG: place container on truck     [ACTIVE]  [Complete]
```

**Verification:**

| Step | Action | Expected Result |
|------|--------|-----------------|
| 1 | Schedule is running, root actions are active | Active actions show "Complete" button |
| 2 | Click "Complete" on an active action | Action moves to completed, dependent actions activate |
| 3 | Complete all actions in a dependency chain | Each completion triggers the next activation |
| 4 | Click a past event in the timeline to revert | Completed action returns to active, dependent actions return to pending |

---

### F-9: Graph-Based Schedule Builder

**Status:** Implemented

**Description:**
Alternative takt generation algorithm using a declarative graph-based approach. Replaces the imperative per-resource methods (`createTaktsForWorinstructionQc`, `TT`, `RTG`) with a single generic placement algorithm driven by a declarative action blueprint.

1. Each container's workflow is defined as a flat list of `ActionTemplate` declarations
2. Templates declare constraints: `firstInTakt`, `anchor`, `syncWith`, `onlyOnePerTakt`
3. One anchor action pins the QC takt to `containerIndex`
4. Cross-resource synchronization uses `syncWith` (e.g., TT "handover to QC" syncs with QC "QC Lift")
5. The algorithm places segments into takts, then wires dependencies as a post-processing step
6. Enabled via a feature flag (`useGraphScheduleBuilder`) on `WorkQueueProcessor`
7. Template selection based on `LoadMode`: `DSCH` uses discharge twin template (`getDischargeTwinTemplate`), `LOAD` uses load single template (`getLoadSingleTemplate`)

**Key Concepts:**

- **ActionTemplate**: Declarative action definition with name, device type, duration, and placement constraints
- **Segment**: Group of consecutive actions (within one resource) that belong to the same takt
- **Anchor**: Exactly one action per container that pins to takt index = containerIndex
- **SyncWith**: Cross-resource constraint — places this segment in the same takt as the referenced action
- **Blueprint**: Full action list for one container, built dynamically from work instruction parameters

**Algorithm:**

1. Build blueprint per container (durations from work instruction)
2. Split per-device chains into segments at `firstInTakt` boundaries
3. Place anchor segment at `containerIndex`
4. Resolve sync-based segments (place in same takt as sync target)
5. Place backward adjacent segments with overflow handling
6. Wire intra-chain and cross-container dependencies in blueprint execution order

**Feature Flag:**

```java
// Legacy (default)
new WorkQueueProcessor(driveTimeSupplier, qcOffsetSupplier, false);

// Graph-based
new WorkQueueProcessor(driveTimeSupplier, qcOffsetSupplier, true);
```

**Verification:**

| Step | Action | Expected Result |
|------|--------|-----------------|
| 1 | Create GraphScheduleBuilder with fixed drive time | Builder created |
| 2 | Build blueprint for single work instruction | Blueprint contains QC, TT, RTG actions with correct durations |
| 3 | Create takts for single container | QC in anchor takt, TT/RTG in pulse takts, all syncs resolved |
| 4 | Create takts for multiple containers | Cross-container dependencies wired, anchors at container indices |
| 5 | Enable feature flag and activate work queue | ScheduleCreated uses graph builder output |

**Implementation Files:**
- `src/main/java/com/wonderingwizard/processors/GraphScheduleBuilder.java` (new)
- `src/main/java/com/wonderingwizard/processors/WorkQueueProcessor.java` (modified — feature flag)
- `src/test/java/com/wonderingwizard/processors/GraphScheduleBuilderTest.java` (new)

---

### F-10: Delay Processor

**Status:** Implemented

**Description:**
Implement a DelayProcessor that calculates and tracks schedule delays based on takt execution times. The processor monitors all active schedules and detects when takts overrun their planned duration, propagating delay information to the schedule view.

1. On each `TimeEvent`, check all active schedules for delays
2. A takt is delayed when it has been active longer than its planned duration (`plannedStartTime + durationSeconds < currentTime`)
3. The total delay propagates to future takts by shifting their estimated start times forward
4. Total delay can decrease if subsequent takts complete faster than their planned duration
5. Per-takt delay information (start delay, execution delay) is displayed in the webview

**Delay Definitions:**

- **Start delay:** How late a takt started relative to its planned start time. Computed as `actualStartTime - plannedStartTime`.
- **Takt delay (execution delay):** How much longer a takt took (or is taking) beyond its planned duration. For active takts: `max(0, currentTime - actualStartTime - durationSeconds)`. For completed takts: `max(0, completedAt - actualStartTime - durationSeconds)`.
- **Total delay:** The cumulative delay of the schedule. For the active takt: `max(0, currentTime - (plannedStartTime + durationSeconds))`. Decreases when takts complete faster than their planned duration.

**Requested Behavior:**

```
Example scenario:
- TAKT100 starts on time (0s start delay), duration = 120s
- TAKT100 runs for 150s → 30s takt delay, 30s total delay
- TAKT101 starts 30s late (30s start delay)
- TAKT101 finishes in 120s from its actual start → 0s takt delay
- Total delay remains 30s (unless future takts make up time)
```

**Events:**

| Event | Description |
|-------|-------------|
| `TimeEvent` | Triggers delay recalculation for all active schedules |
| `TaktActivated` | Records actual start time for delay tracking |
| `TaktCompleted` | Records completion time for delay tracking |
| `ScheduleCreated` | Initializes delay tracking for a new schedule |
| `WorkQueueMessage(INACTIVE)` | Removes delay tracking for deactivated schedule |

**Side Effects:**

| Side Effect | Description |
|-------------|-------------|
| `DelayUpdated(workQueueId, totalDelaySeconds)` | Emitted when the total delay changes for a schedule |

**Webview Display:**

- Total delay badge at the top of each schedule (red "DELAY: +Xs" or green "ON TIME")
- Per-takt delay indicators: start delay and execution delay shown in takt headers
- Start delay shown in red if > 0, green if 0
- Execution delay shown for COMPLETED takts; "..." shown for ACTIVE takts

**Verification:**

| Step | Action | Expected Result |
|------|--------|-----------------|
| 1 | Create schedule with 2 takts (120s duration each) | DelayProcessor initializes tracking |
| 2 | Activate TAKT100 on time | No delay emitted |
| 3 | TimeEvent at 150s (30s past planned end) | `DelayUpdated(queue, 30)` emitted |
| 4 | Complete TAKT100 at 150s, activate TAKT101 at 150s | Delay remains 30s based on completed takt |
| 5 | Complete TAKT101 at 270s (120s from start) | Delay 30s (takt completed on time relative to its duration but schedule is 30s behind) |
| 6 | Deactivate schedule | Delay tracking removed |

**Test Execution:**
```bash
# Run tests
mvn test -Dtest=DelayProcessorTest
```

**Implementation Files:**
- `src/main/java/com/wonderingwizard/sideeffects/DelayUpdated.java`
- `src/main/java/com/wonderingwizard/processors/DelayProcessor.java`
- `src/main/java/com/wonderingwizard/sideeffects/TaktActivated.java` (modified — also implements Event)
- `src/main/java/com/wonderingwizard/sideeffects/TaktCompleted.java` (modified — also implements Event)
- `src/main/java/com/wonderingwizard/engine/SideEffect.java` (modified — permits DelayUpdated)
- `src/main/java/com/wonderingwizard/server/DemoServer.java` (modified — delay display in ScheduleView/TaktView)
- `src/main/java/com/wonderingwizard/server/JsonSerializer.java` (modified — serialize delay fields)
- `src/main/resources/index.html` (modified — delay badges and per-takt delay display)
- `src/test/java/com/wonderingwizard/processors/DelayProcessorTest.java`

---

### F-11: Kafka Event Consumer Infrastructure

**Status:** Implemented

**Description:**
Introduce Kafka as an event source for the engine. The architecture provides a generic consumer infrastructure that makes it easy to add consumers for any Kafka topic. Each message type has its own topic and mapper. The first implemented consumer is for the WorkQueue topic.

1. Generic `KafkaEventConsumer` that polls a Kafka topic, deserializes Avro messages, maps them to engine events, and feeds them into the engine
2. `KafkaConsumerManager` that manages the lifecycle (start/stop) of all registered consumers
3. `WorkQueueEventMapper` that maps the WorkQueue Avro schema to the existing `WorkQueueMessage` engine event
4. Configuration records (`KafkaConfiguration`, `ConsumerConfiguration`) for connection and per-topic settings
5. Consumers run on virtual threads for efficient resource usage

**Kafka WorkQueue Topic:**

| Setting | Value |
|---------|-------|
| Topic | `APMT.terminalOperations.workQueue.topic.confidential.dedicated.v1` |
| Group ID | `apmt.tc1.sit.terminal-operations.flow-tone-work-queue.consumergroup.v1` |
| Avro Message | `APMT.terminalOperations.workQueue.topic.confidential.dedicated.v1.WorkQueue` |

**Architecture:**

```
Kafka Topic ──► KafkaEventConsumer (virtual thread)
                    │
                    ├── Avro Deserializer (Confluent Schema Registry)
                    │
                    ├── EventMapper<E> (e.g., WorkQueueEventMapper)
                    │       │
                    │       └── GenericRecord → WorkQueueMessage (engine event)
                    │
                    └── Engine.processEvent(event)
```

**Adding a New Topic Consumer:**

```java
// 1. Create a mapper implementing EventMapper<YourEvent>
public class YourEventMapper implements EventMapper<YourEvent> {
    @Override
    public YourEvent map(GenericRecord record) {
        // Extract fields from Avro record and return engine event
    }
}

// 2. Register with KafkaConsumerManager
manager.register(
    new ConsumerConfiguration("your.topic.v1", "your-group-v1", "YourAvroType", null, false),
    new YourEventMapper()
);
```

**Requested Behavior:**

```java
// Create Kafka configuration
var kafkaConfig = new KafkaConfiguration(
    "kafka-broker-service:9094", "fes",
    "http://kafka-schema-registry-service:8081",
    "Plain", "admin", "admin-secret", "SaslPlaintext"
);

// Create consumer manager
var manager = new KafkaConsumerManager(kafkaConfig, engine);

// Register WorkQueue consumer
var workQueueConfig = new ConsumerConfiguration(
    "APMT.terminalOperations.workQueue.topic.confidential.dedicated.v1",
    "apmt.tc1.sit.terminal-operations.flow-tone-work-queue.consumergroup.v1",
    "APMT.terminalOperations.workQueue.topic.confidential.dedicated.v1.WorkQueue",
    null, false
);
manager.register(workQueueConfig, new WorkQueueEventMapper());

// Start all consumers
manager.startAll();
```

**Status Mapping (Kafka → Engine):**

| Kafka Status | Engine Status |
|-------------|---------------|
| ACTIVE | ACTIVE |
| WORKING | ACTIVE |
| CREATED | ACTIVE |
| INACTIVE | INACTIVE |
| COMPLETE | INACTIVE |
| CANCELLED | INACTIVE |
| DELETED | INACTIVE |

**Verification:**

| Step | Action | Expected Result |
|------|--------|-----------------|
| 1 | Create KafkaConfiguration with broker details | Configuration created |
| 2 | Create ConsumerConfiguration for WorkQueue topic | Configuration with topic, group, Avro type |
| 3 | Register WorkQueueEventMapper with consumer manager | Consumer registered |
| 4 | Map an Avro GenericRecord with ACTIVE status | Returns WorkQueueMessage(id, ACTIVE, mudaSeconds) |
| 5 | Map an Avro GenericRecord with COMPLETE status | Returns WorkQueueMessage(id, INACTIVE, 0) |
| 6 | Map an Avro GenericRecord with null muda time | Returns WorkQueueMessage with qcMudaSeconds=0 |
| 7 | Consumer properties include SASL config | Properties contain security.protocol, sasl.mechanism |

**Test Execution:**
```bash
# Run tests
mvn test -Dtest="WorkQueueEventMapperTest,KafkaEventConsumerTest,ConsumerConfigurationTest,KafkaConsumerManagerTest"
```

**Implementation Files:**
- `src/main/java/com/wonderingwizard/kafka/KafkaConfiguration.java`
- `src/main/java/com/wonderingwizard/kafka/ConsumerConfiguration.java`
- `src/main/java/com/wonderingwizard/kafka/EventMapper.java`
- `src/main/java/com/wonderingwizard/kafka/KafkaEventConsumer.java`
- `src/main/java/com/wonderingwizard/kafka/KafkaConsumerManager.java`
- `src/main/java/com/wonderingwizard/kafka/WorkQueueEventMapper.java`
- `src/main/java/com/wonderingwizard/kafka/messages/WorkQueueKafkaMessage.java`
- `src/test/java/com/wonderingwizard/kafka/WorkQueueEventMapperTest.java`
- `src/test/java/com/wonderingwizard/kafka/KafkaEventConsumerTest.java`
- `src/test/java/com/wonderingwizard/kafka/ConsumerConfigurationTest.java`
- `src/test/java/com/wonderingwizard/kafka/KafkaConsumerManagerTest.java`
- `pom.xml` (modified — added Kafka, Avro, Confluent dependencies)

---

### F-12: Event Log Export/Import

**Status:** Implemented

**Description:**
Implement an EventLogProcessor that records every event passing through the system. The web viewer provides Export and Import buttons that allow users to download the full event log as a JSON file and later import it to restore the system to that exact state.

1. An `EventLogProcessor` is registered with the engine and records every event in order
2. A `GET /api/event-log/export` endpoint returns all user-initiated steps as a downloadable JSON file
3. A `POST /api/event-log/import` endpoint accepts an exported JSON file, resets the engine, and replays all events to restore state
4. The web viewer header has Export and Import buttons
5. Export downloads an `event-log.json` file via the browser
6. Import opens a file picker, reads the selected JSON file, and sends it to the import endpoint

**Export Format:**

```json
[
  {"description":"WI 1","event":{"type":"WorkInstructionEvent","workInstructionId":1,...}},
  {"description":"Activate WQ","event":{"type":"WorkQueueMessage","workQueueId":1,"status":"ACTIVE",...}},
  {"description":"Tick +60s","event":{"type":"TimeEvent","timestamp":"2024-01-01T00:06:00Z"}}
]
```

**Import Behavior:**
1. Reset engine to initial state (step back to 0, clear history)
2. For each entry in the JSON array, reconstruct the event from the `event` sub-object
3. Replay each event through `processStep()` with the original description
4. Broadcast updated state via SSE to all connected clients

**REST API:**

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/event-log/export` | Download event log as JSON file |
| `POST` | `/api/event-log/import` | Import event log JSON, reset and replay |

**Verification:**

| Step | Action | Expected Result |
|------|--------|-----------------|
| 1 | Register EventLogProcessor with engine | Processor records all events, produces no side effects |
| 2 | Process several events | Event log grows with each event |
| 3 | Export via GET /api/event-log/export | JSON array of steps with descriptions and events |
| 4 | Import the exported JSON into a fresh server | All steps replayed, schedules and state restored |
| 5 | Click Export button in web viewer | Browser downloads event-log.json |
| 6 | Click Import button and select file | System resets and replays events from file |

**Test Execution:**
```bash
# Run tests
mvn test -Dtest="EventLogProcessorTest,EventDeserializerTest,EventLogExportImportTest"
```

**Implementation Files:**
- `src/main/java/com/wonderingwizard/processors/EventLogProcessor.java`
- `src/main/java/com/wonderingwizard/server/EventDeserializer.java`
- `src/main/java/com/wonderingwizard/server/DemoServer.java` (modified — export/import endpoints)
- `src/main/java/com/wonderingwizard/server/JsonSerializer.java` (modified — full WorkInstructionEvent and WorkQueueMessage serialization)
- `src/main/resources/index.html` (modified — Export and Import buttons)
- `src/test/java/com/wonderingwizard/processors/EventLogProcessorTest.java`
- `src/test/java/com/wonderingwizard/server/EventDeserializerTest.java`
- `src/test/java/com/wonderingwizard/server/EventLogExportImportTest.java`

### F-13: Kafka Side Effect Publisher (EquipmentInstruction)

**Status:** Implemented

**Description:**
Implement a Kafka side effect publisher that maps engine side effects to Avro records and publishes them to Kafka topics. This is the outbound counterpart to the Kafka event consumer infrastructure (F-11). The first use case is publishing `EquipmentInstruction` messages when actions are activated.

1. `ActionActivated` side effect is enriched with `deviceType` and `workInstructions` from the originating `Action` record
2. A `SideEffectMapper<S>` functional interface maps a `SideEffect` to an Avro `GenericRecord` (symmetric to `EventMapper`)
3. A `KafkaSideEffectPublisher` manages registered mappers and publishes side effects to configured topics (symmetric to `KafkaEventConsumer`)
4. An `ActionActivatedToEquipmentInstructionMapper` maps `ActionActivated` → EquipmentInstruction Avro record, parameterized with a `Set<ActionType>` to filter which action types to publish
5. The publisher is called after `engine.processEvent()` returns, keeping the engine pure (no I/O inside processors)
6. Three mapper instances are registered — one for RTG actions (→ RTG topic), one for TT actions (→ TT topic), and one for QC actions (→ QC topic)

**Architecture:**

```
Engine.processEvent(event) → List<SideEffect>
        │
        ▼
KafkaSideEffectPublisher.publish(sideEffects)
        │
        ├── ActionActivated (RTG_*) → Mapper(RTG types) → Kafka (RTG EquipmentInstruction topic)
        ├── ActionActivated (TT_*) → Mapper(TT types) → Kafka (TT EquipmentInstruction topic)
        ├── ActionActivated (QC_*) → Mapper(QC types) → Kafka (QC EquipmentInstruction topic)
        ├── OtherSideEffect? → (future mapper) → Kafka (other topic)
        └── No mapper match → skip
```

**Verification:**

| Step | Action | Expected Result |
|------|--------|-----------------|
| 1 | Activate an action with work instructions | ActionActivated contains deviceType and workInstructions |
| 2 | Map ActionActivated with work instructions | EquipmentInstruction Avro record with all fields populated |
| 3 | Map ActionActivated without work instructions | Returns null (skipped) |
| 4 | Register mapper and publish side effects | Only matching side effect types are dispatched to their mapper |

**Test Execution:**
```bash
mvn test -Dtest="ActionActivatedToEquipmentInstructionMapperTest,KafkaSideEffectPublisherTest"
```

**Implementation Files:**
- `src/main/java/com/wonderingwizard/sideeffects/ActionActivated.java` (modified — added deviceType and workInstructions)
- `src/main/java/com/wonderingwizard/processors/ScheduleRunnerProcessor.java` (modified — populates enriched ActionActivated)
- `src/main/java/com/wonderingwizard/kafka/SideEffectMapper.java` (new — functional interface)
- `src/main/java/com/wonderingwizard/kafka/ProducerConfiguration.java` (new — per-topic producer config)
- `src/main/java/com/wonderingwizard/kafka/KafkaSideEffectPublisher.java` (new — generic publisher)
- `src/main/java/com/wonderingwizard/kafka/ActionActivatedToEquipmentInstructionMapper.java` (new — Avro mapper)
- `src/main/resources/schemas/EquipmentInstruction.avro` (copied from schemas/)
- `src/main/java/com/wonderingwizard/server/JsonSerializer.java` (modified — serializes new fields)
- `src/test/java/com/wonderingwizard/kafka/ActionActivatedToEquipmentInstructionMapperTest.java`
- `src/test/java/com/wonderingwizard/kafka/KafkaSideEffectPublisherTest.java`

### F-14: AssetEvent Kafka Consumers (JSON)

**Status:** Implemented

**Description:**
Consume AssetEvent messages from three JSON Kafka topics (RTG, QC, EH). AssetEvents are operational milestones published by container handling equipment, reporting actions such as lifting containers, placing them, or reaching positions.

1. A `JsonEventMapper<E>` functional interface maps raw JSON strings to engine events (counterpart to `EventMapper` for Avro)
2. A `KafkaJsonEventConsumer<E>` consumes JSON messages using `StringDeserializer` (counterpart to `KafkaEventConsumer` for Avro)
3. `KafkaConsumerManager` extended with `registerJson()` to support both Avro and JSON consumers
4. An `AssetEventMapper` parses flat JSON AssetEvent messages using the existing `JsonParser`
5. Three consumers registered — one per CHE type (RTG, QC, EH), each on its own topic

**Topics:**
- `apmt.terminaloperations.assetevent.rubbertyredgantry.topic.confidential.dedicated.v1`
- `apmt.terminaloperations.assetevent.quaycrane.topic.confidential.dedicated.v1`
- `apmt.terminaloperations.assetevent.emptyhandler.topic.confidential.dedicated.v1`

**JSON Format:**
```json
{
    "move": "LOAD",
    "operationalEvent": "QCplacedContaineronVessel",
    "cheID": "QCZ1",
    "terminalCode": "",
    "timestamp": 1773103048
}
```

**Verification:**

| Step | Action | Expected Result |
|------|--------|-----------------|
| 1 | Parse QC AssetEvent JSON | AssetEvent with correct move, operationalEvent, cheId, timestamp |
| 2 | Parse RTG AssetEvent JSON | AssetEvent with RTG cheId and operational event |
| 3 | Parse compact JSON (no whitespace) | Same result as formatted JSON |
| 4 | Register JSON consumers in manager | JSON consumers tracked separately from Avro consumers |

**Test Execution:**
```bash
mvn test -Dtest="AssetEventMapperTest,KafkaJsonEventConsumerTest,KafkaConsumerManagerTest"
```

**Implementation Files:**
- `src/main/java/com/wonderingwizard/kafka/JsonEventMapper.java` (new — functional interface for JSON)
- `src/main/java/com/wonderingwizard/kafka/KafkaJsonEventConsumer.java` (new — JSON consumer)
- `src/main/java/com/wonderingwizard/kafka/AssetEventMapper.java` (new — JSON → AssetEvent mapper)
- `src/main/java/com/wonderingwizard/events/AssetEvent.java` (new — event record)
- `src/main/java/com/wonderingwizard/kafka/KafkaConsumerManager.java` (modified — added registerJson)
- `src/main/java/com/wonderingwizard/server/Settings.java` (modified — 3 AssetEvent consumer configs)
- `src/main/java/com/wonderingwizard/server/DemoServer.java` (modified — registers 3 AssetEvent consumers)
- `src/test/java/com/wonderingwizard/kafka/AssetEventMapperTest.java`
- `src/test/java/com/wonderingwizard/kafka/KafkaJsonEventConsumerTest.java`

### F-15: Work Instructions Page

**Status:** Implemented

**Description:**
Add a standalone web page at `/workinstructions` that displays all distinct work instructions with their latest state, updated in real time via SSE. Users can edit any field inline and re-send the work instruction event.

**Requested Behavior:**

1. Navigate to `/workinstructions` in the browser
2. The page shows a table of all `WorkInstructionEvent` events that have been processed, deduplicated by `workInstructionId` (only the latest state is shown)
3. Each field in the table is editable (same fields as the Export Editor: WI ID, WQ ID, Fetch CHE, Status, Est. Move Time, Cycle Time, RTG Cycle Time, Put CHE, Twin Fetch/Put/Carry, Twin Companion, To Position)
4. Each row has a "Send" button that POSTs the (potentially edited) row to `/api/work-instruction`
5. The table updates live as new events arrive via SSE

**Verification:**

| Step | Action | Expected Result |
|------|--------|-----------------|
| 1 | Navigate to `/workinstructions` | Page loads with empty state message |
| 2 | Send a WorkInstructionEvent from the main view | Table shows 1 row with the WI data |
| 3 | Send another event for the same WI ID with different status | Table still shows 1 row with the updated status |
| 4 | Send a WorkInstructionEvent for a different WI ID | Table shows 2 rows |
| 5 | Edit a field in the table and click Send | WorkInstructionEvent is sent with edited values |

**Test Execution:**
```bash
mvn test -Dtest="DemoServerTest"
```

**Implementation Files:**
- `src/main/resources/workinstructions.html` (new — Work Instructions UI page)
- `src/main/java/com/wonderingwizard/server/DemoServer.java` (modified — added `/workinstructions` route)
- `src/test/java/com/wonderingwizard/server/DemoServerTest.java` (modified — added HTTP endpoint tests)

### F-16: Digital Map Processor and Schedule Creation Pipeline

**Status:** Implemented

**Description:**
Implement a DigitalMapProcessor that handles digital map events and provides pathfinding-based duration enrichment for TT drive actions during schedule creation. The processor has a dual role:

1. **EventProcessor:** Reacts to `DigitalMapEvent` to parse and store a terminal map graph. Produces no side effects.
2. **SchedulePipelineStep:** Registered with `WorkQueueProcessor` as a step in the schedule creation pipeline. Computes travel durations between yard positions using pathfinding and adjusts TT drive action durations before takt fitting.

**Schedule Creation Pipeline:**
The schedule creation is now a three-step pipeline:
1. **Template creation** — `GraphScheduleBuilder` builds action templates with default durations
2. **Duration enrichment** — Pipeline steps (e.g., `DigitalMapProcessor`) modify action durations based on external data
3. **Takt fitting** — Actions with final durations are fitted into takts, introducing additional takts if actions don't fit

Pipeline steps are registered dynamically with `WorkQueueProcessor.registerStep()`. If no steps are registered, templates pass directly to takt fitting with default durations.

**Key Design Decisions:**
- Existing schedules are **not** affected when a new map arrives; only new schedules use updated map data
- The digital map is parsed into a bidirectional adjacency graph for pathfinding
- TT drive durations are scaled proportionally based on the ratio of map path duration to default total drive duration
- The pipeline step interface (`SchedulePipelineStep`) is extensible — additional enrichers can be added without modifying existing code

**Digital Map Event Format:**
```json
{
    "edges": [
        {"from": "Y-PTM-1L20E4", "to": "QC-01", "durationSeconds": 180},
        {"from": "QC-01", "to": "Y-PTM-2R10A1", "durationSeconds": 200}
    ]
}
```

**Verification:**

| Step | Action | Expected Result |
|------|--------|-----------------|
| 1 | Process `DigitalMapEvent` with valid map | Map parsed, no side effects, `isMapLoaded()` is true |
| 2 | Process `DigitalMapEvent` with empty payload | Map not loaded, no side effects |
| 3 | Find path duration between connected nodes | Returns shortest path duration |
| 4 | Find path between disconnected nodes | Returns -1 |
| 5 | Create schedule with map loaded | TT drive durations adjusted by map pathfinding |
| 6 | Create schedule without map loaded | Default durations used (passthrough) |
| 7 | Load new map over existing map | Old map replaced, new durations used |
| 8 | Capture and restore state | Map state correctly saved and restored |

**Test Execution:**
```bash
mvn test -Dtest="DigitalMapProcessorTest"
```

**Implementation Files:**
- `src/main/java/com/wonderingwizard/events/DigitalMapEvent.java` (new — digital map event record)
- `src/main/java/com/wonderingwizard/processors/DigitalMapProcessor.java` (new — dual-role processor)
- `src/main/java/com/wonderingwizard/processors/SchedulePipelineStep.java` (new — pipeline step interface)
- `src/main/java/com/wonderingwizard/processors/GraphScheduleBuilder.java` (modified — added pipeline-aware `createTakts` overload and `withDuration` on ActionTemplate)
- `src/main/java/com/wonderingwizard/processors/WorkQueueProcessor.java` (modified — added `registerStep()` and passes pipeline steps to schedule builder)
- `src/main/java/com/wonderingwizard/server/DemoServer.java` (modified — registers DigitalMapProcessor)
- `src/main/java/com/wonderingwizard/server/JsonSerializer.java` (modified — added DigitalMapEvent serialization)
- `src/main/java/com/wonderingwizard/server/EventDeserializer.java` (modified — added DigitalMapEvent deserialization)
- `src/test/java/com/wonderingwizard/processors/DigitalMapProcessorTest.java` (new — comprehensive test suite)

### F-17: End-to-End Performance Tests

**Status:** Implemented

**Description:**
End-to-end test suite that interacts with a running server instance via the same HTTP API used by the frontend. Tests run against a live `localhost` server to validate real-world performance characteristics.

**Test: Schedule Creation Performance**
Creates a high volume of work instructions and measures schedule creation throughput:
1. Create 10,000 WorkInstructions (1,000 per WQ across 10 WQs) via `POST /api/work-instruction`
2. Activate all 10 WQs via `POST /api/work-queue` with status `ACTIVE`
3. Measure how long each activation takes to return with a `ScheduleCreated` side effect
4. Verify all 10 schedules exist via `GET /api/state`

Work instruction creation uses virtual threads for concurrent HTTP requests. WQ activation is sequential to measure individual schedule creation time per queue.

**How to Run:**
```bash
# Terminal 1: Start the server
mvn exec:java

# Terminal 2: Run e2e tests
mvn test -Pe2e
```

The `e2e` Maven profile overrides the default surefire exclusion to include only tests tagged with `@Tag("e2e")`. These tests are excluded from normal `mvn test` runs.

The base URL defaults to `http://localhost:8080` and can be overridden with:
```bash
mvn test -Pe2e -De2e.baseUrl=http://localhost:9090
```

**Verification:**

| Step | Action | Expected Result |
|------|--------|-----------------|
| 1 | Server not running, run e2e tests | Test fails with clear error message |
| 2 | Server running, run e2e tests | 10,000 WIs created, 10 schedules created, timing printed |
| 3 | Normal `mvn test` | E2E tests are skipped |

**Implementation Files:**
- `src/test/java/com/wonderingwizard/e2e/ScheduleCreationPerformanceE2ETest.java` (new — e2e performance test)
- `pom.xml` (modified — added `e2e` profile and `excludedGroups` for surefire)

### F-18: Conditional Buffer Action (skipWhenGatesSatisfied)

**Status:** Implemented

**Description:**
Add a conditional `TT_DRIVE_TO_BUFFER` action between `TT_HANDOVER_FROM_QC` and `TT_DRIVE_TO_RTG_PULL` in all discharge templates. This action should only execute when `QC_DISCHARGED_CONTAINER` events have NOT yet arrived — if they have already arrived (RTG is ready), the action is automatically skipped.

This is implemented via a new `skipWhenGatesSatisfied` flag on `ActionTemplate` and `Action`:
- Event gates on the action define the **skip condition**, not an activation barrier
- At activation time: if all event gates are already satisfied → auto-complete (skip)
- At activation time: if event gates are NOT satisfied → activate normally (TT drives to buffer)

**Behavior:**
- **Happy path:** QC discharges both containers before TT reaches the buffer action → gates are pre-satisfied → action auto-completes → TT goes straight to RTG
- **Buffer path:** QC hasn't discharged yet → TT drives to buffer → TT continues to RTG after buffer action completes externally

**Templates modified:**
All discharge templates with `QC_DISCHARGED_CONTAINER` event gates:
- `getDischargeTwinTemplate` (gates with suffix 1 and 2)
- `getDischargeSingleTemplate`
- `getDischargeLiftSinglesDropTwin`
- `getDischargeLiftSinglesDropSinglesSameBay`
- `getDischargeLiftSinglesDropSinglesDifferentBay`
- `getDischargeLiftTwinsDropSinglesSameBay`
- `getDischargeLiftTwinsDropSinglesDifferentBay`

**Verification:**

| Step | Action | Expected Result |
|------|--------|-----------------|
| 1 | Gates satisfied before activation | Action auto-completes, next action activates immediately |
| 2 | Gates NOT satisfied at activation | Action activates normally (TT drives to buffer) |
| 3 | All 389 tests pass | No regressions |

### F-19: Nuke Work Queue

**Status:** Implemented

**Description:**
Add a "Nuke" button to the Work Queues editor page that completely deletes a work queue, all its work instructions, and any active schedule. This is a destructive operation with a confirmation dialog.

**Event Flow:**
1. User clicks "Nuke" button on a work queue row → confirmation dialog
2. Frontend sends `POST /api/nuke-work-queue` with `{ workQueueId }`
3. Server creates `NukeWorkQueueEvent` and processes it through the engine
4. `WorkQueueProcessor` removes the WQ from all internal maps (`activeSchedules`, `workInstructions`, `qcMudaByQueue`, `loadModeByQueue`) and emits `ScheduleAborted` if there was an active schedule
5. `ScheduleRunnerProcessor` removes the schedule state for that WQ
6. Frontend removes the row from the table

**Verification:**

| Step | Action | Expected Result |
|------|--------|-----------------|
| 1 | Nuke an active work queue | ScheduleAborted emitted, all data cleared, row removed |
| 2 | Nuke a non-existent work queue | No side effects, no errors |
| 3 | Re-activate after nuke | Creates fresh empty schedule (no WIs) |

**Implementation Files:**
- `src/main/java/com/wonderingwizard/events/NukeWorkQueueEvent.java` (new — event record)
- `src/main/java/com/wonderingwizard/processors/WorkQueueProcessor.java` (modified — handles NukeWorkQueueEvent)
- `src/main/java/com/wonderingwizard/processors/ScheduleRunnerProcessor.java` (modified — handles NukeWorkQueueEvent)
- `src/main/java/com/wonderingwizard/server/DemoServer.java` (modified — new `/api/nuke-work-queue` endpoint)
- `src/main/java/com/wonderingwizard/server/JsonSerializer.java` (modified — serialization support)
- `src/main/java/com/wonderingwizard/server/EventDeserializer.java` (modified — deserialization support)
- `src/main/resources/workqueues.html` (modified — Nuke button with confirmation)
- `src/test/java/com/wonderingwizard/processors/WorkQueueProcessorTest.java` (modified — 2 new nuke tests)
- `src/main/java/com/wonderingwizard/processors/GraphScheduleBuilder.java` (modified — added `skipWhenGatesSatisfied` to `ActionTemplate`, added conditional buffer to all discharge templates)
- `src/main/java/com/wonderingwizard/processors/ScheduleRunnerProcessor.java` (modified — skip event gate check for `skipWhenGatesSatisfied` actions, auto-complete when gates pre-satisfied)
- `src/test/java/com/wonderingwizard/processors/ScheduleRunnerProcessorTest.java` (modified — 2 new tests for skip behavior)
