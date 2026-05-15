package com.cloudqueryx.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

public class OpenAIEmbeddingService implements EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(OpenAIEmbeddingService.class);
    private static final String ENDPOINT = "https://api.openai.com/v1/embeddings";

    private final String apiKey;
    private final String model;
    private final int dimensions;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public OpenAIEmbeddingService(String apiKey, int dimensions) {
        this(apiKey, "text-embedding-3-small", dimensions);
    }

    public OpenAIEmbeddingService(String apiKey, String model, int dimensions) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("CLOUDQUERYX_OPENAI_API_KEY required for OpenAI embeddings");
        }
        this.apiKey = apiKey;
        this.model = model;
        this.dimensions = dimensions;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper();
        log.info("OpenAI embedding service initialized (model={}, dim={})", model, dimensions);
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return new float[dimensions];
        }

        try {
            String body = mapper.writeValueAsString(Map.of(
                    "input", text,
                    "model", model,
                    "dimensions", dimensions
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("OpenAI API error {}: {}", response.statusCode(), response.body());
                return new float[dimensions];
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode embeddingNode = root.get("data").get(0).get("embedding");

            float[] embedding = new float[dimensions];
            for (int i = 0; i < Math.min(embeddingNode.size(), dimensions); i++) {
                embedding[i] = (float) embeddingNode.get(i).asDouble();
            }
            return embedding;

        } catch (Exception e) {
            log.error("OpenAI embedding failed: {}", e.getMessage());
            return new float[dimensions];
        }
    }

    @Override
    public int dimensions() {
        return dimensions;
    }
}
