# Remaining Kafka Topics Analysis

## Unimplemented Consumer Topics

### 1. FlowSchedulingStandards (Consumer)
**Topic:** `apmt.terminal-operations.flow-scheduling-standards.topic.internal.any.v1`

**What it does:** Configuration parameters for schedule planning, published by a central config system. Contains:
- `planningLockWindow` — minutes before a takt becomes "frozen" (can't be rescheduled)
- `dischargeWipLimit` — max work-in-progress for discharge operations
- `truckSelectionParameters` — weights for truck allocation (proximity vs job urgency)
- `containerTransferDuration` — default handover times: TT↔QC, TT↔RTG, TT↔EH (in seconds)

**Impact:** Medium. The `containerTransferDuration` values could replace hardcoded action durations for QC_PLACE, TT_HANDOVER_FROM_QC, RTG_LIFT_FROM_TT, etc. The `dischargeWipLimit` could limit how many takts are active simultaneously. `planningLockWindow` is needed for multi-WQ planning (freezing schedules near execution).

**Effort:** Low — consume and store, wire values into schedule builder as default durations.

---

## Unimplemented Producer Topics

### 2. FlowStatusV4 (Producer)
**Topic:** `apmt.terminaloperations.flowstatus.topic.confidential.dedicated.v4`

**What it does:** The main status heartbeat — tells the outside world the current state of each work queue. Published on every state change (takt activation, completion, etc.). Key fields:
- `workQueueState` — PLANNED / IN_PROGRESS / DONE / CANCELLED / BLOCKED
- `qcShortName` — which QC is executing this WQ
- `qcNotReadyReasonsForNextTriggerState` — why the next takt can't fire (AWAITING_QC_READY, PENDING_WORK_PREVIOUS_WQ, ACTIVE_DELAY_CODE, WIP_LIMIT_REACHED, MANUAL_WORK)
- `lastTriggerType` / `lastTriggerSequenceNumber` — PULSE99, TAKT100, etc.
- `lastTriggerActualTimestamp` / `lastTriggerPlannedTimestamp` — when the current takt started (actual vs planned)
- `nextTriggerExpectedTimestamp` / `nextTriggerPlannedTimestamp` — when the next takt will fire
- `expectedLiftCount` / `actualLiftCount` — container lift progress
- `nextTaktDependencies` — what's blocking the next takt (list of actions with state)
- `activeChesForWQ` — all active equipment for this WQ

**Impact:** HIGH. This is the primary integration point for external monitoring, WQ sequencing, and QC readiness. The `qcNotReadyReasonsForNextTriggerState` field is directly related to QC readiness states. The `PENDING_WORK_PREVIOUS_WQ` reason is the mechanism for multi-WQ sequential execution.

**Effort:** Medium — we already have all the data (takt states, action states, timestamps). Mainly mapping and serialization.

### 3. FlowException (Producer)
**Topic:** `apmt.deviationmanagement.exception.topic.internal.status.v1`

**What it does:** Reports anomalies and errors to a deviation management system. Fields:
- `exceptionEvent` — CREATE / UPDATE / RESOLVE
- `exceptionType` / `exceptionSeverity` — categorization
- `businessEntityType` / `businessEntityId` — what entity has the problem
- `reasons[]` — detailed reasons with involved work instructions

**Impact:** Low for execution. Important for ops visibility. Would emit exceptions for: WI bypass, writeback failures, stuck actions, missing truck assignments.

**Effort:** Low — fire-and-forget producer, no state changes.

### 4. TAKTReport (Producer)
**Topic:** `apmt.terminaloperations.flowanalytics-taktreport.topic.internal.any.v2`

**What it does:** Analytics report published on takt completion. Contains per-takt and per-equipment timing data:
- Takt level: `plannedStartTime`, `actualStartTime`, `plannedEndTime`, `actualEndTime`, `plannedDuration`, `actualDuration`, `deviation`, `delay`
- Per equipment: same timing fields + `plannedFloat`, `actualFloat`
- Per activity (action): timing + `endState` (completed/cancelled) + `dependentTaktId`

**Impact:** Low for execution. HIGH for analytics/reporting. We now have all the planned/estimated/actual times on actions — this is essentially a serialization of what we already compute.

**Effort:** Low — emit on TaktCompleted side effect, serialize existing data.

### 5. FlowPreliminaryPlan (Producer)
**Topic:** `apmt.terminaloperations.flowpreliminaryplan.topic.internal.any.v1`

**What it does:** Publishes the planned schedule for a QC (point of work). Groups all WQs assigned to a QC with:
- `pointOfWorkName` — the QC
- `schedules[]` — list of WQs with `workQueueId`, `isFrozen`, `warmupPulsesStartTimeUtc[]`, `firstTaktMoveTime`

**Impact:** Medium. This is the multi-WQ per QC view — directly related to sequential WQ planning. Tells external systems which WQs are planned for a QC and in what order.

**Effort:** Low — serialization of existing schedule data grouped by QC.

### 6. FlowTerminalTruckForecast (Producer)
**Topic:** `apmt.terminal-operations.flow-terminal-truck-forecast.topic.internal.any.v2`

**What it does:** Publishes truck demand forecast in time intervals. Per interval:
- `startTime` / `endTime` — the interval window
- Per QC: `totalFrozen`, `totalUnfrozen`, `totalTrucksScheduled` — how many trucks needed
- `currentTrucksAvailableInFESPool` — global truck availability

**Impact:** Low for execution. Important for truck pool management. This is the TT forecast topic you mentioned — it tells truck dispatchers how many trucks each QC needs over time.

**Effort:** Medium — needs to aggregate truck demand across all active schedules per time window. We have the schedule data, but need the interval bucketing logic.

### 7. FlowWork (Producer)
**Topic:** `apmt.terminal-operations.flow-work-execution-state.topic.internal.any.v1`

**What it does:** Per-work-instruction execution state. Published when WI state changes:
- `workInstructionState` — Preliminary / Frozen / Completed / Aborted
- `workQueueState` — same enum, WQ-level
- `plannedStartTime` / `frozenEstimatedMoveTime`
- `workInstructionQueueSequence`, `containerId`, `vesselVisitId`

**Impact:** Medium. Needed for writeback to TOS (N4/Sparcs) — tells the external system which WIs are frozen (committed) vs preliminary. Related to the planning lock window from FlowSchedulingStandards.

**Effort:** Low — emit on schedule creation and WI state changes.

### 8. EH Equipment Instruction (Producer)
**Topic:** `apmt.terminaloperations.equipmentinstruction.emptyhandler.topic.confidential.dedicated.v1`

**What it does:** Same as QC/TT/RTG equipment instructions but for empty handlers. Uses the same EquipmentInstruction schema.

**Impact:** Low until EH support is needed.

**Effort:** Low — same pattern as existing equipment instruction producers.

---

## Priority Recommendation

### What to start with for maximum impact:

**1. Multi-WQ per QC sequential execution (highest impact)**

This is the biggest architectural change and directly affects scheduling performance. It requires:
- Grouping WQs by QC (pointOfWorkName)
- Ordering by earliest estimated move time
- The first WQ executes normally; subsequent WQs wait until the previous one finishes
- Delay in WQ1 cascades to WQ2, WQ3, etc.

This touches: `WorkQueueProcessor` (schedule creation), `ScheduleRunnerProcessor` (activation gating), `EstimatedTimeCalculator` (cross-WQ delay propagation).

The FlowStatusV4 `PENDING_WORK_PREVIOUS_WQ` reason and FlowPreliminaryPlan are the external manifestations of this feature.

**2. FlowStatusV4 producer (second highest impact)**

This is the main external integration — without it, nothing outside FES knows what's happening. It's also prerequisite for QC readiness states. The `qcNotReadyReasonsForNextTriggerState` in FlowStatusV4 IS the QC readiness mechanism — it tells external systems (and potentially FES itself) whether the QC is ready.

**3. QC readiness states**

Looking at the schemas, QC readiness is modeled as:
- **FlowStatusV1:** `qcReadyForNextTriggerState` = OPEN_PRECEDING_WORKINSTRUCTIONS / DELAY_CODE / WAITING_FOR_SPREADER / READY
- **FlowStatusV4:** `qcNotReadyReasonsForNextTriggerState[]` = AWAITING_QC_READY_TRIGGER_OPS2OPS / PENDING_WORK_PREVIOUS_WQ / ACTIVE_DELAY_CODE / WIP_LIMIT_REACHED / MANUAL_WORK

This is about gating takt activation based on QC state. You already consume `CraneReadiness` and `CraneAvailabilityStatus` — the missing piece is using those signals to block takt activation and reporting the readiness state via FlowStatusV4.

### Recommended order:
1. **Multi-WQ per QC** — foundational, everything else builds on it
2. **FlowStatusV4** — external visibility, includes QC readiness reporting
3. **TAKTReport** — easy win, all data already exists
4. **FlowPreliminaryPlan** — easy win after multi-WQ is done
5. **FlowWork** — needed for TOS writeback
6. **FlowTerminalTruckForecast** — the TT forecast topic
7. **FlowSchedulingStandards consumer** — config values, wire into existing code
8. **FlowException** — ops visibility, low priority
9. **EH instructions** — when EH support is needed
