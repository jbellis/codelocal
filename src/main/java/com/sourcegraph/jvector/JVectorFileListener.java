package com.sourcegraph.jvector;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileEvent;
import com.intellij.openapi.vfs.VirtualFileListener;
import com.intellij.openapi.vfs.VirtualFileMoveEvent;
import com.intellij.openapi.diagnostic.Logger;
import io.github.jbellis.jvector.disk.SimpleMappedReader;
import io.github.jbellis.jvector.graph.GraphIndexBuilder;
import io.github.jbellis.jvector.graph.ListRandomAccessVectorValues;
import io.github.jbellis.jvector.vector.VectorEncoding;
import io.github.jbellis.jvector.vector.VectorSimilarityFunction;
import org.jetbrains.annotations.NotNull;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Serializer;

import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class JVectorFileListener implements VirtualFileListener, AutoCloseable {
    private static final Logger log = Logger.getInstance(JVectorFileListener.class);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private final String projectName;

    private final DB db;
    private final Map<Integer, String> chunksByOrdinal;
    private final Map<String, int[]> ordinalsByFile;

    private final GraphIndexBuilder<float[]> builder;
    private final String graphIndexPath;
    private final ArrayList<float[]> vectors;
    private final ListRandomAccessVectorValues ravv;
    private final EmbeddingsProvider embeddingsProvider;
    private boolean dirty;

    public JVectorFileListener(Project project) {
        projectName = project.getName();
        log.debug("JVectorFileListener create for {}", projectName);

        vectors = new ArrayList<>();
        ravv = new ListRandomAccessVectorValues(vectors, 1536);
        builder = new GraphIndexBuilder<>(ravv, VectorEncoding.FLOAT32, VectorSimilarityFunction.DOT_PRODUCT, 16, 100, 1.2f, 1.2f);
        embeddingsProvider = new OpenAIEmbeddingsProvider();

        var cachePath = PathManager.getSystemPath() + File.pathSeparator + "jvector" + File.pathSeparator + projectName;
        var mapDBPath = cachePath + File.pathSeparator + "map.db";
        db = DBMaker.fileDB(mapDBPath).fileMmapEnable().make();
        chunksByOrdinal = db.hashMap("chunksByOrdinal", Serializer.INTEGER, Serializer.STRING).createOrOpen();
        ordinalsByFile = db.hashMap("ordinalsByFile", Serializer.STRING, Serializer.INT_ARRAY).createOrOpen();
        graphIndexPath = cachePath + File.pathSeparator + "jvector.db";
        if (Files.exists(new File(graphIndexPath).toPath())) {
            try {
                builder.load(new SimpleMappedReader(graphIndexPath));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        scheduler.schedule(this::save, 1, java.util.concurrent.TimeUnit.MINUTES);
    }

    public void save() {
        if (!dirty) {
            return;
        }

        log.debug("JVectorFileListener save() for {}", projectName);
        builder.cleanup();
        var g = builder.getGraph();
        try {
            DataOutput out = new DataOutputStream(Files.newOutputStream(new File(graphIndexPath).toPath()));
            g.save(out);
            dirty = false;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static class Chunk {
        public final String body;
        public final float[] embedding;

        private Chunk(String body, float[] embedding) {
            this.body = body;
            this.embedding = embedding;
        }
    }

    private @NotNull List<Chunk> computeEmbeddings(VirtualFile file) {
        var chunks = new ArrayList<Chunk>();
        try {
            var text = new String(file.contentsToByteArray());
            for (var body: chunkify(text)) {
                var embedding = embeddingsProvider.getEmbedding(body);
                chunks.add(new Chunk(body, embedding));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return chunks;
    }

    private List<String> chunkify(String text) {
        return null;
    }

    @Override
    public void contentsChanged(@NotNull VirtualFileEvent event) {
        removeEmbeddings(event.getFile());
        updateEmbeddings(event.getFile());
        dirty = true;
    }

    private void updateEmbeddings(@NotNull VirtualFile file) {
        var chunks = computeEmbeddings(file);
        var ordinals = new int[chunks.size()];
        for (int i = 0; i < chunks.size(); i++) {
            var chunk = chunks.get(i);
            int ordinal = vectors.size();
            vectors.add(chunk.embedding);
            builder.addGraphNode(ordinal, ravv);
            chunksByOrdinal.put(ordinal, chunk.body);
            ordinals[i] = ordinal;
        }
        ordinalsByFile.put(file.getPath(), ordinals);
    }

    @Override
    public void fileCreated(@NotNull VirtualFileEvent event) {
        log.debug("JVectorFileListener fileCreated({}) for {}", event.getFile().getPath(), projectName);
        updateEmbeddings(event.getFile());
        dirty = true;
    }

    @Override
    public void fileDeleted(@NotNull VirtualFileEvent event) {
        log.debug("JVectorFileListener fileDeleted({}) for {}", event.getFile().getPath(), projectName);
        removeEmbeddings(event.getFile());
        dirty = true;
    }

    private void removeEmbeddings(@NotNull VirtualFile file) {
        for (var node: ordinalsByFile.get(file.getPath())) {
            builder.markNodeDeleted(node);
        }
    }

    @Override
    public void fileMoved(@NotNull VirtualFileMoveEvent event) {
        log.debug("JVectorFileListener fileDeleted({} -> {}) for {}",
                  event.getOldParent().getPath(),
                  event.getNewParent().getPath(),
                  projectName);
        // we don't have to update the graph index, just the ordinalsByFile map
        for (var entry : ordinalsByFile.entrySet()) {
            if (!entry.getKey().equals(event.getOldParent().getPath())) {
                continue;
            }

            var newPath = event.getNewParent().getPath();
            ordinalsByFile.put(newPath, entry.getValue());
            ordinalsByFile.remove(entry.getKey());
        }
    }

    // we do not have to implement fileCopied, since a fileCreated event is triggered for the new file


    @Override
    public void close() {
        log.debug("JVectorFileListener close() for {}", projectName);
        save();
        db.close();
        scheduler.shutdown();
    }
}
