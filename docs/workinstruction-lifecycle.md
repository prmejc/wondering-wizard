# Work instruction special cases

### Abandon
Abandon job is used when an unladen TT with a WI assigned needs to be released from its current WI to do other (non-FES) work)
workinstruction.eventType= "WI Abandoned"
Precondition:
- If TT is has not yet physically arrived & confirmed at QC Under (in DISCH) => Assign new TT
- If TT already cofirmed under QC => remove WI from FES (and his twin)

### TT unavailable
  Operator announces himself as unavailable
  containerhandlingequipment topic = "Unavailable"
  Precondition:
- If TT is has not yet physically arrived & confirmed at QC Under (in DISCH) => Assign new TT
- If TT already cofirmed under QC => remove WI from FES (and his twin)


### Reset
Reset is used when there is an issue with the WI in execution. The WI state (workInstructionMoveStage) in TOS will be changed to Planned and manual action is needed to do replanning.
workinstruction.eventType= "WI Reset"
Behavior: Remove WI from FES (anytime)

### Revert
Revert is used when a WI is to be deleted from the plan
workinstruction.eventType= "WI Reverted"
Precondition:
- TT is unladen OR WI not yet dispatched => Remove WI from FES (anytime)
- If WQ is unfrozen => Remove WI from (preliminary) plan

### WQ Change
When WI is moved out of a frozen FES-WQ workinstruction.WQId changed for a WI 

Behavior: remove WI from FES
  
### Bypass - not applicable in DSCH (!)
  Bypass job is used when a WI is not to be dispatched to a TT
  workinstruction.eventType = "WI Bypassed" OR workinstruction.suspendState "BYPASS"
  Precondition: WI not yet dispatched to a TT
  Behavior:
- T-ONE to not dispatch the bypassed WI (flow pauses)
- once Bypass state is removed, T-ONE to continue execution of bypassed WI ("WI Resumed" / suspendState "NONE")

# Work Instruction Lifecycle from examples

This document describes the lifecycle of a Work Instruction (WI) based on observed
`workInstructionMoveStage` and `eventType` combinations from real work instructions.

## Move Stages

The `workInstructionMoveStage` field on Kafka messages can have the following values:

| Move Stage       | Internal Status  | Description                                         |
|------------------|------------------|-----------------------------------------------------|
| Planned          | PENDING          | WI is planned but not yet in motion                 |
| Ready            | PENDING          | WI is ready to be executed                          |
| Fetch Underway   | IN_PROGRESS      | Fetch CHE is moving to pick up the container        |
| Carry Underway   | IN_PROGRESS      | Container is being carried to destination           |
| Carry Complete   | IN_PROGRESS      | Carry leg finished, awaiting put                    |
| Put Underway     | IN_PROGRESS      | Container is being placed at target position        |
| Put Complete     | IN_PROGRESS      | Put leg finished, awaiting final completion         |
| Complete         | COMPLETED        | WI has been completed                               |
| Cancelled        | CANCELLED        | WI has been cancelled                               |

## Load Lifecycle (WI 30812311)

```
                          ┌─────────────────────────────────────────────────┐
                          │                   Planned                       │
                          │          2026-03-15 06:13 → 2026-03-17 05:38   │
                          │                 (357 events)                    │
                          │                                                 │
                          │  Events:                                        │
                          │   - Estimated Move Time Changed  (280)          │
                          │   - FetchCHE Changed             (24)           │
                          │   - PutCHE Changed               (21)           │
                          │   - FetchCHE Assigned            (19)           │
                          │   - Estimated Cycle Time Changed  (3)           │
                          │   - StateFetch Changed            (3)           │
                          │   - WI Bypassed                   (3)           │
                          │   - WI Resumed                    (3)           │
                          │   - To Position Changed           (2)           │
                          │   - WQ Change                     (2)           │
                          │   - Dispatch Changed              (1)           │
                          │   - Pinning Changed               (1)           │
                          └──────────────────────┬──────────────────────────┘
                                                 │
                                   WI MoveStage Changed
                                                 │
                                                 ▼
                          ┌─────────────────────────────────────────────────┐
                          │               Carry Underway                    │
                          │          2026-03-17 05:39 → 2026-03-17 05:42   │
                          │                  (4 events)                     │
                          │                                                 │
                          │  Events:                                        │
                          │   - Estimated Move Time Changed   (2)           │
                          │   - WI MoveStage Changed          (1)           │
                          │   - Estimated Cycle Time Changed  (1)           │
                          └──────────────────────┬──────────────────────────┘
                                                 │
                                    QC Loaded Container
                                                 │
                                                 ▼
                          ┌─────────────────────────────────────────────────┐
                          │                  Complete                        │
                          │             2026-03-17 05:47                     │
                          │                 (1 event)                        │
                          │                                                 │
                          │  Events:                                        │
                          │   - QC Loaded Container           (1)           │
                          └─────────────────────────────────────────────────┘
```

## Event Types by Move Stage

### Planned (357 events over ~47 hours)

| Event Type                     | Count | Description                                       |
|--------------------------------|-------|---------------------------------------------------|
| Estimated Move Time Changed    | 280   | Continuous recalculation of estimated move time    |
| FetchCHE Changed               | 24    | Fetch crane/equipment reassigned                   |
| PutCHE Changed                 | 21    | Put crane/equipment reassigned                     |
| FetchCHE Assigned              | 19    | Fetch crane/equipment initially assigned           |
| Estimated Cycle Time Changed   | 3     | Cycle time estimate updated                        |
| StateFetch Changed             | 3     | Fetch state transition                             |
| WI Bypassed                    | 3     | Work instruction temporarily bypassed              |
| WI Resumed                     | 3     | Work instruction resumed after bypass              |
| To Position Changed            | 2     | Target position changed                            |
| WQ Change                      | 2     | Work queue changed                                 |
| Dispatch Changed               | 1     | Dispatch status changed                            |
| Pinning Changed                | 1     | Container pinning assignment changed               |

### Carry Underway (4 events over ~3 minutes)

| Event Type                     | Count | Description                                       |
|--------------------------------|-------|---------------------------------------------------|
| Estimated Move Time Changed    | 2     | Move time recalculated during carry                |
| WI MoveStage Changed           | 1     | Transition event into this stage                   |
| Estimated Cycle Time Changed   | 1     | Cycle time updated during carry                    |

### Complete (1 event)

| Event Type                     | Count | Description                                       |
|--------------------------------|-------|---------------------------------------------------|
| QC Loaded Container            | 1     | Quay crane loaded the container — WI complete      |

## Stage Transitions

| From            | To              | Triggered By          |
|-----------------|-----------------|-----------------------|
| Planned         | Carry Underway  | WI MoveStage Changed  |
| Carry Underway  | Complete        | QC Loaded Container   |

## Discharge Lifecycle (WI 30816005)

```
                          ┌─────────────────────────────────────────────────┐
                          │                   Planned                       │
                          │          2026-03-15 09:59 → 2026-03-17 03:32   │
                          │                 (50 events)                     │
                          │                                                 │
                          │  Events:                                        │
                          │   - Estimated Move Time Changed  (29)           │
                          │   - CarryCHE Assigned             (3)           │
                          │   - PutCHE Assigned               (3)           │
                          │   - PutCHE Changed                (3)           │
                          │   - WI Abandoned                  (3)           │
                          │   - WI Resequenced                (2)           │
                          │   - CarryCHE Changed              (1)           │
                          │   - Estimated Cycle Time Assigned (1)           │
                          │   - Estimated Move Time Assigned  (1)           │
                          │   - FetchCHE Assigned             (1)           │
                          │   - StateFetch Changed            (1)           │
                          │   - WI Created                    (1)           │
                          │   - WQ Change                     (1)           │
                          └──────────────────────┬──────────────────────────┘
                                                 │
                                  QC Discharged Container
                                                 │
                                                 ▼
                          ┌─────────────────────────────────────────────────┐
                          │               Carry Underway                    │
                          │          2026-03-17 03:35 → 2026-03-17 03:36   │
                          │                  (2 events)                     │
                          │                                                 │
                          │  Events:                                        │
                          │   - QC Discharged Container       (1)           │
                          │   - PutCHE Assigned               (1)           │
                          └──────────────────────┬──────────────────────────┘
                                                 │
                                   WI MoveStage Changed
                                                 │
                                                 ▼
                          ┌─────────────────────────────────────────────────┐
                          │               Carry Complete                    │
                          │             2026-03-17 03:40                    │
                          │                 (1 event)                       │
                          │                                                 │
                          │  Events:                                        │
                          │   - WI MoveStage Changed          (1)           │
                          └──────────────────────┬──────────────────────────┘
                                                 │
                                   WI MoveStage Changed
                                                 │
                                                 ▼
                          ┌─────────────────────────────────────────────────┐
                          │                Put Complete                     │
                          │             2026-03-17 03:41                    │
                          │                 (1 event)                       │
                          │                                                 │
                          │  Events:                                        │
                          │   - WI MoveStage Changed          (1)           │
                          └──────────────────────┬──────────────────────────┘
                                                 │
                                    StateFetch Changed
                                                 │
                                                 ▼
                          ┌─────────────────────────────────────────────────┐
                          │                  Complete                        │
                          │          2026-03-17 03:42 → 2026-03-17 04:05   │
                          │                  (2 events)                     │
                          │                                                 │
                          │  Events:                                        │
                          │   - StateFetch Changed            (1)           │
                          │   - WQ Change                     (1)           │
                          └─────────────────────────────────────────────────┘
```

### Discharge Event Types by Move Stage

#### Planned (50 events over ~41 hours)

| Event Type                     | Count | Description                                       |
|--------------------------------|-------|---------------------------------------------------|
| Estimated Move Time Changed    | 29    | Continuous recalculation of estimated move time    |
| CarryCHE Assigned              | 3     | Carry crane/equipment assigned                     |
| PutCHE Assigned                | 3     | Put crane/equipment assigned                       |
| PutCHE Changed                 | 3     | Put crane/equipment reassigned                     |
| WI Abandoned                   | 3     | Work instruction temporarily abandoned             |
| WI Resequenced                 | 2     | Work instruction resequenced in queue              |
| CarryCHE Changed               | 1     | Carry crane/equipment reassigned                   |
| Estimated Cycle Time Assigned  | 1     | Initial cycle time estimate assigned               |
| Estimated Move Time Assigned   | 1     | Initial move time estimate assigned                |
| FetchCHE Assigned              | 1     | Fetch crane/equipment assigned                     |
| StateFetch Changed             | 1     | Fetch state transition                             |
| WI Created                     | 1     | Work instruction initially created                 |
| WQ Change                      | 1     | Work queue changed                                 |

#### Carry Underway (2 events over ~1 minute)

| Event Type                     | Count | Description                                       |
|--------------------------------|-------|---------------------------------------------------|
| QC Discharged Container        | 1     | Quay crane discharged the container                |
| PutCHE Assigned                | 1     | Put crane assigned during carry                    |

#### Carry Complete (1 event)

| Event Type                     | Count | Description                                       |
|--------------------------------|-------|---------------------------------------------------|
| WI MoveStage Changed           | 1     | Transition event into this stage                   |

#### Put Complete (1 event)

| Event Type                     | Count | Description                                       |
|--------------------------------|-------|---------------------------------------------------|
| WI MoveStage Changed           | 1     | Transition event into this stage                   |

#### Complete (2 events)

| Event Type                     | Count | Description                                       |
|--------------------------------|-------|---------------------------------------------------|
| StateFetch Changed             | 1     | Fetch state finalized                              |
| WQ Change                      | 1     | Work queue updated after completion                |

### Discharge Stage Transitions

| From            | To              | Triggered By              |
|-----------------|-----------------|---------------------------|
| Planned         | Carry Underway  | QC Discharged Container   |
| Carry Underway  | Carry Complete  | WI MoveStage Changed      |
| Carry Complete  | Put Complete    | WI MoveStage Changed      |
| Put Complete    | Complete        | StateFetch Changed         |

## Load vs Discharge — Key Differences

| Aspect                  | Load (yard → vessel)                  | Discharge (vessel → yard)                    |
|-------------------------|---------------------------------------|----------------------------------------------|
| **Move stages**         | 3 stages: Planned → Carry Underway → Complete | 5 stages: Planned → Carry Underway → Carry Complete → Put Complete → Complete |
| **Trigger to start**    | `WI MoveStage Changed`               | `QC Discharged Container`                    |
| **Completion trigger**  | `QC Loaded Container`                | `StateFetch Changed`                         |
| **Extra stages**        | None                                  | `Carry Complete`, `Put Complete`             |
| **Planned duration**    | ~47 hours (357 events)                | ~41 hours (50 events)                        |
| **Execution duration**  | ~8 minutes                            | ~7 minutes                                   |
| **Event volume**        | High (367 total, 280 EMT changes)     | Lower (56 total, 29 EMT changes)            |
| **Unique events**       | WI Bypassed, WI Resumed, Dispatch Changed, Pinning Changed, To Position Changed | WI Created, WI Abandoned, WI Resequenced, CarryCHE Assigned/Changed, Estimated Cycle Time Assigned, Estimated Move Time Assigned |

### Why Discharge has more stages

Load moves go directly from `Carry Underway` to `Complete` because the quay crane
load operation is the final step — once the container is on the vessel, the WI is done.

Discharge moves have additional intermediate stages (`Carry Complete`, `Put Complete`)
because after the quay crane discharges the container, it still needs to be carried
to the yard and placed (put) in its target position. Each leg of the journey has its
own completion stage.
