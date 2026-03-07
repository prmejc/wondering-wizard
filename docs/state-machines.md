# State Machines

## Takt State Machine

A Takt goes through three states during schedule execution.

### States

| State | Description |
|-------|-------------|
| **Waiting** | Takt has been created but is not yet active. All actions within are also in Waiting state. |
| **Active** | Takt is currently being executed. Actions within may transition to Active or Completed. |
| **Completed** | All actions within this takt have been completed. |

### Transitions

```
┌─────────┐        ┌─────────┐        ┌───────────┐
│ Waiting │──────▶│ Active  │──────▶│ Completed │
└─────────┘        └─────────┘        └───────────┘
```

**Waiting → Active:**
A takt transitions to Active when both conditions are met:
1. The previous takt is in state Completed (or this is the first takt in the schedule)
2. The last `TimeEvent` had a timestamp greater than or equal to this takt's `startTime`

When a takt becomes Active, the engine emits a `TaktActivated` side effect containing the takt name and work queue ID.

**Active → Completed:**
A takt transitions to Completed when all actions within it have reached the Completed state.

When a takt becomes Completed, the engine emits a `TaktCompleted` side effect. This may trigger the next takt to become Active if the time condition is also satisfied.

### Initial State

A takt is created in the **Waiting** state when a `ScheduleCreated` event is processed.

---

## Action State Machine

Actions within a takt go through three states during execution.

### States

| State | Description |
|-------|-------------|
| **Waiting** | Action has been created but cannot be executed yet. |
| **Active** | Action is currently being executed. A side effect with the action UUID has been emitted. |
| **Completed** | Action has been completed after receiving an `ActionCompletedEvent`. |

### Transitions

```
┌─────────┐        ┌─────────┐        ┌───────────┐
│ Waiting │──────▶│ Active  │──────▶│ Completed │
└─────────┘        └─────────┘        └───────────┘
```

**Waiting → Active:**
An action transitions to Active when both conditions are met:
1. Its parent takt is in the **Active** state
2. All actions that this action depends on (via `dependsOn` UUIDs) are in the **Completed** state

When an action becomes Active, the engine emits an `ActionActivated` side effect containing the action's UUID. This UUID is used by external systems to identify the action that needs to be performed.

**Active → Completed:**
An action transitions to Completed when the processor receives an `ActionCompletedEvent` with the same action UUID that was emitted in the `ActionActivated` side effect.

When an action becomes Completed, the engine emits an `ActionCompleted` side effect. This may trigger:
- Other actions in the same takt to become Active (if their dependencies are now satisfied)
- The parent takt to become Completed (if all actions in the takt are now completed)
- The next takt to become Active (if the completed takt was the predecessor)

### Initial State

An action is created in the **Waiting** state as part of takt generation.

### Dependency Model

Actions support multiple dependencies via the `dependsOn` set of UUIDs:

```
Single dependency (sequential):
  action1 (no deps) → action2 (depends on action1)

Multiple dependencies (fan-in):
  action1 (no deps) ──┬──▶ action3 (depends on action1 AND action2)
  action2 (no deps) ──┘

Multiple dependents (fan-out):
  action1 (no deps) ──┬──▶ action2 (depends on action1)
                       └──▶ action3 (depends on action1)
```

---

## Container Workflow

The workflow for moving a container from yard to vessel involves three devices
operating in parallel with handover synchronization points.

### Device Actions

**RTG** (3 actions):
```
rtg drive → fetch → rtg handover to TT
```

**TT** (9 actions):
```
drive to RTG pull → drive to RTG standby → drive to RTG under → handover from RTG →
drive to QC pull → drive to QC standby → drive under QC → handover to QC → drive to buffer
```

**QC** (2 actions):
```
handover from TT → place on vessel
```

### Takt Structure (4 takts per container)

```
Takt A (offset -3):  RTG: rtg drive, fetch
                      TT:  drive to RTG pull, drive to RTG standby

Takt B (offset -2):  RTG: rtg handover to TT
                      TT:  drive to RTG under, handover from RTG

Takt C (offset -1):  TT:  drive to QC pull, drive to QC standby

Takt D (offset  0):  TT:  drive under QC, handover to QC, drive to buffer
                      QC:  handover from TT, place on vessel
```

Early takts (A, B, C) have no QC actions because TT has not reached QC position yet.

### Dependency Graph

Actions depend on the previous action of the **same device** (intra-device sequential).
Handover actions additionally depend on the partner device's handover action (cross-device):

```
RTG:  rtg drive ──→ fetch ──→ rtg handover to TT ─────────────┐
                                                                ↓
TT:   drive to RTG pull ──→ drive to RTG standby ──→ drive to RTG under ──→ handover from RTG
      ──→ drive to QC pull ──→ drive to QC standby ──→ drive under QC ──→ handover to QC ──┬→ drive to buffer
                                                                                            ↓
QC:                                                                          handover from TT ──→ place on vessel
```

### Handover Constraints

| RTG action | TT action | Constraint |
|------------|-----------|------------|
| `rtg handover to TT` | `handover from RTG` | Same takt; TT action depends on RTG action |
| — | `handover to QC` | `handover from TT` (QC) depends on this TT action |

- `rtg handover to TT` is always the first RTG action in its takt
- `handover from TT` is always the first QC action in its takt

### Multi-Container Overlap

With multiple containers, each container's workflow is offset by one takt.
Actions from different containers in the same takt are independent (no cross-container dependencies).

```
Example: 2 containers

TAKT100:  Container 0 Takt A  (4 actions)
TAKT101:  Container 0 Takt B + Container 1 Takt A  (7 actions)
TAKT102:  Container 0 Takt C + Container 1 Takt B  (5 actions)
TAKT103:  Container 0 Takt D + Container 1 Takt C  (7 actions)
TAKT104:  Container 1 Takt D  (5 actions)
```

---

## Side Effects Summary

| Side Effect | Trigger |
|-------------|---------|
| `TaktActivated` | Takt transitions from Waiting to Active |
| `TaktCompleted` | Takt transitions from Active to Completed |
| `ActionActivated` | Action transitions from Waiting to Active |
| `ActionCompleted` | Action transitions from Active to Completed |
