package com.cloudqueryx.multimodal;

import com.cloudqueryx.vector.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class MultimodalStore {

    private final Map<String, MultimodalAsset> assets = new ConcurrentHashMap<>();
    private final VectorStore vectorStore;

    public MultimodalStore(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public void store(MultimodalAsset asset) {
        assets.put(asset.id(), asset);
        if (asset.embedding() != null) {
            Map<String, Object> meta = new HashMap<>(asset.metadata());
            meta.put("modality", asset.modality().name());
            meta.put("mimeType", asset.mimeType() != null ? asset.mimeType() : "");
            if (asset.title() != null) meta.put("title", asset.title());

            VectorRecord vr = new VectorRecord(
                    asset.id(), "multimodal", asset.embedding(), meta,
                    asset.extractedText(), asset.createdAt(), null
            );
            vectorStore.insert("multimodal", vr);
        }
    }

    public MultimodalAsset get(String id) {
        return assets.get(id);
    }

    public boolean delete(String id) {
        MultimodalAsset removed = assets.remove(id);
        if (removed == null) return false;
        vectorStore.delete("multimodal", id);
        return true;
    }

    public List<MultimodalAsset> findByUser(String userId) {
        return assets.values().stream()
                .filter(a -> userId.equals(a.userId()))
                .collect(Collectors.toList());
    }

    public List<MultimodalAsset> findByModality(ModalityType modality) {
        return assets.values().stream()
                .filter(a -> a.modality() == modality)
                .collect(Collectors.toList());
    }

    public List<MultimodalAsset> search(float[] queryEmbedding, int topK) {
        List<VectorSearchResult> results = vectorStore.search(
                "multimodal", queryEmbedding, topK, SimilarityMetric.COSINE);
        return results.stream()
                .map(vsr -> assets.get(vsr.record().id()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public List<MultimodalAsset> search(float[] queryEmbedding, int topK, ModalityType modality) {
        List<VectorSearchResult> results = vectorStore.search(
                "multimodal", queryEmbedding, topK, SimilarityMetric.COSINE,
                vr -> modality.name().equals(vr.metadata().get("modality")));
        return results.stream()
                .map(vsr -> assets.get(vsr.record().id()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public int size() {
        return assets.size();
    }
}
