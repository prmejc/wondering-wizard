# Wondering Wizard - Roadmap

## Demo: Interactive Schedule Viewer

The first milestone is a live web demo that walks through the full event processing lifecycle — from registering work instructions to watching actions execute across devices, with the ability to rewind time.

### Demo Script

1. **Send events** — register work instructions and activate a work queue via the web UI
2. **Observe the schedule** — see the generated takts and actions laid out by device (RTG, TT, QC)
3. **Start the clock** — tick simulated time forward and watch actions activate when `estimatedMoveTime` is reached
4. **Watch side effects** — see `ActionActivated`, `ActionCompleted`, `ScheduleCreated` appear in real time
5. **Complete actions** — click active actions in the schedule viewer to fire `ActionCompletedEvent` and unblock dependent actions
6. **Rewind time** — click any past event in the timeline to step the engine back, restoring all processor state to that point

---

## What needs to be built

### M-1: HTTP Demo Server
Embed a lightweight HTTP server (JDK `HttpServer`, zero dependencies) that wraps the existing engine and exposes a REST API.

- `GET /` — serve the single-page schedule viewer
- `GET /api/state` — return full engine state as JSON (current time, steps, schedules, side effects)
- `POST /api/work-instruction` — send a `WorkInstructionEvent`
- `POST /api/work-queue` — send a `WorkQueueMessage` (ACTIVE / INACTIVE)
- `POST /api/tick` — advance simulated clock by N seconds, send `TimeEvent`
- `POST /api/action-completed` — send an `ActionCompletedEvent`
- `POST /api/step-back-to` — revert engine to a target step using `stepBack()`

Track each user action as a numbered step with its resulting side effects. On rewind, call `engine.stepBack()` the correct number of times (accounting for `EventPropagatingEngine` expansion) to keep the external history in sync with the engine's internal state.

### M-2: Schedule Viewer UI
Single `index.html` file using Web Components and vanilla JS (no frameworks, no dependencies).

**Layout:**
- **Header** — simulated clock display with tick buttons (+1 min, +5 min, +15 min)
- **Left panel** — event input forms (add work instruction, activate/deactivate work queue)
- **Center panel** — schedule visualization: takts as rows, actions as color-coded cards (RTG blue, TT amber, QC green) showing pending / active / completed status
- **Right panel** — event timeline with clickable entries for time travel
- **Bottom strip** — live side effects log

**Key interactions:**
- Active actions show a "Complete" button that sends `ActionCompletedEvent`
- Clicking a past step in the timeline reverts the engine and UI to that point
- State refreshes after every user action (no polling)

### M-3: JSON Serialization (no library)
Hand-rolled JSON serializer using Java pattern matching on sealed types, records, enums, `Instant`, and `UUID`. Minimal flat-object JSON parser for incoming request bodies.

---

## Conventions

- Zero external runtime dependencies — JDK only
- Time is always driven by `TimeEvent`, never `System.currentTimeMillis()`
- All domain values use enums or sealed types — no raw strings
- Side effects are the only output — processors never call external systems directly
- Frontend: Web Components, vanilla JS, single `index.html`
