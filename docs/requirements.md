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

1. Accept `WorkQueueMessage` events with a `workQueueId` and `status` field (using `WorkQueueStatus` enum)
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

