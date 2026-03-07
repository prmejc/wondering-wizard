package com.wonderingwizard.server;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.DeviceType;
import com.wonderingwizard.domain.takt.Takt;
import com.wonderingwizard.engine.Engine;
import com.wonderingwizard.engine.Event;
import com.wonderingwizard.engine.EventProcessingEngine;
import com.wonderingwizard.engine.EventPropagatingEngine;
import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.ActionCompletedEvent;
import com.wonderingwizard.events.TimeEvent;
import com.wonderingwizard.events.WorkInstructionEvent;
import com.wonderingwizard.events.WorkInstructionStatus;
import com.wonderingwizard.events.WorkQueueMessage;
import com.wonderingwizard.events.WorkQueueStatus;
import com.wonderingwizard.processors.ScheduleRunnerProcessor;
import com.wonderingwizard.processors.TimeAlarmProcessor;
import com.wonderingwizard.processors.WorkQueueProcessor;
import com.wonderingwizard.sideeffects.ActionActivated;
import com.wonderingwizard.sideeffects.ActionCompleted;
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
    private final List<Step> steps = new ArrayList<>();
    private final Instant initialTime = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private Instant currentTime = initialTime;
    private HttpServer httpServer;

    /**
     * A numbered step representing a user-initiated event and its resulting side effects.
     *
     * @param stepNumber the step number (1-based)
     * @param description a short description of the event
     * @param event the event that was processed
     * @param sideEffects the side effects produced
     * @param engineHistoryDelta the number of engine history entries consumed by this step
     */
    public record Step(int stepNumber, String description, Event event,
                       List<SideEffect> sideEffects, int engineHistoryDelta) {}

    /** Action status for schedule visualization. */
    public enum ActionState {
        PENDING, ACTIVE, COMPLETED
    }

    /** Takt status for schedule visualization. */
    public enum TaktState {
        WAITING, ACTIVE, COMPLETED
    }

    /** Schedule view for the API state response. */
    public record ScheduleView(String workQueueId, boolean active, Instant estimatedMoveTime,
                                List<TaktView> takts) {}

    /** Takt view within a schedule. */
    public record TaktView(String name, TaktState status, Instant plannedStartTime,
                            Instant estimatedStartTime, Instant actualStartTime,
                            int durationSeconds, List<ActionView> actions) {}

    /** Action view within a takt. */
    public record ActionView(UUID id, DeviceType deviceType, String description,
                              ActionState status, Set<UUID> dependsOn, int containerIndex,
                              int durationSeconds) {}

    public DemoServer() {
        EventProcessingEngine baseEngine = new EventProcessingEngine();
        this.engine = new EventPropagatingEngine(baseEngine);
        engine.register(new TimeAlarmProcessor());
        engine.register(new WorkQueueProcessor());
        engine.register(new ScheduleRunnerProcessor());
    }

    DemoServer(Engine engine) {
        this.engine = engine;
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
        httpServer.createContext("/api/tick", this::handleTick);
        httpServer.createContext("/api/action-completed", this::handleActionCompleted);
        httpServer.createContext("/api/step-back-to", this::handleStepBackTo);
        httpServer.start();
        logger.info("Demo server started on port " + port);
    }

    /**
     * Stops the HTTP server.
     */
    public void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
            logger.info("Demo server stopped");
        }
    }

    /**
     * Processes an event and records it as a numbered step.
     *
     * @param description short description of the event
     * @param event the event to process
     * @return the list of side effects produced
     */
    public List<SideEffect> processStep(String description, Event event) {
        int historyBefore = engine.getHistorySize();
        List<SideEffect> sideEffects = engine.processEvent(event);
        int historyDelta = engine.getHistorySize() - historyBefore;

        int stepNumber = steps.size() + 1;
        steps.add(new Step(stepNumber, description, event, sideEffects, historyDelta));

        return sideEffects;
    }

    /**
     * Steps back to the target step number, undoing all steps after it.
     *
     * @param targetStep the step number to revert to (1-based, 0 means undo all)
     * @return true if step-back was successful
     */
    public boolean stepBackTo(int targetStep) {
        if (targetStep < 0 || targetStep >= steps.size()) {
            return false;
        }

        // Calculate total engine step-backs needed
        while (steps.size() > targetStep) {
            Step last = steps.remove(steps.size() - 1);
            for (int i = 0; i < last.engineHistoryDelta(); i++) {
                engine.stepBack();
            }
        }

        return true;
    }

    /**
     * Builds the current state for the API response, including schedules with action statuses.
     *
     * @return the state as a JSON-serializable map
     */
    public Map<String, Object> getState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("currentTime", currentTime);
        state.put("steps", steps);
        state.put("schedules", buildScheduleViews());
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
        // Derive schedule state from accumulated side effects
        Map<String, ScheduleViewBuilder> builders = new LinkedHashMap<>();

        for (Step step : steps) {
            for (SideEffect se : step.sideEffects()) {
                switch (se) {
                    case ScheduleCreated created -> {
                        ScheduleViewBuilder builder = new ScheduleViewBuilder(
                                created.workQueueId(), true, created.estimatedMoveTime());
                        for (Takt takt : created.takts()) {
                            List<ActionView> actionViews = new ArrayList<>();
                            for (Action action : takt.actions()) {
                                actionViews.add(new ActionView(
                                        action.id(), action.deviceType(), action.description(),
                                        ActionState.PENDING, action.dependsOn(), action.containerIndex(),
                                        action.durationSeconds()));
                            }
                            builder.takts.add(new TaktView(takt.name(), TaktState.WAITING,
                                    takt.plannedStartTime(), takt.estimatedStartTime(), null,
                                    takt.durationSeconds(), actionViews));
                        }
                        builders.put(created.workQueueId(), builder);
                    }
                    case ScheduleAborted aborted ->
                            builders.remove(aborted.workQueueId());
                    case TaktActivated taktActivated -> {
                        ScheduleViewBuilder builder = builders.get(taktActivated.workQueueId());
                        if (builder != null) {
                            builder.setTaktStatus(taktActivated.taktName(), TaktState.ACTIVE);
                            builder.setActualStartTime(taktActivated.taktName(), taktActivated.activatedAt());
                        }
                    }
                    case TaktCompleted taktCompleted -> {
                        ScheduleViewBuilder builder = builders.get(taktCompleted.workQueueId());
                        if (builder != null) {
                            builder.setTaktStatus(taktCompleted.taktName(), TaktState.COMPLETED);
                        }
                    }
                    case ActionActivated activated -> {
                        ScheduleViewBuilder builder = builders.get(activated.workQueueId());
                        if (builder != null) {
                            builder.setActionStatus(activated.actionId(), ActionState.ACTIVE);
                        }
                    }
                    case ActionCompleted completed -> {
                        ScheduleViewBuilder builder = builders.get(completed.workQueueId());
                        if (builder != null) {
                            builder.setActionStatus(completed.actionId(), ActionState.COMPLETED);
                        }
                    }
                    default -> { /* Other side effects don't affect schedule view */ }
                }
            }
        }

        return builders.values().stream()
                .map(ScheduleViewBuilder::build)
                .toList();
    }

    private static class ScheduleViewBuilder {
        final String workQueueId;
        final boolean active;
        final Instant estimatedMoveTime;
        final List<TaktView> takts = new ArrayList<>();
        final Map<UUID, ActionState> actionStates = new HashMap<>();
        final Map<String, TaktState> taktStates = new HashMap<>();
        final Map<String, Instant> actualStartTimes = new HashMap<>();

        ScheduleViewBuilder(String workQueueId, boolean active, Instant estimatedMoveTime) {
            this.workQueueId = workQueueId;
            this.active = active;
            this.estimatedMoveTime = estimatedMoveTime;
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

        ScheduleView build() {
            List<TaktView> updatedTakts = new ArrayList<>();
            for (TaktView takt : takts) {
                TaktState taktState = taktStates.getOrDefault(takt.name(), takt.status());
                Instant actualStartTime = actualStartTimes.get(takt.name());
                List<ActionView> updatedActions = new ArrayList<>();
                for (ActionView action : takt.actions()) {
                    ActionState state = actionStates.getOrDefault(action.id(), action.status());
                    updatedActions.add(new ActionView(
                            action.id(), action.deviceType(), action.description(),
                            state, action.dependsOn(), action.containerIndex(),
                            action.durationSeconds()));
                }
                updatedTakts.add(new TaktView(takt.name(), taktState,
                        takt.plannedStartTime(), takt.estimatedStartTime(), actualStartTime,
                        takt.durationSeconds(), updatedActions));
            }
            return new ScheduleView(workQueueId, active, estimatedMoveTime, updatedTakts);
        }
    }

    // --- HTTP Handlers ---

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

    private void handleGetState(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }
        sendJsonResponse(exchange, 200, JsonSerializer.serialize(getState()));
    }

    private void handleWorkInstruction(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        try {
            Map<String, String> body = readJsonBody(exchange);
            String workInstructionId = requireField(body, "workInstructionId");
            String workQueueId = requireField(body, "workQueueId");
            String fetchChe = body.getOrDefault("fetchChe", "");
            String statusStr = body.getOrDefault("status", "PENDING");
            WorkInstructionStatus status = WorkInstructionStatus.valueOf(statusStr);
            String estimatedMoveTimeStr = body.get("estimatedMoveTime");
            Instant estimatedMoveTime = estimatedMoveTimeStr != null
                    ? Instant.parse(estimatedMoveTimeStr) : null;

            String estimatedCycleTimeStr = body.getOrDefault("estimatedCycleTimeSeconds", "0");
            int estimatedCycleTimeSeconds = Integer.parseInt(estimatedCycleTimeStr);

            WorkInstructionEvent event = new WorkInstructionEvent(
                    workInstructionId, workQueueId, fetchChe, status, estimatedMoveTime, estimatedCycleTimeSeconds);
            List<SideEffect> effects = processStep("WorkInstruction: " + workInstructionId, event);

            sendJsonResponse(exchange, 200, JsonSerializer.serialize(
                    Map.of("step", steps.get(steps.size() - 1), "sideEffects", effects)));
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
            String workQueueId = requireField(body, "workQueueId");
            String statusStr = requireField(body, "status");
            WorkQueueStatus status = WorkQueueStatus.valueOf(statusStr);

            String qcMudaStr = body.getOrDefault("qcMudaSeconds", "0");
            int qcMudaSeconds = Integer.parseInt(qcMudaStr);

            WorkQueueMessage event = new WorkQueueMessage(workQueueId, status, qcMudaSeconds);
            List<SideEffect> effects = processStep("WorkQueue " + statusStr + ": " + workQueueId, event);

            sendJsonResponse(exchange, 200, JsonSerializer.serialize(
                    Map.of("step", steps.get(steps.size() - 1), "sideEffects", effects)));
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

            currentTime = currentTime.plusSeconds(seconds);
            TimeEvent event = new TimeEvent(currentTime);
            List<SideEffect> effects = processStep("Tick +" + seconds + "s", event);

            sendJsonResponse(exchange, 200, JsonSerializer.serialize(
                    Map.of("step", steps.get(steps.size() - 1), "sideEffects", effects)));
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
            String workQueueId = requireField(body, "workQueueId");
            UUID actionId = UUID.fromString(actionIdStr);

            ActionCompletedEvent event = new ActionCompletedEvent(actionId, workQueueId);
            List<SideEffect> effects = processStep("Complete action: " + actionIdStr.substring(0, 8), event);

            sendJsonResponse(exchange, 200, JsonSerializer.serialize(
                    Map.of("step", steps.get(steps.size() - 1), "sideEffects", effects)));
        } catch (Exception e) {
            sendJsonResponse(exchange, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
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

            // Recompute currentTime from remaining steps
            boolean success = stepBackTo(targetStep);

            if (success) {
                // Recalculate current time from remaining tick steps
                currentTime = initialTime;
                for (Step step : steps) {
                    if (step.event() instanceof TimeEvent te) {
                        currentTime = te.timestamp();
                    }
                }
                sendJsonResponse(exchange, 200, JsonSerializer.serialize(getState()));
            } else {
                sendJsonResponse(exchange, 400, "{\"error\":\"Invalid target step\"}");
            }
        } catch (Exception e) {
            sendJsonResponse(exchange, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
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
