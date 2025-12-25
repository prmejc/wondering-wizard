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

1. Accept `WorkQueueMessage` events with a `workQueueId` and `status` field
2. Create a new schedule when a message with status "Active" is received for a new workQueueId
3. Be idempotent for duplicate "Active" messages with the same workQueueId
4. Abort the schedule when a message with status "Inactive" is received for an existing workQueueId

**Requested Behavior:**

```java
engine.register(new WorkQueueProcessor());

var sideEffects1 = engine.processEvent(new WorkQueueMessage("queue-1", "Active"));
var sideEffects2 = engine.processEvent(new WorkQueueMessage("queue-1", "Active"));
var sideEffects3 = engine.processEvent(new WorkQueueMessage("queue-1", "Inactive"));
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
| 2 | Process `WorkQueueMessage("queue-1", "Active")` | Returns `[ScheduleCreated]`, event and side effect logged |
| 3 | Process `WorkQueueMessage("queue-1", "Active")` (duplicate) | Empty side effects list (idempotent), event logged |
| 4 | Process `WorkQueueMessage("queue-1", "Inactive")` | Returns `[ScheduleAborted]`, event and side effect logged |

**Test Execution:**
```bash
# Run tests
mvn test -Dtest=WorkQueueProcessorTest
```

**Expected Output:**
```
INFO: Processing event: WorkQueueMessage[workQueueId=queue-1, status=Active]
INFO: Side effect: ScheduleCreated[workQueueId=queue-1]

INFO: Processing event: WorkQueueMessage[workQueueId=queue-1, status=Active]
INFO: No side effects produced

INFO: Processing event: WorkQueueMessage[workQueueId=queue-1, status=Inactive]
INFO: Side effect: ScheduleAborted[workQueueId=queue-1]
```

**Implementation Files:**
- `src/main/java/com/wonderingwizard/events/WorkQueueMessage.java`
- `src/main/java/com/wonderingwizard/sideeffects/ScheduleCreated.java`
- `src/main/java/com/wonderingwizard/sideeffects/ScheduleAborted.java`
- `src/main/java/com/wonderingwizard/processors/WorkQueueProcessor.java`
