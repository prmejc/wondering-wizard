package com.wonderingwizard.server;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages Server-Sent Events (SSE) connections and broadcasts events to all connected clients.
 * <p>
 * SSE is a simple protocol over HTTP for server-to-client push:
 * <ul>
 *   <li>Response uses {@code Content-Type: text/event-stream}</li>
 *   <li>Messages are formatted as {@code event: <type>\ndata: <json>\n\n}</li>
 *   <li>The connection stays open indefinitely</li>
 *   <li>Browsers reconnect automatically via the {@code EventSource} API</li>
 * </ul>
 */
public class SseConnectionManager {

    private static final Logger logger = Logger.getLogger(SseConnectionManager.class.getName());

    private final CopyOnWriteArrayList<SseConnection> connections = new CopyOnWriteArrayList<>();
    private volatile boolean keepaliveRunning;

    private record SseConnection(HttpExchange exchange, OutputStream outputStream) {}

    /**
     * Registers a new SSE connection. Sets up response headers and sends the initial
     * response line. The connection remains open until the client disconnects.
     */
    public void addConnection(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, 0); // chunked

        OutputStream os = exchange.getResponseBody();
        SseConnection connection = new SseConnection(exchange, os);
        connections.add(connection);

        logger.info("SSE client connected (" + connections.size() + " total)");
    }

    /**
     * Broadcasts an event to all connected SSE clients.
     * Clients that have disconnected are automatically removed.
     *
     * @param eventType the SSE event type (used by EventSource.addEventListener on the client)
     * @param jsonData the JSON payload to send
     */
    public void broadcast(String eventType, String jsonData) {
        if (connections.isEmpty()) {
            return;
        }

        byte[] message = formatSseMessage(eventType, jsonData);

        for (SseConnection connection : connections) {
            try {
                connection.outputStream().write(message);
                connection.outputStream().flush();
            } catch (IOException e) {
                logger.fine("SSE client disconnected, removing");
                removeConnection(connection);
            }
        }
    }

    /**
     * Starts a keepalive thread that sends comment lines every 30 seconds
     * to prevent proxy/load-balancer timeouts.
     */
    public void startKeepalive() {
        if (keepaliveRunning) {
            return;
        }
        keepaliveRunning = true;
        Thread.ofVirtual().name("sse-keepalive").start(() -> {
            byte[] keepalive = ": keepalive\n\n".getBytes(StandardCharsets.UTF_8);
            while (keepaliveRunning) {
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                for (SseConnection connection : connections) {
                    try {
                        connection.outputStream().write(keepalive);
                        connection.outputStream().flush();
                    } catch (IOException e) {
                        removeConnection(connection);
                    }
                }
            }
        });
    }

    /**
     * Stops the keepalive thread and closes all connections.
     */
    public void stop() {
        keepaliveRunning = false;
        for (SseConnection connection : connections) {
            removeConnection(connection);
        }
    }

    /**
     * Returns the number of currently connected clients.
     */
    public int getConnectionCount() {
        return connections.size();
    }

    private void removeConnection(SseConnection connection) {
        connections.remove(connection);
        try {
            connection.outputStream().close();
        } catch (IOException ignored) {
            // Already disconnected
        }
        try {
            connection.exchange().close();
        } catch (Exception ignored) {
            // Already closed
        }
    }

    private static byte[] formatSseMessage(String eventType, String data) {
        String message = "event: " + eventType + "\ndata: " + data + "\n\n";
        return message.getBytes(StandardCharsets.UTF_8);
    }
}
