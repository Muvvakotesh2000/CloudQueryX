package com.cloudqueryx.web.api;

import com.cloudqueryx.context.runtime.TraceEvent;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TraceEventHub {
    private final Map<String, List<TraceEvent>> backlog = new ConcurrentHashMap<>();
    private final Map<String, List<SseClient>> clients = new ConcurrentHashMap<>();

    public void publish(TraceEvent event) {
        backlog.computeIfAbsent(event.requestId(), ignored -> Collections.synchronizedList(new ArrayList<>()))
                .add(event);
        List<SseClient> subscribers = clients.get(event.requestId());
        if (subscribers == null) return;
        List<SseClient> dead = new ArrayList<>();
        for (SseClient client : subscribers) {
            if (!client.send(event)) dead.add(client);
        }
        subscribers.removeAll(dead);
    }

    public void close(String requestId) {
        List<SseClient> subscribers = clients.remove(requestId);
        if (subscribers == null) return;
        for (SseClient client : subscribers) client.close();
    }

    public void stream(HttpExchange ex, String requestId) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0);
        SseClient client = new SseClient(ex.getResponseBody());
        clients.computeIfAbsent(requestId, ignored -> Collections.synchronizedList(new ArrayList<>()))
                .add(client);
        List<TraceEvent> existing = backlog.getOrDefault(requestId, List.of());
        for (TraceEvent event : existing) {
            if (!client.send(event)) {
                clients.getOrDefault(requestId, List.of()).remove(client);
                client.close();
                return;
            }
        }
    }

    private static class SseClient {
        private final OutputStream out;

        private SseClient(OutputStream out) {
            this.out = out;
        }

        synchronized boolean send(TraceEvent event) {
            try {
                String payload = "event: " + event.stage() + "\n"
                        + "data: " + JsonUtil.toJson(event) + "\n\n";
                out.write(payload.getBytes(StandardCharsets.UTF_8));
                out.flush();
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        void close() {
            try {
                out.close();
            } catch (IOException ignored) {
            }
        }
    }
}
