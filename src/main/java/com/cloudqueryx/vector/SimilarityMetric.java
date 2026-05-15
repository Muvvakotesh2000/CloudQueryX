package com.cloudqueryx.vector;

public enum SimilarityMetric {
    COSINE {
        @Override
        public float compute(float[] a, float[] b) {
            float dot = 0, normA = 0, normB = 0;
            for (int i = 0; i < a.length; i++) {
                dot += a[i] * b[i];
                normA += a[i] * a[i];
                normB += b[i] * b[i];
            }
            float denom = (float) (Math.sqrt(normA) * Math.sqrt(normB));
            return denom == 0 ? 0 : dot / denom;
        }
    },
    DOT_PRODUCT {
        @Override
        public float compute(float[] a, float[] b) {
            float dot = 0;
            for (int i = 0; i < a.length; i++) {
                dot += a[i] * b[i];
            }
            return dot;
        }
    },
    EUCLIDEAN {
        @Override
        public float compute(float[] a, float[] b) {
            float sum = 0;
            for (int i = 0; i < a.length; i++) {
                float diff = a[i] - b[i];
                sum += diff * diff;
            }
            return 1.0f / (1.0f + (float) Math.sqrt(sum));
        }
    };

    public abstract float compute(float[] a, float[] b);
}
