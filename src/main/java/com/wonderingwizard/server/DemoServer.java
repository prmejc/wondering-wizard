package com.wonderingwizard.server;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.ActionCondition;
import com.wonderingwizard.domain.takt.ActionConditionContext;
import com.wonderingwizard.domain.takt.ActionDependencyCondition;
import com.wonderingwizard.domain.takt.ActionType;
import com.wonderingwizard.domain.takt.EventGateCondition;
import com.wonderingwizard.domain.takt.ConditionContext;
import com.wonderingwizard.domain.takt.DependencyCondition;
import com.wonderingwizard.domain.takt.DeviceType;
import com.wonderingwizard.domain.takt.TaktActivationCondition;
import com.wonderingwizard.domain.takt.TaktCondition;
import com.wonderingwizard.domain.takt.Takt;
import com.wonderingwizard.domain.takt.TimeCondition;
import com.wonderingwizard.engine.Engine;
import com.wonderingwizard.engine.Event;
import com.wonderingwizard.engine.EventProcessingEngine;
import com.wonderingwizard.engine.EventProcessor;
import com.wonderingwizard.engine.EventPropagatingEngine;
import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.kafka.ActionActivatedToEquipmentInstructionMapper;
import com.wonderingwizard.kafka.AssetEventMapper;
import com.wonderingwizard.kafka.KafkaConsumerManager;
import com.wonderingwizard.kafka.KafkaSideEffectPublisher;
import com.wonderingwizard.kafka.WorkInstructionEventMapper;
import com.wonderingwizard.kafka.WorkQueueEventMapper;
import com.wonderingwizard.events.ActionCompletedEvent;
import com.wonderingwizard.events.DigitalMapEvent;
import com.wonderingwizard.events.OverrideActionConditionEvent;
import com.wonderingwizard.events.OverrideConditionEvent;
import com.wonderingwizard.events.SystemTimeSet;
import com.wonderingwizard.events.TimeEvent;
import com.wonderingwizard.events.NukeWorkQueueEvent;
import com.wonderingwizard.events.WorkInstructionEvent;
import com.wonderingwizard.events.WorkQueueMessage;
import com.wonderingwizard.events.WorkQueueStatus;
import com.wonderingwizard.events.CheJobStepState;
import com.wonderingwizard.events.CheStatus;
import com.wonderingwizard.events.ContainerHandlingEquipmentEvent;
import com.wonderingwizard.processors.DelayProcessor;
import com.wonderingwizard.processors.DigitalMapProcessor;
import com.wonderingwizard.processors.EventLogProcessor;
import com.wonderingwizard.processors.RtgWaitDurationStep;
import com.wonderingwizard.processors.ScheduleRunnerProcessor;
import com.wonderingwizard.processors.TTStateProcessor;
import com.wonderingwizard.processors.TimeAlarmProcessor;
import com.wonderingwizard.processors.WorkQueueProcessor;
import com.wonderingwizard.sideeffects.ActionActivated;
import com.wonderingwizard.sideeffects.ActionCompleted;
import com.wonderingwizard.sideeffects.DelayUpdated;
import com.wonderingwizard.sideeffects.ScheduleAborted;
import com.wonderingwizard.sideeffects.ScheduleCreated;
import com.wonderingwizard.sideeffects.TaktActivated;
import com.wonderingwizard.sideeffects.TaktCompleted;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Logger;

/**
 * Embedded HTTP demo server that wraps the event processing engine and exposes a REST API.
 * Uses JDK {@link HttpServer} with zero external dependencies.
 * <p>
 * Tracks numbered steps (user-initiated events) with their resulting side effects,
 * and handles step-back accounting for {@link EventPropagatingEngine} expansion.
 */
public class DemoServer {

    private static final Logger logger = Logger.getLogger(DemoServer.class.getName());

    private final Engine engine;
    private final Settings settings;
    private final ScheduleRunnerProcessor scheduleRunnerProcessor;
    private final TTStateProcessor ttStateProcessor;
    private final List<Step> steps = new ArrayList<>();
    private final Instant initialTime = Instant.EPOCH;
    private Instant currentTime = initialTime;
    private DigitalMapProcessor digitalMapProcessor;
    private HttpServer httpServer;
    private KafkaConsumerManager kafkaConsumerManager;
    private KafkaSideEffectPublisher sideEffectPublisher;
    private final SseConnectionManager sseManager = new SseConnectionManager();
    private static final long BROADCAST_DEBOUNCE_MS = 100;
    private volatile boolean broadcastPending;
    private volatile boolean broadcastLoopRunning;
    private final LinkedBlockingQueue<EngineCommand<?>> eventQueue = new LinkedBlockingQueue<>();
    private volatile boolean eventLoopRunning;

    /**
     * A numbered step representing a user-initiated event and its resulting side effects.
     *
     * @param stepNumber the step number (1-based)
     * @param description a short description of the event
     * @param event the event that was processed
     * @param sideEffects the side effects produced
     */
    public record Step(int stepNumber, String description, Event event,
                       List<SideEffect> sideEffects) {}

    /** Action status for schedule visualization. */
    public enum ActionState {
        PENDING, ACTIVE, COMPLETED
    }

    /** Takt status for schedule visualization. */
    public enum TaktState {
        WAITING, ACTIVE, COMPLETED
    }

    /** Schedule view for the API state response. */
    public record ScheduleView(long workQueueId, boolean active, Instant estimatedMoveTime,
                                long totalDelaySeconds, List<TaktView> takts) {}

    /** Condition evaluation result for the API. */
    public record ConditionView(String id, String type, boolean satisfied, boolean overridden, String explanation) {}

    /** Takt view within a schedule. */
    public record TaktView(String name, TaktState status, Instant plannedStartTime,
                            Instant estimatedStartTime, Instant actualStartTime,
                            Instant completedAt, int durationSeconds,
                            long startDelaySeconds, long taktDelaySeconds,
                            List<ActionView> actions,
                            List<ConditionView> conditions) {}

    /** Action view within a takt. */
    public record ActionView(UUID id, DeviceType deviceType, String description,
                              ActionState status, Set<UUID> dependsOn, int containerIndex,
                              int durationSeconds, int deviceIndex, List<ConditionView> conditions,
                              List<String> containerIds, String cheShortName) {}

    /**
     * A command submitted to the single-threaded event processing queue.
     * HTTP handlers submit commands and block on the future until processing completes.
     */
    private record EngineCommand<T>(Callable<T> task, CompletableFuture<T> future) {}

    /**
     * Submits a task to the single-threaded event processing queue and waits for the result.
     * All engine state mutations go through this queue to guarantee single-threaded processing.
     * When the event loop is not running (e.g. in unit tests), executes the task directly.
     */
    <T> T submitAndWait(Callable<T> task) {
        if (!eventLoopRunning) {
            try {
                return task.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        eventQueue.add(new EngineCommand<>(task, future));
        try {
            return future.get();
        } catch (Exception e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause != null ? cause : e);
        }
    }

    /**
     * Submits a void task to the event processing queue and waits for completion.
     */
    void submitAndWait(Runnable task) {
        submitAndWait(() -> { task.run(); return null; });
    }

    private void startEventLoop() {
        eventLoopRunning = true;
        Thread.ofVirtual().name("event-processing-loop").start(() -> {
            while (eventLoopRunning) {
                try {
                    EngineCommand<?> command = eventQueue.take();
                    executeCommand(command);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    @SuppressWarnings("unchecked")
    private <T> void executeCommand(EngineCommand<T> command) {
        try {
            T result = command.task().call();
            command.future().complete(result);
        } catch (Exception e) {
            command.future().completeExceptionally(e);
        }
    }

    public DemoServer() {
        this(Settings.load());
    }

    public DemoServer(Settings settings) {
        this.settings = settings;
        EventProcessingEngine baseEngine = new EventProcessingEngine();
        this.engine = new EventPropagatingEngine(baseEngine);
        engine.register(new EventLogProcessor());
        engine.register(new TimeAlarmProcessor());
        this.digitalMapProcessor = new DigitalMapProcessor();
        var workQueueProcessor = new WorkQueueProcessor();
        workQueueProcessor.registerStep(digitalMapProcessor);
        workQueueProcessor.registerStep(new RtgWaitDurationStep());
        engine.register(digitalMapProcessor);
        engine.register(workQueueProcessor);
        this.scheduleRunnerProcessor = new ScheduleRunnerProcessor();
        engine.register(scheduleRunnerProcessor);
        engine.register(new DelayProcessor());
        this.ttStateProcessor = new TTStateProcessor();
        engine.register(ttStateProcessor);
        scheduleRunnerProcessor.registerTTAllocationStrategy(ttStateProcessor);
        // Take initial snapshot so we can always reset to clean state
        engine.snapshot();
        snapshotStepIndex = 0;
    }

    DemoServer(Engine engine) {
        this.settings = Settings.load();
        this.engine = engine;
        this.scheduleRunnerProcessor = null;
        this.ttStateProcessor = null;
    }

    /**
     * Starts the HTTP server on the specified port.
     *
     * @param port the port to listen on
     * @throws IOException if the server cannot be started
     */
    public void start(int port) throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.createContext("/", this::handleRoot);
        httpServer.createContext("/api/state", this::handleGetState);
        httpServer.createContext("/api/work-instruction", this::handleWorkInstruction);
        httpServer.createContext("/api/work-queue", this::handleWorkQueue);
        httpServer.createContext("/api/nuke-work-queue", this::handleNukeWorkQueue);
        httpServer.createContext("/api/tick", this::handleTick);
        httpServer.createContext("/api/action-completed", this::handleActionCompleted);
        httpServer.createContext("/api/override-condition", this::handleOverrideCondition);
        httpServer.createContext("/api/override-action-condition", this::handleOverrideActionCondition);
        httpServer.createContext("/api/step-back-to", this::handleStepBackTo);
        httpServer.createContext("/api/snapshot", this::handleSnapshot);
        httpServer.createContext("/api/event-log/export", this::handleExportEventLog);
        httpServer.createContext("/api/event-log/import", this::handleImportEventLog);
        httpServer.createContext("/api/events", this::handleSseConnection);
        httpServer.createContext("/editor", this::handleEditor);
        httpServer.createContext("/workinstructions", this::handleWorkInstructions);
        httpServer.createContext("/workqueues", this::handleWorkQueues);
        httpServer.createContext("/pathfinder", this::handlePathfinder);
        httpServer.createContext("/trucks", this::handleTrucks);
        httpServer.createContext("/api/container-handling-equipment", this::handleContainerHandlingEquipment);
        httpServer.createContext("/proposal2", this::handleProposal2);
        httpServer.createContext("/api/pathfind", this::handlePathfind);
        httpServer.createContext("/api/digitalmap", this::handleDigitalMap);
        httpServer.createContext("/api/standby", this::handleStandby);
        httpServer.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        httpServer.start();
        sseManager.startKeepalive();
        startEventLoop();
        logger.info("Demo server started on port " + port);

        // Set system time to current computer time
        sendSystemTimeSet(Instant.now().truncatedTo(ChronoUnit.SECONDS));

        // Load default digital map
        loadDefaultDigitalMap();

        if (settings.kafkaEnabled()) {
            startKafkaConsumers();
            startSideEffectPublisher();
        } else {
            logger.info("Kafka disabled (kafka.enabled=false)");
        }
    }

    /**
     * Stops the HTTP server.
     */
    public void stop() {
        eventLoopRunning = false;
        sseManager.stop();
        if (sideEffectPublisher != null) {
            sideEffectPublisher.stop();
        }
        if (kafkaConsumerManager != null) {
            kafkaConsumerManager.stopAll();
        }
        if (httpServer != null) {
            httpServer.stop(0);
            logger.info("Demo server stopped");
        }
    }

    private void startKafkaConsumers() {
        // Wrap the engine so Kafka events are recorded as steps in the viewer
        Engine stepRecordingEngine = new Engine() {
            @Override
            public void register(EventProcessor processor) {
                engine.register(processor);
            }

            @Override
            public List<SideEffect> processEvent(Event event) {
                return processStep("Kafka: " + event.getClass().getSimpleName(), event).sideEffects();
            }

            @Override
            public void snapshot() {
                engine.snapshot();
            }

            @Override
            public boolean stepBack() {
                return engine.stepBack();
            }

            @Override
            public int getHistorySize() {
                return engine.getHistorySize();
            }

            @Override
            public void clearHistory() {
                engine.clearHistory();
            }
        };

        kafkaConsumerManager = new KafkaConsumerManager(settings.kafkaConfiguration(), stepRecordingEngine);
        kafkaConsumerManager.register(
                settings.workQueueConsumerConfiguration(),
                new WorkQueueEventMapper()
        );
        kafkaConsumerManager.register(
                settings.workInstructionConsumerConfiguration(),
                new WorkInstructionEventMapper()
        );
        AssetEventMapper assetEventMapper = new AssetEventMapper();
        kafkaConsumerManager.registerJson(
                settings.assetEventRtgConsumerConfiguration(),
                assetEventMapper
        );
        kafkaConsumerManager.registerJson(
                settings.assetEventQcConsumerConfiguration(),
                assetEventMapper
        );
        kafkaConsumerManager.registerJson(
                settings.assetEventEhConsumerConfiguration(),
                assetEventMapper
        );
        kafkaConsumerManager.startAll();
    }

    private void startSideEffectPublisher() {
        sideEffectPublisher = new KafkaSideEffectPublisher(settings.kafkaConfiguration());
        sideEffectPublisher.registerMapper(
                ActionActivated.class,
                settings.equipmentInstructionRtgTopic(),
                new ActionActivatedToEquipmentInstructionMapper(settings.terminalCode(),
                        Set.of(ActionType.RTG_DRIVE, ActionType.RTG_FETCH,
                                ActionType.RTG_HANDOVER_TO_TT, ActionType.RTG_LIFT_FROM_TT,
                                ActionType.RTG_PLACE_ON_YARD))
        );
        sideEffectPublisher.registerMapper(
                ActionActivated.class,
                settings.equipmentInstructionTtTopic(),
                new ActionActivatedToEquipmentInstructionMapper(settings.terminalCode(),
                        Set.of(ActionType.TT_DRIVE_TO_RTG_PULL, ActionType.TT_DRIVE_TO_RTG_STANDBY,
                                ActionType.TT_DRIVE_TO_RTG_UNDER, ActionType.TT_HANDOVER_FROM_RTG,
                                ActionType.TT_DRIVE_TO_QC_PULL, ActionType.TT_DRIVE_TO_QC_STANDBY,
                                ActionType.TT_DRIVE_UNDER_QC, ActionType.TT_HANDOVER_TO_QC,
                                ActionType.TT_HANDOVER_FROM_QC, ActionType.TT_HANDOVER_TO_RTG,
                                ActionType.TT_DRIVE_TO_BUFFER))
        );
        sideEffectPublisher.registerMapper(
                ActionActivated.class,
                settings.equipmentInstructionQcTopic(),
                new ActionActivatedToEquipmentInstructionMapper(settings.terminalCode(),
                        Set.of(ActionType.QC_LIFT, ActionType.QC_PLACE))
        );
        sideEffectPublisher.start();
    }

    /**
     * Sends a SystemTimeSet event, updating the system clock.
     *
     * @param timestamp the time to set
     */
    public void sendSystemTimeSet(Instant timestamp) {
        processStep("System time set", new SystemTimeSet(timestamp));
    }

    private void loadDefaultDigitalMap() {
        try (InputStream is = getClass().getResourceAsStream("/digitalmap.json")) {
            if (is == null) {
                logger.info("No default digital map found (digitalmap.json not in resources)");
                return;
            }
            String mapJson = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            processStep("Load default digital map", new DigitalMapEvent(mapJson));
            logger.info("Default digital map loaded");
        } catch (IOException e) {
            logger.warning("Failed to load default digital map: " + e.getMessage());
        }
    }

    /**
     * Processes an event and records it as a numbered step.
     *
     * @param description short description of the event
     * @param event the event to process
     * @return the list of side effects produced
     */
    /** Result of processing a step, returned to HTTP handlers. */
    record StepResult(Step step, List<SideEffect> sideEffects) {}

    public StepResult processStep(String description, Event event) {
        return submitAndWait(() -> processStepInternal(description, event));
    }

    /**
     * Core event processing logic. Must only be called from the event processing thread.
     */
    private StepResult processStepInternal(String description, Event event) {
        // Handle SystemTimeSet: update currentTime
        if (event instanceof SystemTimeSet sts) {
            currentTime = sts.timestamp();
        }

        List<SideEffect> sideEffects = engine.processEvent(event);

        int stepNumber = steps.size() + 1;
        Step step = new Step(stepNumber, description, event, sideEffects);
        steps.add(step);

        // Publish on a separate thread
        if (sideEffectPublisher != null) {
            List<SideEffect> toPublish = List.copyOf(sideEffects);
            Thread.ofVirtual().name("kafka-publish").start(() -> sideEffectPublisher.publish(toPublish));
        }

        broadcastState();

        return new StepResult(step, sideEffects);
    }

    /**
     * Creates an explicit snapshot of the current engine state.
     * The snapshot is associated with the current step count so that
     * step-back can restore it and replay events from that point.
     */
    public void createSnapshot() {
        submitAndWait(() -> {
            engine.snapshot();
            snapshotStepIndex = steps.size();
            logger.info("Snapshot created at step " + snapshotStepIndex);
        });
    }

    /**
     * Resets the engine to its initial state (before any events).
     * Uses the initial snapshot taken at startup.
     */
    private void resetToInitial() {
        // Restore initial snapshot
        engine.stepBack();
        steps.clear();
        currentTime = initialTime;
        snapshotStepIndex = 0;
        // Re-take snapshot at step 0 for future resets
        engine.snapshot();
    }

    /** Step index at which the most recent snapshot was taken (-1 = none). */
    private int snapshotStepIndex = -1;

    /**
     * Signals that a state broadcast is needed. Called from the event processing thread.
     * The actual serialization and broadcast happen on a separate debounce thread.
     */
    private void broadcastState() {
        broadcastPending = true;
        if (!broadcastLoopRunning) {
            broadcastLoopRunning = true;
            Thread.ofVirtual().name("sse-debounce").start(this::broadcastLoop);
        }
    }

    private void broadcastLoop() {
        try {
            while (broadcastPending) {
                broadcastPending = false;
                try {
                    Thread.sleep(BROADCAST_DEBOUNCE_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                // Serialize state on the event processing thread, then broadcast on this thread
                String json = submitAndWait(() -> JsonSerializer.serialize(buildState()));
                sseManager.broadcast("state", json);
            }
        } finally {
            broadcastLoopRunning = false;
            // If a broadcast was requested while we were shutting down, restart
            if (broadcastPending) {
                broadcastState();
            }
        }
    }

    /**
     * Steps back to the target step number by restoring the nearest snapshot
     * and replaying events from that point.
     *
     * @param targetStep the step number to revert to (1-based, 0 means undo all)
     * @return true if step-back was successful
     */
    public boolean stepBackTo(int targetStep) {
        return submitAndWait(() -> {
            if (targetStep < 0 || targetStep >= steps.size()) {
                return false;
            }
            if (snapshotStepIndex < 0 || targetStep < snapshotStepIndex) {
                logger.warning("Cannot step back to step " + targetStep
                        + ": no snapshot or target is before snapshot at step " + snapshotStepIndex);
                return false;
            }

            // Save the events we need to replay (from snapshot to target)
            List<Step> stepsToReplay = new ArrayList<>();
            for (int i = snapshotStepIndex; i < targetStep; i++) {
                stepsToReplay.add(steps.get(i));
            }

            // Restore the snapshot
            engine.stepBack();

            // Clear all steps after snapshot
            while (steps.size() > snapshotStepIndex) {
                steps.remove(steps.size() - 1);
            }

            // Replay events from snapshot up to target (without creating new snapshots)
            for (Step step : stepsToReplay) {
                if (step.event() instanceof SystemTimeSet sts) {
                    currentTime = sts.timestamp();
                }
                List<SideEffect> sideEffects = engine.processEvent(step.event());
                steps.add(new Step(steps.size() + 1, step.description(), step.event(), sideEffects));
            }

            // Re-take the snapshot at current position so further step-backs work
            engine.snapshot();
            snapshotStepIndex = steps.size();

            return true;
        });
    }

    /**
     * Builds the current state for the API response, including schedules with action statuses.
     *
     * @return the state as a JSON-serializable map
     */
    public Map<String, Object> getState() {
        return submitAndWait(() -> buildState());
    }

    private static final int MAX_STEPS_IN_RESPONSE = 300;
    private static final int DEFAULT_SCHEDULE_PAGE_SIZE = 5;

    /**
     * Builds the state map. Must only be called from the event processing thread.
     */
    private Map<String, Object> buildState() {
        return buildState(0, DEFAULT_SCHEDULE_PAGE_SIZE);
    }

    /**
     * Builds the state map with pagination. Must only be called from the event processing thread.
     *
     * @param schedulePage zero-based page index for schedules
     * @param schedulePageSize number of schedules per page
     */
    private Map<String, Object> buildState(int schedulePage, int schedulePageSize) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("currentTime", currentTime);
        state.put("snapshotStep", snapshotStepIndex);
        state.put("totalSteps", steps.size());

        // Only include the last MAX_STEPS_IN_RESPONSE steps
        if (steps.size() <= MAX_STEPS_IN_RESPONSE) {
            state.put("steps", steps);
        } else {
            state.put("steps", steps.subList(steps.size() - MAX_STEPS_IN_RESPONSE, steps.size()));
        }

        // Paginate schedules
        List<ScheduleView> allSchedules = buildScheduleViews();
        state.put("totalSchedules", allSchedules.size());
        state.put("schedulePage", schedulePage);
        state.put("schedulePageSize", schedulePageSize);

        int from = Math.min(schedulePage * schedulePageSize, allSchedules.size());
        int to = Math.min(from + schedulePageSize, allSchedules.size());
        state.put("schedules", allSchedules.subList(from, to));

        // Include TT (truck) state
        if (ttStateProcessor != null) {
            state.put("trucks", ttStateProcessor.getTruckState());
        }

        return state;
    }

    // Visible for testing
    Instant getCurrentTime() {
        return currentTime;
    }

    // Visible for testing
    List<Step> getSteps() {
        return List.copyOf(steps);
    }

    private List<ScheduleView> buildScheduleViews() {
        // Build schedule structure from ScheduleCreated side effects,
        // then query ScheduleRunnerProcessor for authoritative action/takt states
        Map<Long, ScheduleViewBuilder> builders = new LinkedHashMap<>();

        for (Step step : steps) {
            for (SideEffect se : step.sideEffects()) {
                switch (se) {
                    case ScheduleCreated created -> {
                        long wqId = created.workQueueId();
                        ScheduleViewBuilder builder = new ScheduleViewBuilder(wqId, true, created.estimatedMoveTime());
                        builder.hasTTAllocation = scheduleRunnerProcessor != null
                                && scheduleRunnerProcessor.hasTTAllocationStrategy();
                        for (Takt takt : created.takts()) {
                            List<ActionView> actionViews = new ArrayList<>();
                            for (Action originalAction : takt.actions()) {
                                // Get authoritative action (may have truck assignment)
                                Action action = scheduleRunnerProcessor != null
                                        ? scheduleRunnerProcessor.getAction(wqId, originalAction.id())
                                        : originalAction;
                                if (action == null) action = originalAction;
                                List<String> cIds = action.workInstructions().stream()
                                    .map(wi -> wi.containerId() != null ? wi.containerId() : "")
                                    .filter(id -> !id.isEmpty())
                                    .toList();
                                // Get action status from processor's authoritative state
                                ActionState actionState = scheduleRunnerProcessor != null
                                        ? mapActionStatus(scheduleRunnerProcessor.getActionStatus(wqId, action.id()))
                                        : ActionState.PENDING;
                                actionViews.add(new ActionView(
                                        action.id(), action.deviceType(), action.description(),
                                        actionState, action.dependsOn(), action.containerIndex(),
                                        action.durationSeconds(), action.deviceIndex(), List.of(), cIds,
                                        action.cheShortName()));
                            }
                            // Get takt status from processor's authoritative state
                            TaktState taktState = scheduleRunnerProcessor != null
                                    ? mapTaktState(scheduleRunnerProcessor.getTaktState(wqId, takt.name()))
                                    : TaktState.WAITING;
                            Instant actualStart = scheduleRunnerProcessor != null
                                    ? scheduleRunnerProcessor.getActualStartTime(wqId, takt.name())
                                    : null;
                            builder.takts.add(new TaktView(takt.name(), taktState,
                                    takt.plannedStartTime(), takt.estimatedStartTime(), actualStart,
                                    null, takt.durationSeconds(), 0, 0, actionViews, List.of()));
                        }
                        builder.storeTakts(created.takts());
                        // Populate satisfied event gates from processor
                        if (scheduleRunnerProcessor != null) {
                            for (Takt takt : created.takts()) {
                                for (Action action : takt.actions()) {
                                    Set<String> gates = scheduleRunnerProcessor.getSatisfiedEventGates(wqId, action.id());
                                    if (!gates.isEmpty()) {
                                        builder.satisfiedEventGates.put(action.id(), new HashSet<>(gates));
                                    }
                                }
                            }
                        }
                        builders.put(wqId, builder);
                    }
                    case ScheduleAborted aborted ->
                            builders.remove(aborted.workQueueId());
                    case DelayUpdated delayUpdated -> {
                        ScheduleViewBuilder builder = builders.get(delayUpdated.workQueueId());
                        if (builder != null) {
                            builder.totalDelaySeconds = delayUpdated.totalDelaySeconds();
                        }
                    }
                    default -> { /* Action/takt state is queried from processor directly */ }
                }
            }
            // Track override events (they are events, not side effects)
            if (step.event() instanceof OverrideConditionEvent override) {
                ScheduleViewBuilder builder = builders.get(override.workQueueId());
                if (builder != null) {
                    builder.addOverride(override.taktName(), override.conditionId());
                }
            }
            if (step.event() instanceof OverrideActionConditionEvent override) {
                ScheduleViewBuilder builder = builders.get(override.workQueueId());
                if (builder != null) {
                    builder.addActionOverride(override.actionId(), override.conditionId());
                }
            }
        }

        return builders.values().stream()
                .map(b -> b.build(currentTime))
                .toList();
    }

    private static ActionState mapActionStatus(ScheduleRunnerProcessor.ActionStatus status) {
        return switch (status) {
            case COMPLETED -> ActionState.COMPLETED;
            case ACTIVE -> ActionState.ACTIVE;
            case PENDING -> ActionState.PENDING;
        };
    }

    private static TaktState mapTaktState(ScheduleRunnerProcessor.TaktState state) {
        return switch (state) {
            case COMPLETED -> TaktState.COMPLETED;
            case ACTIVE -> TaktState.ACTIVE;
            case WAITING -> TaktState.WAITING;
        };
    }

    private static class ScheduleViewBuilder {
        final long workQueueId;
        final boolean active;
        final Instant estimatedMoveTime;
        final List<TaktView> takts = new ArrayList<>();
        final Map<UUID, ActionState> actionStates = new HashMap<>();
        final Map<String, TaktState> taktStates = new HashMap<>();
        final Map<String, Instant> actualStartTimes = new HashMap<>();
        final Map<String, Instant> completedAtTimes = new HashMap<>();
        long totalDelaySeconds = 0;
        /** Original takt data for building conditions. */
        List<Takt> originalTakts = List.of();
        /** Overridden conditions per takt name. */
        final Map<String, Set<String>> overriddenConditions = new HashMap<>();
        /** Overridden action conditions per action UUID. */
        final Map<UUID, Set<String>> overriddenActionConditions = new HashMap<>();
        /** Satisfied event gate condition IDs per action UUID. */
        final Map<UUID, Set<String>> satisfiedEventGates = new HashMap<>();
        /** Whether a TT allocation strategy is registered. */
        boolean hasTTAllocation = false;

        ScheduleViewBuilder(long workQueueId, boolean active, Instant estimatedMoveTime) {
            this.workQueueId = workQueueId;
            this.active = active;
            this.estimatedMoveTime = estimatedMoveTime;
        }

        void storeTakts(List<Takt> takts) {
            this.originalTakts = takts;
        }

        void setActionStatus(UUID actionId, ActionState status) {
            actionStates.put(actionId, status);
        }

        void setTaktStatus(String taktName, TaktState status) {
            taktStates.put(taktName, status);
        }

        void setActualStartTime(String taktName, Instant time) {
            actualStartTimes.put(taktName, time);
        }

        void addOverride(String taktName, String conditionId) {
            overriddenConditions.computeIfAbsent(taktName, k -> new HashSet<>()).add(conditionId);
        }

        void addActionOverride(UUID actionId, String conditionId) {
            overriddenActionConditions.computeIfAbsent(actionId, k -> new HashSet<>()).add(conditionId);
        }

        void setCompletedAt(String taktName, Instant time) {
            completedAtTimes.put(taktName, time);
        }

        ScheduleView build(Instant currentTime) {
            // Build action lookup for dependency descriptions
            Map<UUID, String> actionDescriptions = new HashMap<>();
            for (Takt takt : originalTakts) {
                for (Action action : takt.actions()) {
                    actionDescriptions.put(action.id(), action.description());
                }
            }

            // Build completed action IDs from takt action views
            Set<UUID> completedActionIds = new HashSet<>();
            for (TaktView takt : takts) {
                for (ActionView action : takt.actions()) {
                    if (action.status() == ActionState.COMPLETED) {
                        completedActionIds.add(action.id());
                    }
                }
            }

            ConditionContext context = new ConditionContext(currentTime, completedActionIds);

            List<TaktView> updatedTakts = new ArrayList<>();
            for (int i = 0; i < takts.size(); i++) {
                TaktView takt = takts.get(i);
                TaktState taktState = taktStates.getOrDefault(takt.name(), takt.status());
                Instant actualStartTime = actualStartTimes.getOrDefault(takt.name(), takt.actualStartTime());
                Instant completedAt = completedAtTimes.getOrDefault(takt.name(), takt.completedAt());

                // Calculate per-takt delay info
                long startDelay = 0;
                long taktDelay = 0;
                if (actualStartTime != null && takt.plannedStartTime() != null) {
                    startDelay = Math.max(0,
                            Duration.between(takt.plannedStartTime(), actualStartTime).getSeconds());
                }
                if (completedAt != null && actualStartTime != null) {
                    taktDelay = Math.max(0,
                            Duration.between(actualStartTime, completedAt).getSeconds()
                                    - takt.durationSeconds());
                }

                // Update estimated start time for waiting takts based on total delay
                Instant updatedEstimatedStart = takt.estimatedStartTime();
                if (taktState == TaktState.WAITING && totalDelaySeconds > 0
                        && takt.estimatedStartTime() != null) {
                    updatedEstimatedStart = takt.plannedStartTime()
                            .plusSeconds(totalDelaySeconds);
                }

                List<ActionView> updatedActions = new ArrayList<>();
                Takt originalTakt = i < originalTakts.size() ? originalTakts.get(i) : null;
                for (ActionView action : takt.actions()) {
                    ActionState actionState = actionStates.getOrDefault(action.id(), action.status());
                    List<ConditionView> actionConditions = List.of();
                    if (actionState == ActionState.PENDING && originalTakt != null) {
                        actionConditions = buildActionConditionViews(
                                action, originalTakt, taktState, completedActionIds, actionDescriptions,
                                overriddenActionConditions.getOrDefault(action.id(), Set.of()),
                                satisfiedEventGates.getOrDefault(action.id(), Set.of()));
                    }
                    updatedActions.add(new ActionView(
                            action.id(), action.deviceType(), action.description(),
                            actionState, action.dependsOn(), action.containerIndex(),
                            action.durationSeconds(), action.deviceIndex(), actionConditions,
                            action.containerIds(), action.cheShortName()));
                }

                // Build conditions for WAITING takts
                List<ConditionView> conditionViews = List.of();
                if (taktState == TaktState.WAITING && i < originalTakts.size()) {
                    conditionViews = buildConditionViews(originalTakts.get(i), context,
                            actionDescriptions,
                            overriddenConditions.getOrDefault(takt.name(), Set.of()));
                }

                updatedTakts.add(new TaktView(takt.name(), taktState,
                        takt.plannedStartTime(), updatedEstimatedStart, actualStartTime,
                        completedAt, takt.durationSeconds(),
                        startDelay, taktDelay,
                        updatedActions, conditionViews));
            }
            return new ScheduleView(workQueueId, active, estimatedMoveTime,
                    totalDelaySeconds, updatedTakts);
        }

        private List<ConditionView> buildActionConditionViews(
                ActionView action, Takt takt, TaktState taktState,
                Set<UUID> completedActionIds, Map<UUID, String> actionDescriptions,
                Set<String> overrides, Set<String> satisfiedGateIds) {
            List<ConditionView> views = new ArrayList<>();

            // Determine if this is a first action (no intra-takt dependencies)
            Set<UUID> taktActionIds = new HashSet<>();
            for (Action a : takt.actions()) {
                taktActionIds.add(a.id());
            }

            Set<UUID> intraTaktDeps = new HashSet<>();
            for (UUID depId : action.dependsOn() != null ? action.dependsOn() : Set.<UUID>of()) {
                if (taktActionIds.contains(depId)) {
                    intraTaktDeps.add(depId);
                }
            }

            boolean isFirstAction = intraTaktDeps.isEmpty();
            ActionConditionContext context = new ActionConditionContext(
                    taktState == TaktState.ACTIVE, completedActionIds, satisfiedGateIds);

            if (isFirstAction) {
                // First action: condition is takt activation
                TaktActivationCondition cond = new TaktActivationCondition(takt.name());
                boolean overridden = overrides.contains(cond.id());
                boolean satisfied = overridden || cond.evaluate(context);
                views.add(new ConditionView(cond.id(), cond.type(),
                        satisfied, overridden,
                        satisfied ? null : cond.explanation(context)));
            } else {
                // Non-first action: condition is dependency completion
                Map<UUID, String> depDescs = new HashMap<>();
                for (UUID depId : intraTaktDeps) {
                    depDescs.put(depId, actionDescriptions.getOrDefault(depId,
                            depId.toString().substring(0, 8)));
                }
                ActionDependencyCondition cond = new ActionDependencyCondition(intraTaktDeps, depDescs);
                boolean overridden = overrides.contains(cond.id());
                boolean satisfied = overridden || cond.evaluate(context);
                views.add(new ConditionView(cond.id(), cond.type(),
                        satisfied, overridden,
                        satisfied ? null : cond.explanation(context)));
            }

            // Event gate conditions
            Action originalAction = takt.actions().stream()
                    .filter(a -> a.id().equals(action.id()))
                    .findFirst().orElse(null);
            if (originalAction != null) {
                for (EventGateCondition gate : originalAction.eventGates()) {
                    boolean overridden = overrides.contains(gate.id());
                    boolean satisfied = overridden || gate.evaluate(context);
                    views.add(new ConditionView(gate.id(), gate.type(),
                            satisfied, overridden,
                            satisfied ? null : gate.explanation(context)));
                }
            }

            // TT allocation condition: TT actions need a truck assigned
            if (action.deviceType() == DeviceType.TT && hasTTAllocation) {
                boolean hasTruck = action.cheShortName() != null;
                views.add(new ConditionView("tt-allocation", "TT_ALLOCATION",
                        hasTruck, false,
                        hasTruck ? "Assigned: " + action.cheShortName()
                                : "Waiting for free TT (FES pool)"));
            }

            return views;
        }

        private List<ConditionView> buildConditionViews(Takt takt, ConditionContext context,
                                                         Map<UUID, String> actionDescriptions,
                                                         Set<String> overrides) {
            List<ConditionView> views = new ArrayList<>();

            // Time condition
            if (takt.estimatedStartTime() != null) {
                TimeCondition timeCond = new TimeCondition(takt.estimatedStartTime());
                boolean overridden = overrides.contains(timeCond.id());
                boolean satisfied = overridden || timeCond.evaluate(context);
                views.add(new ConditionView(timeCond.id(), timeCond.type(),
                        satisfied, overridden,
                        satisfied ? null : timeCond.explanation(context)));
            }

            // Dependency condition
            if (!takt.actions().isEmpty()) {
                Set<UUID> taktActionIds = new HashSet<>();
                for (Action action : takt.actions()) {
                    taktActionIds.add(action.id());
                }

                Map<String, Set<UUID>> externalDeps = new LinkedHashMap<>();
                Map<UUID, String> depDescs = new HashMap<>();

                for (Action action : takt.actions()) {
                    boolean hasIntraTaktDep = action.dependsOn().stream()
                            .anyMatch(taktActionIds::contains);
                    if (hasIntraTaktDep) continue;

                    Set<UUID> extDeps = new HashSet<>();
                    for (UUID depId : action.dependsOn()) {
                        if (!taktActionIds.contains(depId)) {
                            extDeps.add(depId);
                            depDescs.put(depId, actionDescriptions.getOrDefault(depId,
                                    depId.toString().substring(0, 8)));
                        }
                    }
                    if (!extDeps.isEmpty()) {
                        externalDeps.put(action.description(), extDeps);
                    }
                }

                if (!externalDeps.isEmpty()) {
                    DependencyCondition depCond = new DependencyCondition(externalDeps, depDescs);
                    boolean overridden = overrides.contains(depCond.id());
                    boolean satisfied = overridden || depCond.evaluate(context);
                    views.add(new ConditionView(depCond.id(), depCond.type(),
                            satisfied, overridden,
                            satisfied ? null : depCond.explanation(context)));
                }
            }

            return views;
        }
    }

    // --- HTTP Handlers ---

    private void handleSseConnection(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        sseManager.addConnection(exchange);

        // Send current state immediately so the client is up to date
        sseManager.broadcast("state", JsonSerializer.serialize(getState()));
    }

    private void handleRoot(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        try (InputStream is = getClass().getResourceAsStream("/index.html")) {
            if (is == null) {
                sendResponse(exchange, 404, "Schedule viewer not found");
                return;
            }
            byte[] html = is.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, html.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(html);
            }
        }
    }

    private void handleEditor(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        try (InputStream is = getClass().getResourceAsStream("/editor.html")) {
            if (is == null) {
                sendResponse(exchange, 404, "Editor not found");
                return;
            }
            byte[] html = is.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, html.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(html);
            }
        }
    }

    private void handleWorkInstructions(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        try (InputStream is = getClass().getResourceAsStream("/workinstructions.html")) {
            if (is == null) {
                sendResponse(exchange, 404, "Work instructions page not found");
                return;
            }
            byte[] html = is.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, html.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(html);
            }
        }
    }

    private void handleTrucks(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        try (InputStream is = getClass().getResourceAsStream("/trucks.html")) {
            if (is == null) {
                sendResponse(exchange, 404, "Trucks page not found");
                return;
            }
            byte[] html = is.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, html.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(html);
            }
        }
    }

    private void handleContainerHandlingEquipment(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        try {
            Map<String, String> body = readJsonBody(exchange);
            String eventType = body.getOrDefault("eventType", "");
            Long cheId = body.get("cheId") != null && !body.get("cheId").isEmpty()
                    ? Long.parseLong(body.get("cheId")) : null;
            String opType = body.getOrDefault("opType", "");
            String cdhTerminalCode = body.getOrDefault("cdhTerminalCode", "");
            Long messageSequenceNumber = body.get("messageSequenceNumber") != null
                    && !body.get("messageSequenceNumber").isEmpty()
                    ? Long.parseLong(body.get("messageSequenceNumber")) : null;
            String cheShortName = requireField(body, "cheShortName");
            String cheStatusStr = body.getOrDefault("cheStatus", "");
            CheStatus cheStatus = cheStatusStr.isEmpty() ? null : CheStatus.fromDisplayName(cheStatusStr);
            String cheKind = body.getOrDefault("cheKind", "TT");
            Long chePoolId = body.get("chePoolId") != null && !body.get("chePoolId").isEmpty()
                    ? Long.parseLong(body.get("chePoolId")) : null;
            String cheJobStepStateStr = body.getOrDefault("cheJobStepState", "");
            CheJobStepState cheJobStepState = cheJobStepStateStr.isEmpty() ? null : CheJobStepState.fromCode(cheJobStepStateStr);
            Long sourceTsMs = body.get("sourceTsMs") != null && !body.get("sourceTsMs").isEmpty()
                    ? Long.parseLong(body.get("sourceTsMs")) : null;

            ContainerHandlingEquipmentEvent event = new ContainerHandlingEquipmentEvent(
                    eventType, cheId, opType, cdhTerminalCode, messageSequenceNumber,
                    cheShortName, cheStatus, cheKind, chePoolId, cheJobStepState, sourceTsMs);
            StepResult result = processStep("CHE: " + cheShortName, event);

            sendJsonResponse(exchange, 200, JsonSerializer.serialize(
                    Map.of("step", result.step(), "sideEffects", result.sideEffects())));
        } catch (Exception e) {
            sendJsonResponse(exchange, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private void handleWorkQueues(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        try (InputStream is = getClass().getResourceAsStream("/workqueues.html")) {
            if (is == null) {
                sendResponse(exchange, 404, "Work queues page not found");
                return;
            }
            byte[] html = is.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, html.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(html);
            }
        }
    }

    private void handlePathfinder(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        try (InputStream is = getClass().getResourceAsStream("/pathfinder.html")) {
            if (is == null) {
                sendResponse(exchange, 404, "Pathfinder page not found");
                return;
            }
            byte[] html = is.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, html.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(html);
            }
        }
    }

    private void handleProposal2(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        try (InputStream is = getClass().getResourceAsStream("/proposal2.html")) {
            if (is == null) {
                sendResponse(exchange, 404, "Proposal 2 page not found");
                return;
            }
            byte[] html = is.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, html.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(html);
            }
        }
    }

    private void handlePathfind(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        String query = exchange.getRequestURI().getQuery();
        if (query == null) {
            sendResponse(exchange, 400, "{\"error\":\"Missing query parameters 'from' and 'to'\"}");
            return;
        }

        String from = null;
        String to = null;
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2) {
                String key = java.net.URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
                String value = java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                if ("from".equals(key)) from = value;
                if ("to".equals(key)) to = value;
            }
        }

        if (from == null || from.isEmpty() || to == null || to.isEmpty()) {
            sendResponse(exchange, 400, "{\"error\":\"Both 'from' and 'to' parameters are required\"}");
            return;
        }

        if (!digitalMapProcessor.isMapLoaded()) {
            sendResponse(exchange, 503, "{\"error\":\"No digital map loaded\"}");
            return;
        }

        int duration = digitalMapProcessor.findPathDuration(from, to);

        String json = "{\"from\":" + JsonSerializer.serialize(from)
                + ",\"to\":" + JsonSerializer.serialize(to)
                + ",\"durationSeconds\":" + duration
                + ",\"found\":" + (duration >= 0) + "}";

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        sendResponse(exchange, 200, json);
    }

    private void handleDigitalMap(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        if (!digitalMapProcessor.isMapLoaded()) {
            sendResponse(exchange, 503, "{\"error\":\"No digital map loaded\"}");
            return;
        }

        var sb = new StringBuilder();
        sb.append("{\"pois\":[");
        var poiList = digitalMapProcessor.getPois();
        for (int i = 0; i < poiList.size(); i++) {
            var poi = poiList.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"name\":").append(JsonSerializer.serialize(poi.name()))
              .append(",\"lat\":").append(poi.lat())
              .append(",\"lon\":").append(poi.lon()).append('}');
        }
        sb.append("],\"roads\":[");
        var roads = digitalMapProcessor.getRoadSegments();
        for (int i = 0; i < roads.size(); i++) {
            var seg = roads.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"lat1\":").append(seg.lat1())
              .append(",\"lon1\":").append(seg.lon1())
              .append(",\"lat2\":").append(seg.lat2())
              .append(",\"lon2\":").append(seg.lon2())
              .append(",\"speed\":").append(seg.speedKmh())
              .append(",\"oneway\":").append(seg.oneway()).append('}');
        }
        sb.append("]}");

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        sendResponse(exchange, 200, sb.toString());
    }

    private void handleStandby(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        String query = exchange.getRequestURI().getQuery();
        if (query == null) {
            sendResponse(exchange, 400, "{\"error\":\"Missing query parameter 'position'\"}");
            return;
        }

        String position = null;
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2) {
                String key = java.net.URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
                String value = java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                if ("position".equals(key)) position = value;
            }
        }

        if (position == null || position.isEmpty()) {
            sendResponse(exchange, 400, "{\"error\":\"'position' parameter is required\"}");
            return;
        }

        if (!digitalMapProcessor.isMapLoaded()) {
            sendResponse(exchange, 503, "{\"error\":\"No digital map loaded\"}");
            return;
        }

        String standby = digitalMapProcessor.findStandbyLocation(position);

        String json;
        if (standby == null) {
            json = "{\"position\":" + JsonSerializer.serialize(position)
                    + ",\"found\":false}";
        } else {
            json = "{\"position\":" + JsonSerializer.serialize(position)
                    + ",\"standby\":" + JsonSerializer.serialize(standby)
                    + ",\"found\":true}";
        }

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        sendResponse(exchange, 200, json);
    }

    private void handleGetState(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }
        Map<String, String> params = parseQueryParams(exchange.getRequestURI().getQuery());
        int schedulePage = Integer.parseInt(params.getOrDefault("schedulePage", "0"));
        int schedulePageSize = Integer.parseInt(params.getOrDefault("schedulePageSize",
                String.valueOf(DEFAULT_SCHEDULE_PAGE_SIZE)));
        String json = submitAndWait(() -> JsonSerializer.serialize(
                buildState(schedulePage, schedulePageSize)));
        sendJsonResponse(exchange, 200, json);
    }

    private static Map<String, String> parseQueryParams(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isBlank()) return params;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                params.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return params;
    }

    private void handleWorkInstruction(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        try {
            Map<String, String> body = readJsonBody(exchange);
            String eventType = body.getOrDefault("eventType", "");
            long workInstructionId = Long.parseLong(requireField(body, "workInstructionId"));
            long workQueueId = Long.parseLong(requireField(body, "workQueueId"));
            String fetchChe = body.getOrDefault("fetchChe", "");
            String workInstructionMoveStage = body.containsKey("workInstructionMoveStage")
                    ? body.get("workInstructionMoveStage")
                    : body.getOrDefault("status", "Planned");
            String estimatedMoveTimeStr = body.get("estimatedMoveTime");
            Instant estimatedMoveTime = estimatedMoveTimeStr != null
                    ? Instant.parse(estimatedMoveTimeStr) : null;

            String estimatedCycleTimeStr = body.getOrDefault("estimatedCycleTimeSeconds", "0");
            int estimatedCycleTimeSeconds = Integer.parseInt(estimatedCycleTimeStr);

            String estimatedRtgCycleTimeStr = body.getOrDefault("estimatedRtgCycleTimeSeconds", "60");
            int estimatedRtgCycleTimeSeconds = Integer.parseInt(estimatedRtgCycleTimeStr);

            String putChe = body.getOrDefault("putChe", "");
            boolean isTwinFetch = Boolean.parseBoolean(body.getOrDefault("isTwinFetch", "false"));
            boolean isTwinPut = Boolean.parseBoolean(body.getOrDefault("isTwinPut", "false"));
            boolean isTwinCarry = Boolean.parseBoolean(body.getOrDefault("isTwinCarry", "false"));
            long twinCompanionWorkInstruction = Long.parseLong(body.getOrDefault("twinCompanionWorkInstruction", "0"));
            String toPosition = body.getOrDefault("toPosition", "");
            String containerId = body.getOrDefault("containerId", "");

            WorkInstructionEvent event = new WorkInstructionEvent(
                    eventType, workInstructionId, workQueueId, fetchChe, workInstructionMoveStage, estimatedMoveTime,
                    estimatedCycleTimeSeconds, estimatedRtgCycleTimeSeconds,
                    putChe, isTwinFetch, isTwinPut, isTwinCarry, twinCompanionWorkInstruction,
                    toPosition, containerId);
            StepResult result = processStep("WorkInstruction: " + workInstructionId, event);

            sendJsonResponse(exchange, 200, JsonSerializer.serialize(
                    Map.of("step", result.step(), "sideEffects", result.sideEffects())));
        } catch (Exception e) {
            sendJsonResponse(exchange, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private void handleWorkQueue(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        try {
            Map<String, String> body = readJsonBody(exchange);
            long workQueueId = Long.parseLong(requireField(body, "workQueueId"));
            String statusStr = requireField(body, "status");
            WorkQueueStatus status = WorkQueueStatus.valueOf(statusStr);

            String qcMudaStr = body.getOrDefault("qcMudaSeconds", "0");
            int qcMudaSeconds = Integer.parseInt(qcMudaStr);

            String loadModeStr = body.get("loadMode");
            com.wonderingwizard.events.LoadMode loadMode = loadModeStr != null
                    ? com.wonderingwizard.events.LoadMode.valueOf(loadModeStr)
                    : null;

            WorkQueueMessage event = new WorkQueueMessage(workQueueId, status, qcMudaSeconds, loadMode);
            StepResult result = processStep("WorkQueue " + statusStr + ": " + workQueueId, event);

            sendJsonResponse(exchange, 200, JsonSerializer.serialize(
                    Map.of("step", result.step(), "sideEffects", result.sideEffects())));
        } catch (Exception e) {
            sendJsonResponse(exchange, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private void handleNukeWorkQueue(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        try {
            Map<String, String> body = readJsonBody(exchange);
            long workQueueId = Long.parseLong(requireField(body, "workQueueId"));

            NukeWorkQueueEvent event = new NukeWorkQueueEvent(workQueueId);
            StepResult result = processStep("Nuke WorkQueue: " + workQueueId, event);

            sendJsonResponse(exchange, 200, JsonSerializer.serialize(
                    Map.of("step", result.step(), "sideEffects", result.sideEffects())));
        } catch (Exception e) {
            sendJsonResponse(exchange, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private void handleTick(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        try {
            Map<String, String> body = readJsonBody(exchange);
            String secondsStr = requireField(body, "seconds");
            long seconds = Long.parseLong(secondsStr);

            StepResult result = submitAndWait(() -> {
                currentTime = currentTime.plusSeconds(seconds);
                TimeEvent te = new TimeEvent(currentTime);
                return processStepInternal("Tick +" + seconds + "s", te);
            });

            sendJsonResponse(exchange, 200, JsonSerializer.serialize(
                    Map.of("step", result.step(), "sideEffects", result.sideEffects())));
        } catch (Exception e) {
            sendJsonResponse(exchange, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private void handleActionCompleted(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        try {
            Map<String, String> body = readJsonBody(exchange);
            String actionIdStr = requireField(body, "actionId");
            long workQueueId = Long.parseLong(requireField(body, "workQueueId"));
            UUID actionId = UUID.fromString(actionIdStr);

            ActionCompletedEvent event = new ActionCompletedEvent(actionId, workQueueId);
            StepResult result = processStep("Complete action: " + actionIdStr.substring(0, 8), event);

            sendJsonResponse(exchange, 200, JsonSerializer.serialize(
                    Map.of("step", result.step(), "sideEffects", result.sideEffects())));
        } catch (Exception e) {
            sendJsonResponse(exchange, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private void handleOverrideCondition(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        try {
            Map<String, String> body = readJsonBody(exchange);
            long workQueueId = Long.parseLong(requireField(body, "workQueueId"));
            String taktName = requireField(body, "taktName");
            String conditionId = requireField(body, "conditionId");

            OverrideConditionEvent event = new OverrideConditionEvent(workQueueId, taktName, conditionId);
            StepResult result = processStep(
                    "Override condition: " + conditionId + " on " + taktName, event);

            sendJsonResponse(exchange, 200, JsonSerializer.serialize(
                    Map.of("step", result.step(), "sideEffects", result.sideEffects())));
        } catch (Exception e) {
            sendJsonResponse(exchange, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private void handleOverrideActionCondition(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        try {
            Map<String, String> body = readJsonBody(exchange);
            long workQueueId = Long.parseLong(requireField(body, "workQueueId"));
            String actionIdStr = requireField(body, "actionId");
            String conditionId = requireField(body, "conditionId");
            UUID actionId = UUID.fromString(actionIdStr);

            OverrideActionConditionEvent event = new OverrideActionConditionEvent(
                    workQueueId, actionId, conditionId);
            StepResult result = processStep(
                    "Override action condition: " + conditionId + " on " + actionIdStr.substring(0, 8), event);

            sendJsonResponse(exchange, 200, JsonSerializer.serialize(
                    Map.of("step", result.step(), "sideEffects", result.sideEffects())));
        } catch (Exception e) {
            sendJsonResponse(exchange, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private void handleSnapshot(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }
        String stateJson = submitAndWait(() -> {
            engine.snapshot();
            snapshotStepIndex = steps.size();
            logger.info("Snapshot created at step " + snapshotStepIndex);
            broadcastState();
            return JsonSerializer.serialize(buildState());
        });
        sendJsonResponse(exchange, 200, stateJson);
    }

    private void handleStepBackTo(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        try {
            Map<String, String> body = readJsonBody(exchange);
            String targetStepStr = requireField(body, "targetStep");
            int targetStep = Integer.parseInt(targetStepStr);

            String result = submitAndWait(() -> {
                if (targetStep < 0 || targetStep >= steps.size()) {
                    return null;
                }
                if (snapshotStepIndex < 0 || targetStep < snapshotStepIndex) {
                    logger.warning("Cannot step back to step " + targetStep
                            + ": no snapshot or target is before snapshot at step " + snapshotStepIndex);
                    return null;
                }

                List<Step> stepsToReplay = new ArrayList<>();
                for (int i = snapshotStepIndex; i < targetStep; i++) {
                    stepsToReplay.add(steps.get(i));
                }

                engine.stepBack();

                while (steps.size() > snapshotStepIndex) {
                    steps.remove(steps.size() - 1);
                }

                for (Step step : stepsToReplay) {
                    if (step.event() instanceof SystemTimeSet sts) {
                        currentTime = sts.timestamp();
                    }
                    List<SideEffect> sideEffects = engine.processEvent(step.event());
                    steps.add(new Step(steps.size() + 1, step.description(), step.event(), sideEffects));
                }

                engine.snapshot();
                snapshotStepIndex = steps.size();

                // Recalculate current time from remaining steps
                currentTime = initialTime;
                for (Step step : steps) {
                    if (step.event() instanceof SystemTimeSet sts) {
                        currentTime = sts.timestamp();
                    } else if (step.event() instanceof TimeEvent te) {
                        currentTime = te.timestamp();
                    }
                }

                broadcastState();
                return JsonSerializer.serialize(buildState());
            });

            if (result != null) {
                sendJsonResponse(exchange, 200, result);
            } else {
                sendJsonResponse(exchange, 400, "{\"error\":\"Invalid target step\"}");
            }
        } catch (Exception e) {
            sendJsonResponse(exchange, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private void handleExportEventLog(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        byte[] bytes = submitAndWait(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            boolean first = true;
            for (Step step : steps) {
                if (!first) sb.append(',');
                sb.append("{\"description\":");
                appendJsonString(sb, step.description());
                sb.append(",\"event\":");
                sb.append(JsonSerializer.serialize(step.event()));
                sb.append('}');
                first = false;
            }
            sb.append(']');
            return sb.toString().getBytes(StandardCharsets.UTF_8);
        });

        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"event-log.json\"");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void handleImportEventLog(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            List<Map<String, String>> entries = parseImportArray(body);

            String stateJson = submitAndWait(() -> {
                // Reset engine to initial state
                resetToInitial();

                // Replay events
                for (Map<String, String> entry : entries) {
                    String description = entry.get("description");
                    Event event = EventDeserializer.deserialize(entry);

                    // For TimeEvents, update currentTime before processing
                    // (SystemTimeSet is handled inside processStepInternal)
                    if (event instanceof TimeEvent te) {
                        currentTime = te.timestamp();
                    }

                    processStepInternal(description != null ? description : "Imported", event);
                }

                broadcastState();
                return JsonSerializer.serialize(buildState());
            });

            sendJsonResponse(exchange, 200, stateJson);
        } catch (Exception e) {
            sendJsonResponse(exchange, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    /**
     * Parses a JSON array of objects where each object has a "description" field
     * and an "event" sub-object. Flattens the event fields into the returned maps.
     */
    private List<Map<String, String>> parseImportArray(String json) {
        List<Map<String, String>> result = new ArrayList<>();
        String trimmed = json.strip();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            throw new IllegalArgumentException("Expected JSON array");
        }

        // Parse the array elements by finding matching braces at the top level
        int depth = 0;
        int start = -1;
        for (int i = 1; i < trimmed.length() - 1; i++) {
            char c = trimmed.charAt(i);
            if (c == '"') {
                // Skip string content
                i++;
                while (i < trimmed.length() && trimmed.charAt(i) != '"') {
                    if (trimmed.charAt(i) == '\\') i++;
                    i++;
                }
            } else if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    String element = trimmed.substring(start, i + 1);
                    result.add(parseImportEntry(element));
                    start = -1;
                }
            }
        }
        return result;
    }

    /**
     * Parses a single import entry: {"description":"...", "event":{...}}.
     * Extracts the description and flattens the event fields into a single map.
     */
    private Map<String, String> parseImportEntry(String json) {
        // Find the "event" sub-object and "description" field
        Map<String, String> result = new HashMap<>();

        // Extract description
        int descIdx = json.indexOf("\"description\"");
        if (descIdx >= 0) {
            int colonIdx = json.indexOf(':', descIdx + 13);
            int valStart = json.indexOf('"', colonIdx + 1);
            int valEnd = findClosingQuote(json, valStart);
            result.put("description", unescape(json.substring(valStart + 1, valEnd)));
        }

        // Extract the event sub-object
        int eventIdx = json.indexOf("\"event\"");
        if (eventIdx >= 0) {
            int colonIdx = json.indexOf(':', eventIdx + 7);
            int braceStart = json.indexOf('{', colonIdx);
            int depth = 0;
            int braceEnd = braceStart;
            for (int i = braceStart; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '"') {
                    i++;
                    while (i < json.length() && json.charAt(i) != '"') {
                        if (json.charAt(i) == '\\') i++;
                        i++;
                    }
                } else if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) { braceEnd = i; break; }
                }
            }
            String eventJson = json.substring(braceStart, braceEnd + 1);
            result.putAll(JsonParser.parseObject(eventJson));
        }

        return result;
    }

    private static int findClosingQuote(String s, int openPos) {
        int pos = openPos + 1;
        while (pos < s.length()) {
            if (s.charAt(pos) == '\\') {
                pos += 2;
            } else if (s.charAt(pos) == '"') {
                return pos;
            } else {
                pos++;
            }
        }
        return s.length() - 1;
    }

    private static String unescape(String s) {
        if (!s.contains("\\")) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    default -> { sb.append('\\'); sb.append(next); }
                }
                i++;
            } else {
                sb.append(s.charAt(i));
            }
        }
        return sb.toString();
    }

    private static void appendJsonString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append('"');
    }

    // --- Utility methods ---

    private Map<String, String> readJsonBody(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return JsonParser.parseObject(body);
    }

    private String requireField(Map<String, String> body, String field) {
        String value = body.get(field);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Missing required field: " + field);
        }
        return value;
    }

    private void sendJsonResponse(HttpExchange exchange, int statusCode, String json) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        sendResponse(exchange, statusCode, json);
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
