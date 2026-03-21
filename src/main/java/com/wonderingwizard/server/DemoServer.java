package com.wonderingwizard.server;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.ActionCondition;
import com.wonderingwizard.domain.takt.ActionStatus;
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
import com.wonderingwizard.kafka.CheTargetPositionEventMapper;
import com.wonderingwizard.kafka.JobOperationEventMapper;
import com.wonderingwizard.kafka.KafkaConsumerManager;
import com.wonderingwizard.kafka.KafkaSideEffectPublisher;
import com.wonderingwizard.events.CheLogicalPositionEvent;
import com.wonderingwizard.kafka.CheLogicalPositionEventMapper;
import com.wonderingwizard.kafka.CraneAvailabilityStatusEventMapper;
import com.wonderingwizard.kafka.ContainerMoveStateEventMapper;
import com.wonderingwizard.kafka.CraneDelayActivityEventMapper;
import com.wonderingwizard.kafka.CraneReadinessEventMapper;
import com.wonderingwizard.kafka.QuayCraneMappingEventMapper;
import com.wonderingwizard.kafka.TerminalLayoutEventMapper;
import com.wonderingwizard.kafka.ContainerHandlingEquipmentEventMapper;
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
import com.wonderingwizard.processors.ActionCompletedEvaluator;
import com.wonderingwizard.processors.RTGAssetEventEvaluator;
import com.wonderingwizard.processors.DelayProcessor;
import com.wonderingwizard.processors.DigitalMapProcessor;
import com.wonderingwizard.processors.EventLogProcessor;
import com.wonderingwizard.processors.RtgWaitDurationStep;
import com.wonderingwizard.processors.QCAssetEventEvaluator;
import com.wonderingwizard.processors.RTGJobOperationEvaluator;
import com.wonderingwizard.processors.TTPositionEventEvaluator;
import com.wonderingwizard.processors.ScheduleRunnerProcessor;
import com.wonderingwizard.processors.TTStateProcessor;
import com.wonderingwizard.processors.ContainerMoveStoppedHandler;
import com.wonderingwizard.processors.TTUnavailableHandler;
import com.wonderingwizard.processors.WIAbandonedHandler;
import com.wonderingwizard.processors.WIResetHandler;
import com.wonderingwizard.processors.WIRevertHandler;
import com.wonderingwizard.processors.WQChangeHandler;
import com.wonderingwizard.processors.TimeAlarmProcessor;
import com.wonderingwizard.processors.WorkQueueProcessor;
import com.wonderingwizard.sideeffects.ActionActivated;
import com.wonderingwizard.sideeffects.ActionCompleted;
import com.wonderingwizard.sideeffects.DelayUpdated;
import com.wonderingwizard.sideeffects.ScheduleAborted;
import com.wonderingwizard.sideeffects.ScheduleCreated;
import com.wonderingwizard.sideeffects.TaktActivated;
import com.wonderingwizard.sideeffects.TaktCompleted;
import com.wonderingwizard.sideeffects.TruckAssigned;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private final EventProcessingEngine baseEngine;
    private final Settings settings;
    private final ScheduleRunnerProcessor scheduleRunnerProcessor;
    private final TTStateProcessor ttStateProcessor;
    private final com.wonderingwizard.processors.QCStateProcessor qcStateProcessor;
    private final List<Step> steps = new ArrayList<>();
    private final Map<Long, WorkQueueMessage> wqMessageCache = new HashMap<>();
    /** Cached schedule view builders, rebuilt incrementally from new steps only. */
    private final Map<Long, ScheduleViewBuilder> scheduleViewCache = new LinkedHashMap<>();
    private int scheduleViewCacheIndex = 0;
    private final Instant initialTime = Instant.EPOCH;
    private Instant currentTime = initialTime;
    private DigitalMapProcessor digitalMapProcessor;
    private HttpServer httpServer;
    private KafkaConsumerManager kafkaConsumerManager;
    private com.wonderingwizard.server.demo.DemoEventProducer demoEventProducer;
    private KafkaSideEffectPublisher sideEffectPublisher;
    private org.apache.kafka.clients.producer.KafkaProducer<String, String> jsonKafkaProducer;
    private org.apache.kafka.clients.producer.KafkaProducer<String, org.apache.avro.generic.GenericRecord> avroKafkaProducer;
    private com.wonderingwizard.metrics.Metrics metrics;
    private final com.wonderingwizard.kafka.DeadLetterQueue deadLetterQueue = new com.wonderingwizard.kafka.DeadLetterQueue();
    private final SseConnectionManager sseManager = new SseConnectionManager();
    private static final long BROADCAST_DEBOUNCE_MIN_MS = 50;
    private static final long BROADCAST_DEBOUNCE_MAX_MS = 500;
    private volatile boolean broadcastPending;
    private volatile boolean broadcastLoopRunning;
    private volatile Thread playClockThread;
    private volatile double playClockSpeed = 1.0;
    private volatile Instant playClockLastSentTime;
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
                                long totalDelaySeconds, List<TaktView> takts,
                                String workQueueSequence, String pointOfWorkName,
                                String bollardPosition, String workQueueManaged) {}

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
    public record ActionView(UUID id, DeviceType deviceType, ActionType actionType,
                              String description,
                              ActionState status, Set<UUID> dependsOn, int containerIndex,
                              int durationSeconds, int deviceIndex, List<ConditionView> conditions,
                              List<String> containerIds, String cheShortName,
                              String completionReason) {}

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
        this.baseEngine = new EventProcessingEngine();
        this.engine = new EventPropagatingEngine(baseEngine);
        engine.register(new EventLogProcessor());
        engine.register(new TimeAlarmProcessor());
        this.digitalMapProcessor = new DigitalMapProcessor();
        var workQueueProcessor = new WorkQueueProcessor();
        workQueueProcessor.registerStep(digitalMapProcessor);
        workQueueProcessor.registerStep(new RtgWaitDurationStep());
        engine.register(digitalMapProcessor);
        engine.register(workQueueProcessor);
        this.ttStateProcessor = new TTStateProcessor();
        engine.register(ttStateProcessor);
        this.qcStateProcessor = new com.wonderingwizard.processors.QCStateProcessor();
        engine.register(qcStateProcessor);
        this.scheduleRunnerProcessor = new ScheduleRunnerProcessor();
        scheduleRunnerProcessor.registerTTAllocationStrategy(ttStateProcessor);
        scheduleRunnerProcessor.registerSubProcessor(new TTUnavailableHandler());
        scheduleRunnerProcessor.registerSubProcessor(new WIAbandonedHandler());
        scheduleRunnerProcessor.registerSubProcessor(new WIResetHandler());
        scheduleRunnerProcessor.registerSubProcessor(new WIRevertHandler());
        scheduleRunnerProcessor.registerSubProcessor(new WQChangeHandler());
        scheduleRunnerProcessor.registerSubProcessor(new ContainerMoveStoppedHandler());
        scheduleRunnerProcessor.registerCompletionEvaluator(new QCAssetEventEvaluator());
        scheduleRunnerProcessor.registerCompletionEvaluator(new TTPositionEventEvaluator());
        scheduleRunnerProcessor.registerCompletionEvaluator(new RTGJobOperationEvaluator());
        scheduleRunnerProcessor.registerCompletionEvaluator(new ActionCompletedEvaluator());
        scheduleRunnerProcessor.registerCompletionEvaluator(new RTGAssetEventEvaluator());
        engine.register(scheduleRunnerProcessor);
        engine.register(new DelayProcessor());
        // Take initial snapshot so we can always reset to clean state
        engine.snapshot();
        snapshotStepIndex = 0;
    }

    DemoServer(Engine engine) {
        this.settings = Settings.load();
        this.engine = engine;
        this.baseEngine = null;
        this.scheduleRunnerProcessor = null;
        this.ttStateProcessor = null;
        this.qcStateProcessor = null;
    }

    /**
     * Starts the HTTP server on the specified port.
     *
     * @param port the port to listen on
     * @throws IOException if the server cannot be started
     */
    public void start(int port) throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.createContext("/", this::handleSystemStatus);
        httpServer.createContext("/schedule-viewer", this::handleScheduleViewer);
        httpServer.createContext("/api/state", this::handleGetState);
        httpServer.createContext("/api/work-instruction", this::handleWorkInstruction);
        httpServer.createContext("/api/work-instruction/batch", this::handleWorkInstructionBatch);
        httpServer.createContext("/api/work-queue", this::handleWorkQueue);
        httpServer.createContext("/api/nuke-work-queue", this::handleNukeWorkQueue);
        httpServer.createContext("/api/tick", this::handleTick);
        httpServer.createContext("/api/play", this::handlePlay);
        httpServer.createContext("/api/action-completed", this::handleActionCompleted);
        httpServer.createContext("/api/simulate-qc-event", this::handleSimulateQcEvent);
        httpServer.createContext("/api/simulate-rtg-asset-event", this::handleSimulateRtgAssetEvent);
        httpServer.createContext("/api/simulate-rtg-job-operation", this::handleSimulateRtgJobOperation);
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
        httpServer.createContext("/quaycranes", this::handleQuayCranes);
        httpServer.createContext("/api/container-handling-equipment", this::handleContainerHandlingEquipment);
        httpServer.createContext("/api/quay-crane-mapping", this::handleQuayCraneMapping);
        httpServer.createContext("/api/crane-availability-status", this::handleCraneAvailabilityStatus);
        httpServer.createContext("/api/crane-readiness", this::handleCraneReadiness);
        httpServer.createContext("/api/crane-delay-activity", this::handleCraneDelayActivity);
        httpServer.createContext("/api/container-move-state", this::handleContainerMoveState);
        httpServer.createContext("/containermovestate", this::handleContainerMoveStatePage);
        httpServer.createContext("/api/che-logical-position", this::handleCheLogicalPosition);

        httpServer.createContext("/api/pathfind", this::handlePathfind);
        httpServer.createContext("/api/digitalmap", this::handleDigitalMap);
        httpServer.createContext("/api/standby", this::handleStandby);
        httpServer.createContext("/locations", this::handleLocations);
        httpServer.createContext("/api/version", this::handleVersion);
        httpServer.createContext("/api/dlq", this::handleDlqApi);
        httpServer.createContext("/dlq", this::handleDlqPage);
        httpServer.createContext("/release-notes", this::handleReleaseNotes);
        httpServer.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        httpServer.start();
        sseManager.startKeepalive();
        startEventLoop();
        logger.info("Demo server started on port " + port);

        // Set system time to current computer time and start the play clock
        sendSystemTimeSet(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        if (settings.clockAutoStart()) {
            startPlayClock();
        }

        // Load default digital map
        loadDefaultDigitalMap();

        if (settings.kafkaEnabled()) {
            startKafkaConsumers();
            startSideEffectPublisher();
            demoEventProducer = new com.wonderingwizard.server.demo.DemoEventKafkaProducer(settings);
            // JSON producer for sending simulated asset events
            var producerProps = new java.util.Properties();
            var kafkaConfig = settings.kafkaConfiguration();
            producerProps.put("bootstrap.servers", kafkaConfig.bootstrapServer());
            producerProps.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
            producerProps.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
            if (kafkaConfig.securityProtocol() != null && !kafkaConfig.securityProtocol().isEmpty()) {
                producerProps.put("security.protocol", kafkaConfig.securityProtocol());
            }
            if (kafkaConfig.saslMechanism() != null && !kafkaConfig.saslMechanism().isEmpty()) {
                producerProps.put("sasl.mechanism", kafkaConfig.saslMechanism());
                producerProps.put("sasl.jaas.config",
                        "org.apache.kafka.common.security.plain.PlainLoginModule required username=\""
                                + kafkaConfig.saslUsername() + "\" password=\"" + kafkaConfig.saslPassword() + "\";");
            }
            jsonKafkaProducer = new org.apache.kafka.clients.producer.KafkaProducer<>(producerProps);
            // Avro producer for sending job operations
            var avroProps = new java.util.Properties();
            avroProps.put("bootstrap.servers", kafkaConfig.bootstrapServer());
            avroProps.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
            avroProps.put("value.serializer", "io.confluent.kafka.serializers.KafkaAvroSerializer");
            avroProps.put("schema.registry.url", kafkaConfig.schemaRegistryUrl());
            if (kafkaConfig.securityProtocol() != null && !kafkaConfig.securityProtocol().isEmpty()) {
                avroProps.put("security.protocol", kafkaConfig.securityProtocol());
            }
            if (kafkaConfig.saslMechanism() != null && !kafkaConfig.saslMechanism().isEmpty()) {
                avroProps.put("sasl.mechanism", kafkaConfig.saslMechanism());
                avroProps.put("sasl.jaas.config",
                        "org.apache.kafka.common.security.plain.PlainLoginModule required username=\""
                                + kafkaConfig.saslUsername() + "\" password=\"" + kafkaConfig.saslPassword() + "\";");
            }
            avroKafkaProducer = new org.apache.kafka.clients.producer.KafkaProducer<>(avroProps);
        } else {
            demoEventProducer = new com.wonderingwizard.server.demo.DemoEventDirectProducer(this);
            logger.info("Kafka disabled (kafka.enabled=false)");
        }
    }

    /**
     * Stops the HTTP server.
     */
    public void stop() {
        stopPlayClock();
        eventLoopRunning = false;
        sseManager.stop();
        if (demoEventProducer instanceof com.wonderingwizard.server.demo.DemoEventKafkaProducer kafkaProducer) {
            kafkaProducer.close();
        }
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
        // Initialize OTEL metrics with Prometheus exporter on port 9464
        this.metrics = new com.wonderingwizard.metrics.Metrics();
        baseEngine.setMetrics(this.metrics);
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

        kafkaConsumerManager = new KafkaConsumerManager(settings.kafkaConfiguration(), stepRecordingEngine, metrics, deadLetterQueue);
        kafkaConsumerManager.register(
                settings.workQueueConsumerConfiguration(),
                new WorkQueueEventMapper()
        );
        kafkaConsumerManager.register(
                settings.workInstructionConsumerConfiguration(),
                new WorkInstructionEventMapper()
        );
        kafkaConsumerManager.register(
                settings.containerHandlingEquipmentConsumerConfiguration(),
                new ContainerHandlingEquipmentEventMapper()
        );
        kafkaConsumerManager.register(
                settings.cheLogicalPositionConsumerConfiguration(),
                new CheLogicalPositionEventMapper()
        );
        kafkaConsumerManager.register(
                settings.terminalLayoutConsumerConfiguration(),
                new TerminalLayoutEventMapper()
        );
        kafkaConsumerManager.register(
                settings.quayCraneMappingConsumerConfiguration(),
                new QuayCraneMappingEventMapper()
        );
        kafkaConsumerManager.register(
                settings.craneReadinessConsumerConfiguration(),
                new CraneReadinessEventMapper()
        );
        kafkaConsumerManager.register(
                settings.craneAvailabilityStatusConsumerConfiguration(),
                new CraneAvailabilityStatusEventMapper()
        );
        kafkaConsumerManager.register(
                settings.craneDelayActivitiesConsumerConfiguration(),
                new CraneDelayActivityEventMapper()
        );
        kafkaConsumerManager.register(
                settings.containerMoveStateConsumerConfiguration(),
                new ContainerMoveStateEventMapper()
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
        kafkaConsumerManager.register(
                settings.cheTargetPositionConsumerConfiguration(),
                new CheTargetPositionEventMapper()
        );
        kafkaConsumerManager.register(
                settings.jobOperationConsumerConfiguration(),
                new JobOperationEventMapper()
        );
        kafkaConsumerManager.startAll();
    }

    private void startSideEffectPublisher() {
        String terminalCode = settings.terminalCode();
        String eventSource = "FESv" + resolveVersion();

        sideEffectPublisher = new KafkaSideEffectPublisher(settings.kafkaConfiguration());
        sideEffectPublisher.registerMapper(
                ActionActivated.class,
                settings.equipmentInstructionRtgTopic(),
                new ActionActivatedToEquipmentInstructionMapper(terminalCode, eventSource,
                        Set.of(ActionType.RTG_DRIVE, ActionType.RTG_FETCH,
                                ActionType.RTG_HANDOVER_TO_TT, ActionType.RTG_LIFT_FROM_TT,
                                ActionType.RTG_PLACE_ON_YARD))
        );
        sideEffectPublisher.registerMapper(
                ActionActivated.class,
                settings.equipmentInstructionTtTopic(),
                new ActionActivatedToEquipmentInstructionMapper(terminalCode, eventSource,
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
                new ActionActivatedToEquipmentInstructionMapper(terminalCode, eventSource,
                        Set.of(ActionType.QC_LIFT, ActionType.QC_PLACE))
        );
        sideEffectPublisher.registerMapper(
                TruckAssigned.class,
                settings.containerMoveStateTopic(),
                new com.wonderingwizard.kafka.TruckAssignedToContainerMoveStateMapper(terminalCode, eventSource)
        );
        sideEffectPublisher.start();
    }

    /**
     * Resolves the current application version from release-notes.html.
     */
    private String resolveVersion() {
        try (InputStream is = getClass().getResourceAsStream("/release-notes.html")) {
            if (is == null) return "unknown";
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            Matcher matcher = VERSION_PATTERN.matcher(content);
            return matcher.find() ? matcher.group(1) : "unknown";
        } catch (IOException e) {
            return "unknown";
        }
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
        // Cache WorkQueueMessage for fast schedule view building
        if (event instanceof WorkQueueMessage wqMsg) {
            wqMessageCache.put(wqMsg.workQueueId(), wqMsg);
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
        wqMessageCache.clear();
        scheduleViewCache.clear();
        scheduleViewCacheIndex = 0;
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
                // Adaptive debounce: short when idle, long when event queue has pressure
                long debounce = Math.clamp(
                        BROADCAST_DEBOUNCE_MIN_MS + eventQueue.size() * 50L,
                        BROADCAST_DEBOUNCE_MIN_MS, BROADCAST_DEBOUNCE_MAX_MS);
                try {
                    Thread.sleep(debounce);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                // Build state snapshot on event loop (fast), serialize on this thread (slow)
                Map<String, Object> stateSnapshot = submitAndWait(() -> buildState());
                String json = JsonSerializer.serialize(stateSnapshot);
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

            // Clear all steps after snapshot and invalidate caches
            while (steps.size() > snapshotStepIndex) {
                steps.remove(steps.size() - 1);
            }
            scheduleViewCache.clear();
            scheduleViewCacheIndex = 0;
            wqMessageCache.clear();

            // Replay events from snapshot up to target (without creating new snapshots)
            for (Step step : stepsToReplay) {
                if (step.event() instanceof SystemTimeSet sts) {
                    currentTime = sts.timestamp();
                }
                if (step.event() instanceof WorkQueueMessage wqMsg) {
                    wqMessageCache.put(wqMsg.workQueueId(), wqMsg);
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
    private static final int DEFAULT_SCHEDULE_PAGE_SIZE = 10;

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

        // Only include the last MAX_STEPS_IN_RESPONSE steps (copy for thread-safe serialization)
        if (steps.size() <= MAX_STEPS_IN_RESPONSE) {
            state.put("steps", List.copyOf(steps));
        } else {
            state.put("steps", List.copyOf(steps.subList(steps.size() - MAX_STEPS_IN_RESPONSE, steps.size())));
        }

        // Paginate schedules
        List<ScheduleView> allSchedules = buildScheduleViews();
        state.put("totalSchedules", allSchedules.size());
        state.put("schedulePage", schedulePage);
        state.put("schedulePageSize", schedulePageSize);

        int from = Math.min(schedulePage * schedulePageSize, allSchedules.size());
        int to = Math.min(from + schedulePageSize, allSchedules.size());
        state.put("schedules", allSchedules.subList(from, to));

        // Include TT (truck) state and positions
        if (ttStateProcessor != null) {
            state.put("trucks", ttStateProcessor.getTruckState());
            state.put("truckPositions", ttStateProcessor.getTruckPositions());
        }
        // Include QC (quay crane) state
        if (qcStateProcessor != null) {
            state.put("quayCranes", qcStateProcessor.getQCState());
            state.put("craneReadiness", qcStateProcessor.getCraneReadiness());
            state.put("craneAvailability", qcStateProcessor.getCraneAvailability());
            state.put("craneDelays", qcStateProcessor.getCraneDelays());
        }

        // Include location occupancy (QC and RTG positions)
        if (scheduleRunnerProcessor != null) {
            state.put("locationOccupancy", scheduleRunnerProcessor.getLocationOccupancy());
        }

        // Include Kafka consumer status
        if (kafkaConsumerManager != null) {
            state.put("kafkaStatus", kafkaConsumerManager.getStatus());
        }

        // Include all known work queues (survives step truncation)
        state.put("workQueues", new HashMap<>(wqMessageCache));

        // Include play clock status and kafka mode
        state.put("playStatus", Map.of(
                "playing", playClockThread != null,
                "speed", playClockSpeed));
        state.put("kafkaEnabled", settings.kafkaEnabled());

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
        // Incrementally process only new steps since last call
        Map<Long, ScheduleViewBuilder> builders = scheduleViewCache;
        Map<Long, WorkQueueMessage> wqMessages = wqMessageCache;

        for (int i = scheduleViewCacheIndex; i < steps.size(); i++) {
            Step step = steps.get(i);
            for (SideEffect se : step.sideEffects()) {
                switch (se) {
                    case ScheduleCreated created -> {
                        long wqId = created.workQueueId();
                        ScheduleViewBuilder builder = new ScheduleViewBuilder(wqId, true, created.estimatedMoveTime());
                        WorkQueueMessage wqMsg = wqMessages.get(wqId);
                        if (wqMsg != null) {
                            builder.workQueueSequence = wqMsg.workQueueSequence();
                            builder.pointOfWorkName = wqMsg.pointOfWorkName();
                            builder.bollardPosition = wqMsg.bollardPosition();
                            builder.workQueueManaged = wqMsg.workQueueManaged();
                        }
                        builder.hasTTAllocation = scheduleRunnerProcessor != null
                                && scheduleRunnerProcessor.hasTTAllocationStrategy();
                        builder.occupiedPositionKeys = scheduleRunnerProcessor != null
                                ? scheduleRunnerProcessor.getOccupiedPositionKeys() : Set.of();
                        builder.storeTakts(created.takts());
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
        scheduleViewCacheIndex = steps.size();

        // Rebuild takt/action views from authoritative processor state (always fresh)
        for (ScheduleViewBuilder builder : builders.values()) {
            long wqId = builder.workQueueId;
            builder.takts.clear();
            for (Takt takt : builder.originalTakts) {
                List<ActionView> actionViews = new ArrayList<>();
                for (Action originalAction : takt.actions()) {
                    Action action = scheduleRunnerProcessor != null
                            ? scheduleRunnerProcessor.getAction(wqId, originalAction.id())
                            : originalAction;
                    if (action == null) action = originalAction;
                    List<String> cIds = action.workInstructions().stream()
                        .map(wi -> wi.containerId() != null ? wi.containerId() : "")
                        .filter(id -> !id.isEmpty())
                        .toList();
                    ActionState actionState = scheduleRunnerProcessor != null
                            ? mapActionStatus(scheduleRunnerProcessor.getActionStatus(wqId, action.id()))
                            : ActionState.PENDING;
                    String reason = action.completionReason() != null
                            ? action.completionReason().displayName() : null;
                    String cheShortName = action.cheShortName();
                    if (cheShortName == null
                            && action.workInstructions() != null && !action.workInstructions().isEmpty()) {
                        var wi = action.workInstructions().getFirst();
                        cheShortName = switch (action.deviceType()) {
                            case QC -> wi.fetchChe();
                            case RTG -> "LOAD".equalsIgnoreCase(wi.moveKind())
                                    ? wi.fetchChe() : wi.putChe();
                            case TT -> null;
                        };
                    }
                    actionViews.add(new ActionView(
                            action.id(), action.deviceType(), action.actionType(),
                            action.description(),
                            actionState, action.dependsOn(), action.containerIndex(),
                            action.durationSeconds(), action.deviceIndex(), List.of(), cIds,
                            cheShortName, reason));
                }
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
            // Refresh occupied positions and satisfied event gates
            builder.occupiedPositionKeys = scheduleRunnerProcessor != null
                    ? scheduleRunnerProcessor.getOccupiedPositionKeys() : Set.of();
            builder.satisfiedEventGates.clear();
            if (scheduleRunnerProcessor != null) {
                for (Takt takt : builder.originalTakts) {
                    for (Action action : takt.actions()) {
                        Set<String> gates = scheduleRunnerProcessor.getSatisfiedEventGates(wqId, action.id());
                        if (!gates.isEmpty()) {
                            builder.satisfiedEventGates.put(action.id(), new HashSet<>(gates));
                        }
                    }
                }
            }
        }

        return builders.values().stream()
                .map(b -> b.build(currentTime))
                .toList();
    }

    private static ActionState mapActionStatus(ActionStatus status) {
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
        Set<String> occupiedPositionKeys = Set.of();

        String workQueueSequence;
        String pointOfWorkName;
        String bollardPosition;
        String workQueueManaged;

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
                            action.id(), action.deviceType(), action.actionType(),
                            action.description(),
                            actionState, action.dependsOn(), action.containerIndex(),
                            action.durationSeconds(), action.deviceIndex(), actionConditions,
                            action.containerIds(), action.cheShortName(),
                            action.completionReason()));
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
                    totalDelaySeconds, updatedTakts,
                    workQueueSequence, pointOfWorkName, bollardPosition, workQueueManaged);
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

            // Location conditions (block/skip based on position occupancy)
            if (originalAction != null) {
                Set<String> occupied = occupiedPositionKeys;
                var locContext = new com.wonderingwizard.domain.takt.ActionConditionContext(
                        taktState == TaktState.ACTIVE, completedActionIds,
                        satisfiedEventGates.getOrDefault(action.id(), Set.of()), occupied);
                for (var cond : originalAction.locationSkipConditions()) {
                    boolean overridden = overrides.contains(cond.id());
                    boolean satisfied = overridden || cond.evaluate(locContext);
                    views.add(new ConditionView(cond.id(), cond.type(),
                            satisfied, overridden,
                            cond.explanation(locContext)));
                }
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

        // Send current state to just the new client (not all), on a virtual thread
        // to avoid blocking the HTTP handler if the event loop is busy
        Thread.ofVirtual().name("sse-init").start(() -> {
            try {
                Map<String, Object> state = submitAndWait(() -> buildState());
                String json = JsonSerializer.serialize(state);
                sseManager.sendToLatest("state", json);
            } catch (Exception e) {
                // Client may have disconnected already — ignore
            }
        });
    }

    private void handleScheduleViewer(HttpExchange exchange) throws IOException {
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

    private void handleQuayCranes(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        try (InputStream is = getClass().getResourceAsStream("/quaycranes.html")) {
            if (is == null) {
                sendResponse(exchange, 404, "Quay cranes page not found");
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

    private void handleQuayCraneMapping(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        try {
            Map<String, String> body = readJsonBody(exchange);
            String qcName = requireField(body, "quayCraneShortName");
            var event = new com.wonderingwizard.events.QuayCraneMappingEvent(
                    qcName,
                    body.getOrDefault("vesselName", null),
                    body.getOrDefault("craneMode", null),
                    body.getOrDefault("lane", null),
                    body.getOrDefault("standbyPositionName", null),
                    body.getOrDefault("standbyNodeName", null),
                    body.getOrDefault("standbyTrafficDirection", null),
                    body.getOrDefault("loadPinningPositionName", null),
                    body.getOrDefault("dischargePinningPositionName", null),
                    body.getOrDefault("terminalCode", ""),
                    System.currentTimeMillis());
            StepResult result = processStep("QC: " + qcName, event);

            sendJsonResponse(exchange, 200, JsonSerializer.serialize(
                    Map.of("step", result.step(), "sideEffects", result.sideEffects())));
        } catch (Exception e) {
            sendJsonResponse(exchange, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private void handleCraneAvailabilityStatus(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        try {
            Map<String, String> body = readJsonBody(exchange);
            String cheId = requireField(body, "cheId");
            var event = new com.wonderingwizard.events.CraneAvailabilityStatusEvent(
                    body.getOrDefault("terminalCode", ""),
                    cheId,
                    body.getOrDefault("cheType", "QC"),
                    com.wonderingwizard.events.CraneAvailabilityStatus.fromCode(
                            body.getOrDefault("cheStatus", "NOT_READY")),
                    System.currentTimeMillis());
            StepResult result = processStep("CraneAvailability: " + cheId, event);

            sendJsonResponse(exchange, 200, JsonSerializer.serialize(
                    Map.of("step", result.step(), "sideEffects", result.sideEffects())));
        } catch (Exception e) {
            sendJsonResponse(exchange, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private void handleCraneReadiness(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        try {
            Map<String, String> body = readJsonBody(exchange);
            String qcShortName = requireField(body, "qcShortName");
            long workQueueId = Long.parseLong(body.getOrDefault("workQueueId", "0"));
            String qcResumeTimestampStr = body.get("qcResumeTimestamp");
            java.time.Instant qcResumeTimestamp = (qcResumeTimestampStr != null && !qcResumeTimestampStr.isBlank())
                    ? java.time.Instant.parse(qcResumeTimestampStr) : java.time.Instant.now();
            var event = new com.wonderingwizard.events.CraneReadinessEvent(
                    qcShortName,
                    workQueueId,
                    qcResumeTimestamp,
                    body.getOrDefault("updatedBy", null),
                    body.getOrDefault("eventId", UUID.randomUUID().toString()));
            StepResult result = processStep("CraneReadiness: " + qcShortName, event);

            sendJsonResponse(exchange, 200, JsonSerializer.serialize(
                    Map.of("step", result.step(), "sideEffects", result.sideEffects())));
        } catch (Exception e) {
            sendJsonResponse(exchange, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private void handleCraneDelayActivity(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        try {
            Map<String, String> body = readJsonBody(exchange);
            String cheShortName = requireField(body, "cheShortName");
            var event = new com.wonderingwizard.events.CraneDelayActivityEvent(
                    body.getOrDefault("eventType", "Crane Delay Occured"),
                    body.getOrDefault("opType", "I"),
                    body.getOrDefault("cdhTerminalCode", ""),
                    parseLongOrNull(body.get("messageSequenceNumber")),
                    parseLongOrNull(body.get("vesselVisitCraneDelayId")),
                    body.getOrDefault("vesselVisitId", null),
                    parseInstantOrNull(body.get("delayStartTime")),
                    parseInstantOrNull(body.get("delayStopTime")),
                    cheShortName,
                    body.getOrDefault("delayRemarks", null),
                    body.getOrDefault("delayType", null),
                    body.getOrDefault("delayTypeDescription", null),
                    body.getOrDefault("positionEnum", null),
                    body.getOrDefault("delayStatus", null),
                    body.getOrDefault("delayTypeAction", null),
                    body.getOrDefault("delayTypeCategory", null),
                    System.currentTimeMillis());
            StepResult result = processStep("CraneDelay: " + cheShortName, event);

            sendJsonResponse(exchange, 200, JsonSerializer.serialize(
                    Map.of("step", result.step(), "sideEffects", result.sideEffects())));
        } catch (Exception e) {
            sendJsonResponse(exchange, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private static Long parseLongOrNull(String value) {
        if (value == null || value.isBlank() || "null".equals(value)) return null;
        return Long.parseLong(value);
    }

    private static Instant parseInstantOrNull(String value) {
        if (value == null || value.isBlank() || "null".equals(value)) return null;
        return Instant.parse(value);
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

    private void handleCheLogicalPosition(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        try {
            Map<String, String> body = readJsonBody(exchange);
            String cheShortName = requireField(body, "cheShortName");
            long currentMapNodeId = body.get("currentMapNodeId") != null && !body.get("currentMapNodeId").isEmpty()
                    ? Long.parseLong(body.get("currentMapNodeId")) : 0L;
            String currentMapNodeName = body.getOrDefault("currentMapNodeName", null);
            double latitude = body.get("latitude") != null && !body.get("latitude").isEmpty()
                    ? Double.parseDouble(body.get("latitude")) : 0.0;
            double longitude = body.get("longitude") != null && !body.get("longitude").isEmpty()
                    ? Double.parseDouble(body.get("longitude")) : 0.0;
            double hdop = body.get("hdop") != null && !body.get("hdop").isEmpty()
                    ? Double.parseDouble(body.get("hdop")) : 0.0;
            long timestampMs = body.get("timestampMs") != null && !body.get("timestampMs").isEmpty()
                    ? Long.parseLong(body.get("timestampMs")) : System.currentTimeMillis();

            CheLogicalPositionEvent event = new CheLogicalPositionEvent(
                    cheShortName, currentMapNodeId, currentMapNodeName,
                    latitude, longitude, hdop, timestampMs);
            StepResult result = processStep("Position: " + cheShortName, event);

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

    private static final Pattern VERSION_PATTERN = Pattern.compile("<h2>v([^<]+)</h2>");

    private void handleVersion(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        try (InputStream is = getClass().getResourceAsStream("/release-notes.html")) {
            if (is == null) {
                sendJsonResponse(exchange, 404, "{\"error\":\"Release notes not found\"}");
                return;
            }
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            Matcher matcher = VERSION_PATTERN.matcher(content);
            String version = matcher.find() ? matcher.group(1) : "unknown";
            sendJsonResponse(exchange, 200, "{\"version\":\"" + escapeJson(version) + "\"}");
        }
    }

    private void handleContainerMoveState(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        try {
            Map<String, String> body = readJsonBody(exchange);
            var event = new com.wonderingwizard.events.ContainerMoveStateEvent(
                    body.getOrDefault("containerMoveAction", "STOPPED"),
                    body.getOrDefault("containerMoveStateRequestStatus", "ERROR"),
                    body.getOrDefault("responseContainerMoveState", "TT_ASSIGNED"),
                    requireField(body, "carryCHEName"),
                    Long.parseLong(requireField(body, "workInstructionId")),
                    body.getOrDefault("moveKind", ""),
                    body.getOrDefault("containerId", ""),
                    body.getOrDefault("terminalCode", ""),
                    body.getOrDefault("errorMessage", ""),
                    System.currentTimeMillis());
            StepResult result = processStep("ContainerMoveState: " + event.carryCHEName(), event);

            sendJsonResponse(exchange, 200, JsonSerializer.serialize(
                    Map.of("step", result.step(), "sideEffects", result.sideEffects())));
        } catch (Exception e) {
            sendJsonResponse(exchange, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private void handleContainerMoveStatePage(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        try (InputStream is = getClass().getResourceAsStream("/containermovestate.html")) {
            if (is == null) {
                sendResponse(exchange, 404, "Container Move State page not found");
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

    private void handleDlqApi(HttpExchange exchange) throws IOException {
        if ("GET".equals(exchange.getRequestMethod())) {
            var entries = deadLetterQueue.getEntries();
            StringBuilder sb = new StringBuilder();
            sb.append("{\"count\":").append(entries.size()).append(",\"entries\":[");
            for (int i = 0; i < entries.size(); i++) {
                var e = entries.get(i);
                if (i > 0) sb.append(',');
                sb.append("{\"topic\":\"").append(escapeJson(e.topic()))
                  .append("\",\"partition\":").append(e.partition())
                  .append(",\"offset\":").append(e.offset())
                  .append(",\"timestamp\":\"").append(e.timestamp())
                  .append("\",\"errorMessage\":\"").append(escapeJson(e.errorMessage()))
                  .append("\",\"exceptionClass\":\"").append(escapeJson(e.exceptionClass()))
                  .append("\"}");
            }
            sb.append("]}");
            sendJsonResponse(exchange, 200, sb.toString());
        } else if ("DELETE".equals(exchange.getRequestMethod())) {
            deadLetterQueue.clear();
            sendJsonResponse(exchange, 200, "{\"cleared\":true}");
        } else {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
        }
    }

    private void handleLocations(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }
        try (InputStream is = getClass().getResourceAsStream("/locations.html")) {
            if (is == null) {
                sendResponse(exchange, 404, "Locations page not found");
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

    private void handleDlqPage(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        try (InputStream is = getClass().getResourceAsStream("/dlq.html")) {
            if (is == null) {
                sendResponse(exchange, 404, "DLQ page not found");
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

    private void handleSystemStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        try (InputStream is = getClass().getResourceAsStream("/system-status.html")) {
            if (is == null) {
                sendResponse(exchange, 404, "System status not found");
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

    private void handleReleaseNotes(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        try (InputStream is = getClass().getResourceAsStream("/release-notes.html")) {
            if (is == null) {
                sendResponse(exchange, 404, "Release notes not found");
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
        var pathCoords = digitalMapProcessor.findPath(from, to);

        var sb = new StringBuilder();
        sb.append("{\"from\":").append(JsonSerializer.serialize(from))
          .append(",\"to\":").append(JsonSerializer.serialize(to))
          .append(",\"durationSeconds\":").append(duration)
          .append(",\"found\":").append(duration >= 0)
          .append(",\"path\":[");
        for (int i = 0; i < pathCoords.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("[").append(pathCoords.get(i)[0]).append(",").append(pathCoords.get(i)[1]).append("]");
        }
        sb.append("]}");
        String json = sb.toString();

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
              .append(",\"oneway\":").append(seg.oneway())
              .append(",\"highway\":\"").append(escapeJson(seg.highway())).append('"')
              .append(",\"name\":\"").append(escapeJson(seg.name())).append('"')
              .append(",\"lanes\":").append(seg.lanes())
              .append(",\"priority\":").append(seg.priority()).append('}');
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
        String size = "20";
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2) {
                String key = java.net.URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
                String value = java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                if ("position".equals(key)) position = value;
                if ("size".equals(key)) size = value;
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

        String standby = "40".equals(size)
                ? digitalMapProcessor.findStandbyLocation40(position)
                : digitalMapProcessor.findStandbyLocation(position);

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
            String fromPosition = body.getOrDefault("fromPosition", "");
            String toPosition = body.getOrDefault("toPosition", "");
            String containerId = body.getOrDefault("containerId", "");
            String moveKind = body.getOrDefault("moveKind", "");
            String jobPosition = body.getOrDefault("jobPosition", "FWD");
            String isoType = body.getOrDefault("isoType", "");
            String freightKind = body.getOrDefault("freightKind", "");
            String pinning = body.getOrDefault("pinning", "");

            WorkInstructionEvent event = new WorkInstructionEvent(
                    eventType, workInstructionId, workQueueId, fetchChe, workInstructionMoveStage, estimatedMoveTime,
                    estimatedCycleTimeSeconds, estimatedRtgCycleTimeSeconds,
                    putChe, isTwinFetch, isTwinPut, isTwinCarry, twinCompanionWorkInstruction,
                    fromPosition, toPosition, containerId, moveKind, jobPosition,
                    isoType, freightKind, pinning);
            demoEventProducer.sendWorkInstruction(event);

            sendJsonResponse(exchange, 200, "{\"ok\":true}");
        } catch (Exception e) {
            sendJsonResponse(exchange, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private WorkInstructionEvent parseWorkInstructionEvent(Map<String, String> body) {
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
        int estimatedCycleTimeSeconds = Integer.parseInt(body.getOrDefault("estimatedCycleTimeSeconds", "0"));
        int estimatedRtgCycleTimeSeconds = Integer.parseInt(body.getOrDefault("estimatedRtgCycleTimeSeconds", "60"));
        String putChe = body.getOrDefault("putChe", "");
        boolean isTwinFetch = Boolean.parseBoolean(body.getOrDefault("isTwinFetch", "false"));
        boolean isTwinPut = Boolean.parseBoolean(body.getOrDefault("isTwinPut", "false"));
        boolean isTwinCarry = Boolean.parseBoolean(body.getOrDefault("isTwinCarry", "false"));
        long twinCompanionWorkInstruction = Long.parseLong(body.getOrDefault("twinCompanionWorkInstruction", "0"));
        String fromPosition = body.getOrDefault("fromPosition", "");
        String toPosition = body.getOrDefault("toPosition", "");
        String containerId = body.getOrDefault("containerId", "");
        String moveKind = body.getOrDefault("moveKind", "");
        String jobPosition = body.getOrDefault("jobPosition", "FWD");
        String isoType = body.getOrDefault("isoType", "");
        String freightKind = body.getOrDefault("freightKind", "");
        String pinning = body.getOrDefault("pinning", "");
        return new WorkInstructionEvent(
                eventType, workInstructionId, workQueueId, fetchChe, workInstructionMoveStage, estimatedMoveTime,
                estimatedCycleTimeSeconds, estimatedRtgCycleTimeSeconds,
                putChe, isTwinFetch, isTwinPut, isTwinCarry, twinCompanionWorkInstruction,
                fromPosition, toPosition, containerId, moveKind, jobPosition,
                isoType, freightKind, pinning);
    }

    /**
     * Accepts newline-delimited JSON: one WI JSON object per line.
     * Processes all as a single batch step.
     */
    private void handleWorkInstructionBatch(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String[] lines = body.split("\n");
            int count = 0;
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                Map<String, String> fields = JsonParser.parseObject(line);
                WorkInstructionEvent event = parseWorkInstructionEvent(fields);
                demoEventProducer.sendWorkInstruction(event);
                count++;
            }
            sendJsonResponse(exchange, 200, "{\"ok\":true,\"count\":" + count + "}");
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

            String workQueueSequence = body.get("workQueueSequence");
            String pointOfWorkName = body.get("pointOfWorkName");
            String bollardPosition = body.get("bollardPosition");
            String workQueueManaged = body.get("workQueueManaged");

            WorkQueueMessage event = new WorkQueueMessage(workQueueId, status, qcMudaSeconds, loadMode,
                    workQueueSequence, pointOfWorkName, bollardPosition, workQueueManaged);
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
                if (playClockLastSentTime != null) {
                    playClockLastSentTime = playClockLastSentTime.plusSeconds(seconds);
                }
                TimeEvent te = new TimeEvent(currentTime);
                return processStepInternal("Tick +" + seconds + "s", te);
            });

            sendJsonResponse(exchange, 200, JsonSerializer.serialize(
                    Map.of("step", result.step(), "sideEffects", result.sideEffects())));
        } catch (Exception e) {
            sendJsonResponse(exchange, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private void handlePlay(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        try {
            Map<String, String> body = readJsonBody(exchange);
            String action = body.getOrDefault("action", "toggle");

            switch (action) {
                case "start" -> {
                    String speedStr = body.getOrDefault("speed", "1");
                    playClockSpeed = Math.max(0.1, Math.min(600, Double.parseDouble(speedStr)));
                    startPlayClock();
                    sendJsonResponse(exchange, 200, "{\"playing\":true,\"speed\":" + playClockSpeed + "}");
                }
                case "stop" -> {
                    stopPlayClock();
                    sendJsonResponse(exchange, 200, "{\"playing\":false}");
                }
                case "speed" -> {
                    String speedStr = body.getOrDefault("speed", "1");
                    playClockSpeed = Math.max(0.1, Math.min(600, Double.parseDouble(speedStr)));
                    sendJsonResponse(exchange, 200, "{\"playing\":" + (playClockThread != null) + ",\"speed\":" + playClockSpeed + "}");
                }
                case "toggle" -> {
                    if (playClockThread != null) {
                        stopPlayClock();
                        sendJsonResponse(exchange, 200, "{\"playing\":false}");
                    } else {
                        String speedStr = body.getOrDefault("speed", "1");
                        playClockSpeed = Math.max(0.1, Math.min(600, Double.parseDouble(speedStr)));
                        startPlayClock();
                        sendJsonResponse(exchange, 200, "{\"playing\":true,\"speed\":" + playClockSpeed + "}");
                    }
                }
                case "status" -> {
                    sendJsonResponse(exchange, 200, "{\"playing\":" + (playClockThread != null) + ",\"speed\":" + playClockSpeed + "}");
                }
                default -> sendJsonResponse(exchange, 400, "{\"error\":\"Unknown action: " + escapeJson(action) + "\"}");
            }
        } catch (Exception e) {
            sendJsonResponse(exchange, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private synchronized void startPlayClock() {
        stopPlayClock();
        playClockLastSentTime = currentTime;
        Thread thread = Thread.ofVirtual().name("play-clock").start(() -> {
            var tickPending = new java.util.concurrent.atomic.AtomicBoolean(false);
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    double speed = playClockSpeed;
                    long intervalMs = Math.max(50, Math.round(1000.0 / speed));
                    Thread.sleep(intervalMs);

                    // Skip if previous tick is still being processed
                    if (tickPending.get()) continue;

                    playClockLastSentTime = playClockLastSentTime.plusSeconds(1);
                    Instant timeToSet = playClockLastSentTime;
                    tickPending.set(true);
                    eventQueue.add(new EngineCommand<>(() -> {
                        try {
                            currentTime = timeToSet;
                            TimeEvent te = new TimeEvent(timeToSet);
                            processStepInternal("Tick +1s", te);
                        } finally {
                            tickPending.set(false);
                        }
                        return null;
                    }, new java.util.concurrent.CompletableFuture<>()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.warning("Play clock error: " + e.getMessage());
                }
            }
        });
        playClockThread = thread;
    }

    private synchronized void stopPlayClock() {
        Thread thread = playClockThread;
        if (thread != null) {
            thread.interrupt();
            playClockThread = null;
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

    /**
     * Sends a QC asset event to Kafka (when enabled) or processes it directly.
     * Used by the schedule viewer "complete" button for QC_LIFT and QC_PLACE actions
     * to flow through the real event path instead of the ActionCompletedEvent shortcut.
     */
    private void handleSimulateQcEvent(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        try {
            Map<String, String> body = readJsonBody(exchange);
            String cheId = requireField(body, "cheId");
            String operationalEvent = requireField(body, "operationalEvent");
            String moveKind = body.getOrDefault("moveKind", "DSCH");

            String json = "{" +
                    "\"move\":\"" + moveKind + "\"," +
                    "\"operationalEvent\":\"" + operationalEvent + "\"," +
                    "\"cheID\":\"" + cheId + "\"," +
                    "\"terminalCode\":\"" + settings.terminalCode() + "\"," +
                    "\"timestamp\":" + java.time.Instant.now().getEpochSecond() +
                    "}";

            if (jsonKafkaProducer != null) {
                String topic = settings.assetEventQcConsumerConfiguration().topic();
                jsonKafkaProducer.send(new org.apache.kafka.clients.producer.ProducerRecord<>(topic, cheId, json));
                sendJsonResponse(exchange, 200, "{\"ok\":true,\"target\":\"kafka\",\"topic\":\"" + escapeJson(topic) + "\"}");
            } else {
                // Kafka disabled — process as direct AssetEvent
                var assetEvent = new com.wonderingwizard.events.AssetEvent(
                        moveKind, operationalEvent, cheId, settings.terminalCode(), java.time.Instant.now());
                StepResult result = processStep("QC asset event: " + cheId + " " + operationalEvent, assetEvent);
                sendJsonResponse(exchange, 200, JsonSerializer.serialize(
                        Map.of("ok", true, "target", "direct", "sideEffects", result.sideEffects())));
            }
        } catch (Exception e) {
            sendJsonResponse(exchange, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    /**
     * Sends an RTG asset event to Kafka (when enabled) or processes it directly.
     * Used by the schedule viewer for RTG_LIFT_FROM_TT and RTG_PLACE_ON_YARD actions.
     */
    private void handleSimulateRtgAssetEvent(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        try {
            Map<String, String> body = readJsonBody(exchange);
            UUID actionId = UUID.fromString(requireField(body, "actionId"));
            long workQueueId = Long.parseLong(requireField(body, "workQueueId"));

            Action action = scheduleRunnerProcessor.getAction(workQueueId, actionId);
            if (action == null) {
                sendJsonResponse(exchange, 404, "{\"error\":\"Action not found\"}");
                return;
            }

            String cheId = action.workInstructions().isEmpty() ? "" : action.workInstructions().getFirst().putChe();
            String operationalEvent = switch (action.actionType()) {
                case RTG_LIFT_FROM_TT -> "RTGliftedContainerfromTruck";
                case RTG_PLACE_ON_YARD -> "RTGplacedContainerOnYard";
                default -> null;
            };
            if (operationalEvent == null) {
                sendJsonResponse(exchange, 400, "{\"error\":\"Unsupported action type for RTG asset event: " + action.actionType() + "\"}");
                return;
            }

            String json = "{" +
                    "\"move\":\"DSCH\"," +
                    "\"operationalEvent\":\"" + operationalEvent + "\"," +
                    "\"cheID\":\"" + cheId + "\"," +
                    "\"terminalCode\":\"" + settings.terminalCode() + "\"," +
                    "\"timestamp\":" + java.time.Instant.now().getEpochSecond() +
                    "}";

            if (jsonKafkaProducer != null) {
                String topic = settings.assetEventRtgConsumerConfiguration().topic();
                jsonKafkaProducer.send(new org.apache.kafka.clients.producer.ProducerRecord<>(topic, cheId, json));
                sendJsonResponse(exchange, 200, "{\"ok\":true,\"target\":\"kafka\"}");
            } else {
                var assetEvent = new com.wonderingwizard.events.AssetEvent(
                        "DSCH", operationalEvent, cheId, settings.terminalCode(), java.time.Instant.now());
                processStep("RTG asset event: " + cheId + " " + operationalEvent, assetEvent);
                sendJsonResponse(exchange, 200, "{\"ok\":true,\"target\":\"direct\"}");
            }
        } catch (Exception e) {
            sendJsonResponse(exchange, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    /**
     * Processes an RTG job operation event directly through the engine.
     * Used by the schedule viewer for RTG_DRIVE and RTG_PLACE_ON_YARD actions.
     */
    private void handleSimulateRtgJobOperation(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        try {
            Map<String, String> body = readJsonBody(exchange);
            UUID actionId = UUID.fromString(requireField(body, "actionId"));
            long workQueueId = Long.parseLong(requireField(body, "workQueueId"));

            Action action = scheduleRunnerProcessor.getAction(workQueueId, actionId);
            if (action == null) {
                sendJsonResponse(exchange, 404, "{\"error\":\"Action not found\"}");
                return;
            }

            String actionCode = switch (action.actionType()) {
                case RTG_DRIVE -> "A";
                case RTG_PLACE_ON_YARD -> "D";
                default -> null;
            };
            if (actionCode == null) {
                sendJsonResponse(exchange, 400, "{\"error\":\"Unsupported action type for RTG job operation: " + action.actionType() + "\"}");
                return;
            }

            // Send one job operation per work instruction (handles twins)
            for (var wi : action.workInstructions()) {
                String cheId = wi.putChe();
                String wiId = String.valueOf(wi.workInstructionId());
                String containerId = wi.containerId() != null ? wi.containerId() : "";

                if (avroKafkaProducer != null) {
                    // Send to Kafka as Avro
                    var schema = loadJobOperationSchema();
                    var jobOp = new org.apache.avro.generic.GenericData.Record(schema);
                    jobOp.put("containerId", containerId);
                    jobOp.put("workInstructionId", wiId);
                    jobOp.put("action", actionCode);
                    jobOp.put("cheId", cheId);
                    jobOp.put("cheType", "RTG");
                    jobOp.put("yardSlot", null);
                    jobOp.put("containerTruckPosition", null);
                    String topic = settings.jobOperationConsumerConfiguration().topic();
                    avroKafkaProducer.send(new org.apache.kafka.clients.producer.ProducerRecord<>(topic, cheId, jobOp));
                } else {
                    var jobOpEvent = new com.wonderingwizard.events.JobOperationEvent(
                            actionCode, cheId, "RTG", wiId, containerId);
                    processStep("RTG job op '" + actionCode + "': " + cheId + " WI " + wiId, jobOpEvent);
                }
            }
            sendJsonResponse(exchange, 200, "{\"ok\":true,\"target\":\"" + (avroKafkaProducer != null ? "kafka" : "direct") + "\"}");
        } catch (Exception e) {
            sendJsonResponse(exchange, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private org.apache.avro.Schema jobOperationSchema;

    private org.apache.avro.Schema loadJobOperationSchema() {
        if (jobOperationSchema == null) {
            try (var is = getClass().getResourceAsStream("/schemas/JobOperation.avsc")) {
                if (is == null) throw new IllegalStateException("JobOperation schema not found");
                jobOperationSchema = new org.apache.avro.Schema.Parser().parse(is);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Failed to load JobOperation schema", e);
            }
        }
        return jobOperationSchema;
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
                scheduleViewCache.clear();
                scheduleViewCacheIndex = 0;
                wqMessageCache.clear();

                for (Step step : stepsToReplay) {
                    if (step.event() instanceof SystemTimeSet sts) {
                        currentTime = sts.timestamp();
                    }
                    if (step.event() instanceof WorkQueueMessage wqMsg) {
                        wqMessageCache.put(wqMsg.workQueueId(), wqMsg);
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
