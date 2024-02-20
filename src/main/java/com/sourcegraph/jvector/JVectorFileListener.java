package com.sourcegraph.jvector;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.AsyncFileListener;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class JVectorFileListener implements AsyncFileListener, AutoCloseable {
    private static final Logger log = Logger.getInstance(JVectorFileListener.class);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private final String projectName;

    private final DB db;
    private final Map<Integer, String> chunksByOrdinal;
    private final Map<String, int[]> ordinalsByFile;

    private final GraphIndexBuilder<float[]> builder;
    private final Path graphIndexPath;
    private final ArrayList<float[]> vectors;
    private final ListRandomAccessVectorValues ravv;
    private final EmbeddingsProvider embeddingsProvider;
    private final @NotNull ProjectFileIndex projectIndex;
    private boolean dirty;

    public JVectorFileListener(Project project) {
        projectName = project.getName();
        projectIndex = ProjectRootManager.getInstance(project).getFileIndex();
        debug("%s: create", projectName);

        vectors = new ArrayList<>();
        ravv = new ListRandomAccessVectorValues(vectors, 1536);
        builder = new GraphIndexBuilder<>(ravv, VectorEncoding.FLOAT32, VectorSimilarityFunction.DOT_PRODUCT, 16, 100, 1.2f, 1.2f);
        embeddingsProvider = new OpenAIEmbeddingsProvider();

        var cachePath = Path.of(PathManager.getSystemPath(), "codelocal", projectName);
        try {
            Files.createDirectories(cachePath);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        var mapDBPath = cachePath.resolve("map.db");
        db = DBMaker.fileDB(mapDBPath.toFile()).fileMmapEnable().make();

        chunksByOrdinal = db.hashMap("chunksByOrdinal", Serializer.INTEGER, Serializer.STRING).createOrOpen();
        ordinalsByFile = db.hashMap("ordinalsByFile", Serializer.STRING, Serializer.INT_ARRAY).createOrOpen();
        graphIndexPath = cachePath.resolve("jvector.db");
        debug("mapDBPath=%s, graphIndexPath=%s", mapDBPath, graphIndexPath);
        if (Files.exists(graphIndexPath)) {
            try {
                builder.load(new SimpleMappedReader(graphIndexPath));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        scheduler.schedule(this::save, 1, java.util.concurrent.TimeUnit.MINUTES);
    }

    private static void debug(String format, Object... args) {
        var message = String.format(format, args);
        log.warn(message);
    }

    public void save() {
        if (!dirty) {
            return;
        }

        debug("%s: save()", projectName);
        builder.cleanup();
        var g = builder.getGraph();
        try {
            DataOutput out = new DataOutputStream(Files.newOutputStream(graphIndexPath));
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
            var text = new String(file.contentsToByteArray(), file.getCharset());
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
        return List.of("TODO");
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

    private void removeEmbeddings(@NotNull VirtualFile file) {
        var ordinals = ordinalsByFile.get(file.getPath());
        if (ordinals == null) {
            return;
        }

        for (var node: ordinals) {
            builder.markNodeDeleted(node);
        }
    }

    @Override
    public ChangeApplier prepareChange(@NotNull List<? extends @NotNull VFileEvent> list) {
        return new ChangeApplier() {
            @Override
            public void afterVfsChange() {
                for (var event: list) {
                    if (event.getFile() == null
                        || !event.getFile().getPath().endsWith(".java") // TODO support non-Java code
                        || !projectIndex.isInContent(event.getFile()))
                    {
                        continue;
                    }

                    if (event instanceof VFileContentChangeEvent) {
                        debug("%s: contentsChanged(%s)", projectName, event.getFile().getPath());
                        removeEmbeddings(event.getFile());
                        updateEmbeddings(event.getFile());
                        dirty = true;
                    } else if (event instanceof VFileMoveEvent) {
                        var me = (VFileMoveEvent) event;
                        debug("%s: fileDeleted(%s -> %s)",
                              projectName,
                              me.getOldParent().getPath(),
                              me.getNewParent().getPath());
                        // we don't have to update the graph index, just the ordinalsByFile map
                        for (var entry : ordinalsByFile.entrySet()) {
                            if (!entry.getKey().equals(me.getOldParent().getPath())) {
                                continue;
                            }

                            var newPath = me.getNewParent().getPath();
                            ordinalsByFile.put(newPath, entry.getValue());
                            ordinalsByFile.remove(entry.getKey());
                        }
                        dirty = true;
                    } else if (event instanceof VFileDeleteEvent) {
                        debug("%s: fileDeleted(%s)", projectName, event.getFile().getPath());
                        removeEmbeddings(event.getFile());
                        dirty = true;
                    } else if (event instanceof VFileCreateEvent) {
                        debug("%s: fileCreated(%s)", projectName, event.getFile().getPath());
                        updateEmbeddings(event.getFile());
                    }
                    // we do not have to implement fileCopied, since a fileCreated event is triggered for the new file
                }
            }
        };
    }

    @Override
    public void close() {
        debug("%s: close()", projectName);
        save();
        db.close();
        scheduler.shutdown();
    }
}
