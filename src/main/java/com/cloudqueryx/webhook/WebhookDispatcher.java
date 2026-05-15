package com.cloudqueryx.webhook;

import com.cloudqueryx.repository.WebhookRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final WebhookRepository webhookRepo;
    private final HttpClient httpClient;
    private final ExecutorService executor;

    public WebhookDispatcher(WebhookRepository webhookRepo) {
        this.webhookRepo = webhookRepo;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.executor = Executors.newFixedThreadPool(2);
    }

    public void dispatch(String databaseId, String eventType, Map<String, Object> payload) {
        executor.submit(() -> {
            try {
                List<WebhookRepository.WebhookRow> hooks = webhookRepo.getActiveForEvent(databaseId, eventType);
                if (hooks.isEmpty()) return;

                Map<String, Object> body = Map.of(
                        "event", eventType,
                        "timestamp", Instant.now().toString(),
                        "databaseId", databaseId,
                        "data", payload
                );
                String json = mapper.writeValueAsString(body);

                for (WebhookRepository.WebhookRow hook : hooks) {
                    sendWebhook(hook, json);
                }
            } catch (Exception e) {
                log.error("Webhook dispatch failed for event {}: {}", eventType, e.getMessage());
            }
        });
    }

    private void sendWebhook(WebhookRepository.WebhookRow hook, String json) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(hook.url()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "CloudQueryX-Webhook/1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(json));

            if (hook.secret() != null && !hook.secret().isBlank()) {
                String signature = computeHmac(json, hook.secret());
                builder.header("X-CloudQueryX-Signature", "sha256=" + signature);
            }

            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.debug("Webhook delivered to {} ({})", hook.url(), response.statusCode());
            } else {
                log.warn("Webhook to {} returned {}", hook.url(), response.statusCode());
            }
        } catch (Exception e) {
            log.warn("Webhook delivery failed to {}: {}", hook.url(), e.getMessage());
        }
    }

    private String computeHmac(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes()));
        } catch (Exception e) {
            log.error("HMAC computation failed", e);
            return "";
        }
    }

    public WebhookRepository getWebhookRepo() {
        return webhookRepo;
    }

    public void shutdown() {
        executor.shutdown();
    }
}
