package com.cloudqueryx.embedding;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

public class LocalEmbeddingService implements EmbeddingService, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LocalEmbeddingService.class);
    private static final int MAX_SEQ_LENGTH = 256;
    private static final int DIMENSIONS = 384;

    private final OrtEnvironment env;
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;

    public LocalEmbeddingService(Path modelDir) {
        try {
            log.info("Loading embedding model from {}", modelDir);

            this.env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(Math.min(4, Runtime.getRuntime().availableProcessors()));
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);

            this.session = env.createSession(modelDir.resolve("model.onnx").toString(), opts);

            Map<String, String> tokenizerOpts = Map.of(
                    "maxLength", String.valueOf(MAX_SEQ_LENGTH),
                    "truncation", "true",
                    "padding", "MAX_LENGTH"
            );
            this.tokenizer = HuggingFaceTokenizer.newInstance(
                    modelDir.resolve("tokenizer.json"), tokenizerOpts);

            log.info("Embedding model loaded (dim={}, maxSeq={})", DIMENSIONS, MAX_SEQ_LENGTH);

            // Warm up with a dummy inference
            embed("warmup");
            log.info("Embedding model warmed up");

        } catch (OrtException | IOException e) {
            throw new RuntimeException("Failed to load embedding model: " + e.getMessage(), e);
        }
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return new float[DIMENSIONS];
        }

        try {
            Encoding encoding = tokenizer.encode(text);
            long[] inputIds = encoding.getIds();
            long[] attentionMask = encoding.getAttentionMask();
            long[] tokenTypeIds = encoding.getTypeIds();

            int len = Math.min(inputIds.length, MAX_SEQ_LENGTH);
            long[][] idsArr = {Arrays.copyOf(inputIds, len)};
            long[][] maskArr = {Arrays.copyOf(attentionMask, len)};
            long[][] typeArr = {Arrays.copyOf(tokenTypeIds, len)};

            try (OnnxTensor idsTensor = OnnxTensor.createTensor(env, idsArr);
                 OnnxTensor maskTensor = OnnxTensor.createTensor(env, maskArr);
                 OnnxTensor typeTensor = OnnxTensor.createTensor(env, typeArr)) {

                Map<String, OnnxTensor> inputs = Map.of(
                        "input_ids", idsTensor,
                        "attention_mask", maskTensor,
                        "token_type_ids", typeTensor
                );

                try (OrtSession.Result result = session.run(inputs)) {
                    float[][][] output = (float[][][]) result.get(0).getValue();
                    return meanPoolAndNormalize(output[0], maskArr[0], len);
                }
            }
        } catch (OrtException e) {
            log.error("Embedding failed for text (length {}): {}", text.length(), e.getMessage());
            return new float[DIMENSIONS];
        }
    }

    private float[] meanPoolAndNormalize(float[][] tokenEmbeddings, long[] mask, int len) {
        float[] pooled = new float[DIMENSIONS];
        int count = 0;
        for (int i = 0; i < len; i++) {
            if (mask[i] == 1) {
                for (int j = 0; j < DIMENSIONS; j++) {
                    pooled[j] += tokenEmbeddings[i][j];
                }
                count++;
            }
        }
        if (count > 0) {
            for (int j = 0; j < DIMENSIONS; j++) {
                pooled[j] /= count;
            }
        }

        // L2 normalize
        float norm = 0;
        for (float v : pooled) norm += v * v;
        norm = (float) Math.sqrt(norm);
        if (norm > 1e-12f) {
            for (int j = 0; j < DIMENSIONS; j++) {
                pooled[j] /= norm;
            }
        }
        return pooled;
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    @Override
    public void close() {
        try {
            if (session != null) session.close();
            if (tokenizer != null) tokenizer.close();
        } catch (OrtException e) {
            log.warn("Error closing ONNX session: {}", e.getMessage());
        }
    }
}
