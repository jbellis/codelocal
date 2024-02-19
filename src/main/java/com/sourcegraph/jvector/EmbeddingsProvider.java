package com.sourcegraph.jvector;

public interface EmbeddingsProvider {
    float[] getEmbedding(String body);
}
