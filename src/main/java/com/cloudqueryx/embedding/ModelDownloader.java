package com.cloudqueryx.embedding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

public final class ModelDownloader {

    private static final Logger log = LoggerFactory.getLogger(ModelDownloader.class);

    private static final String BASE_URL =
            "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main";

    private static final String[][] FILES = {
            {"onnx/model.onnx", "model.onnx"},
            {"tokenizer.json", "tokenizer.json"}
    };

    private ModelDownloader() {}

    public static void ensureModel(Path modelDir) {
        boolean allExist = true;
        for (String[] file : FILES) {
            if (!Files.exists(modelDir.resolve(file[1]))) {
                allExist = false;
                break;
            }
        }
        if (allExist) {
            log.info("Embedding model already downloaded at {}", modelDir);
            return;
        }

        log.info("Downloading embedding model (all-MiniLM-L6-v2) to {}", modelDir);
        try {
            Files.createDirectories(modelDir);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create model directory: " + modelDir, e);
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();

        for (String[] file : FILES) {
            String remotePath = file[0];
            String localName = file[1];
            Path target = modelDir.resolve(localName);

            if (Files.exists(target)) {
                log.info("  {} already exists, skipping", localName);
                continue;
            }

            String url = BASE_URL + "/" + remotePath;
            log.info("  Downloading {} ...", localName);

            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofMinutes(10))
                        .GET()
                        .build();

                HttpResponse<InputStream> response = client.send(request,
                        HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() != 200) {
                    throw new IOException("HTTP " + response.statusCode() + " for " + url);
                }

                Path temp = target.resolveSibling(localName + ".tmp");
                try (InputStream is = response.body()) {
                    long bytes = Files.copy(is, temp, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                    log.info("  Downloaded {} ({} MB)", localName,
                            String.format("%.1f", bytes / 1_000_000.0));
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to download " + localName + ": " + e.getMessage(), e);
            }
        }

        log.info("Embedding model download complete");
    }
}
