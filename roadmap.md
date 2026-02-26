# Wondering Wizard - Project Roadmap

## Current State

The core event processing engine is feature-complete with 6 implemented features:

- **F-1** Time Alarm Processor
- **F-2** Schedule Creation (WorkQueueProcessor)
- **F-3** Step Back / Undo (Memento pattern)
- **F-4** Work Instruction Events
- **F-5** Takt Generation
- **F-6** Schedule Runner (Action Execution)

All features have comprehensive test coverage (~2,235 lines of tests). The engine supports plugin-based processors, event propagation, and full undo capability.

---

## Phase 1: Persistence & External Integration

### F-7: Event Store / Event Sourcing
Persist all processed events to an append-only event store so the full system state can be rebuilt by replaying events. This provides durability, auditability, and enables distributed deployment.

- Append-only event log (in-memory initially, pluggable storage backend)
- Replay capability to reconstruct engine state from stored events
- Snapshot support for faster replay on large event histories
- Integration with step-back (F-3) for persistent undo

### F-8: Side Effect Handlers
Introduce a side effect handler interface that allows external systems to react to side effects. Currently side effects are returned to the caller — handlers would enable push-based integration.

- `SideEffectHandler` interface with typed dispatch
- Support for multiple handlers per side effect type
- Synchronous execution to maintain determinism
- Error handling strategy (fail-fast vs. skip-and-log)

### F-9: External Event Ingestion
Accept events from external sources (message queues, HTTP endpoints) to decouple the engine from the caller.

- Event deserialization from JSON
- Adapter interface for different transport layers (HTTP, AMQP, Kafka)
- Event validation and rejection with meaningful errors

---

## Phase 2: Advanced Scheduling & Domain Features

### F-10: Multi-Device Workflow Templates
Extend the container workflow beyond QC-only actions to support the full container terminal device chain (QC, TT, RTG) with configurable workflow templates.

- Configurable `DeviceActionTemplate` sequences per workflow type
- Cross-device dependency modeling (e.g., TT pickup depends on QC place)
- Device capacity constraints (max concurrent actions per device)

### F-11: Dynamic Schedule Modification
Allow in-flight schedule modifications — adding, removing, or reordering takts while a schedule is running.

- Insert new takts into an active schedule
- Cancel pending (not-yet-activated) takts
- Re-prioritize takt ordering without aborting the schedule
- Dependency graph recalculation on modification

### F-12: Conflict Detection & Resolution
Detect scheduling conflicts such as resource contention (two actions requiring the same device at the same time) and provide resolution strategies.

- Resource contention detection across concurrent schedules
- Configurable resolution strategies (delay, preempt, queue)
- Conflict reporting via dedicated side effects

---

## Phase 3: Observability & Operations

### F-13: Metrics & Monitoring
Expose engine metrics for operational visibility — event throughput, processing latency, queue depths, active schedules.

- Event counters and processing duration tracking
- Active schedule and takt status summaries
- Pluggable metrics exporter (JMX, Prometheus-compatible)

### F-14: Event Replay & Debugging Tools
Build tooling for replaying event sequences for debugging, testing, and incident analysis.

- Record and replay event sequences from event store
- Step-through mode (process one event at a time with inspection)
- Diff engine state between two points in history
- Export/import event sequences for reproducible scenarios

### F-15: Web Dashboard
Provide a web-based UI for monitoring and interacting with the engine in real time.

- Real-time schedule visualization (active takts, action progress)
- Event stream viewer with filtering
- Manual event injection for testing
- Built with Web Components and vanilla JS (per project guidelines)

---

## Phase 4: Scalability & Resilience

### F-16: Partitioned Processing
Support partitioning event processing by work queue ID so multiple engine instances can process independent queues in parallel.

- Partition-aware event routing
- Independent state per partition
- Partition rebalancing on instance changes

### F-17: Idempotent Event Processing
Ensure events can be safely reprocessed (at-least-once delivery) without duplicating side effects.

- Event deduplication via unique event IDs
- Idempotency keys on side effects
- Safe replay after failure recovery

### F-18: Checkpointing & Recovery
Periodic state checkpoints for fast recovery without full event replay.

- Configurable checkpoint intervals
- Checkpoint storage (local file, external store)
- Startup recovery: load latest checkpoint, replay subsequent events

---

## Conventions

- Each feature gets a branch, tests, and documentation update before merging
- Maintain >90% test coverage
- All domain values use enums or sealed types — no raw strings
- Time is always provided via `TimeEvent`, never `System.currentTimeMillis()`
- Side effects are the only output — processors never call external systems directly
