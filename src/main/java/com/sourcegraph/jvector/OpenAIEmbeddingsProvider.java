package com.sourcegraph.jvector;

import com.theokanning.openai.embedding.EmbeddingRequest;
import com.theokanning.openai.service.OpenAiService;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class OpenAIEmbeddingsProvider implements EmbeddingsProvider {
    private final OpenAiService service;

    public OpenAIEmbeddingsProvider() {
        // read the key from ~/.config
        String key;
        try {
            // TODO use a "real" codelocal config file?
            key = Files.readString(Path.of(System.getProperty("user.home"), ".config", "openai", "openai.key")).trim();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        service = new OpenAiService(key);
    }

    @Override
    public float[] getEmbedding(String body) {
        var er = EmbeddingRequest.builder()
                .model("text-embedding-3-small")
                .input(List.of(body))
                .build();
        var r = service.createEmbeddings(er);
        List<Double> embedding = r.getData().get(0).getEmbedding();
        float[] v = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            v[i] = embedding.get(i).floatValue();
        }
        return v;
    }
}
