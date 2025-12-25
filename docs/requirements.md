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
