package com.sourcegraph.jvector;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.AsyncFileListener;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileVisitor;
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import io.github.jbellis.jvector.disk.SimpleMappedReader;
import io.github.jbellis.jvector.graph.GraphIndexBuilder;
import io.github.jbellis.jvector.graph.ListRandomAccessVectorValues;
import io.github.jbellis.jvector.vector.VectorEncoding;
import io.github.jbellis.jvector.vector.VectorSimilarityFunction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Serializer;

import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An asynchronous file listener that reacts to file changes within a project, maintaining and updating an
 * index of file embeddings and a map of embeddings to source file.
 */
public class JVectorFileListener implements AsyncFileListener, AutoCloseable {
    private static final Logger log = Logger.getInstance(JVectorFileListener.class);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private final Project project;

    private final DB db;
    private final Map<Integer, String> chunksByOrdinal;
    private final Map<String, int[]> ordinalsByFile;
    private final Map<String, byte[]> fileContentHashes;

    private final GraphIndexBuilder<float[]> builder;
    private final Path graphIndexPath;
    private final ArrayList<float[]> vectors;
    private final ListRandomAccessVectorValues ravv;
    private final EmbeddingsProvider embeddingsProvider;
    private boolean dirty;

    public JVectorFileListener(Project project) {
        this.project = project;
        debug("%s: create", project.getName());

        vectors = new ArrayList<>();
        ravv = new ListRandomAccessVectorValues(vectors, 1536);
        builder = new GraphIndexBuilder<>(ravv, VectorEncoding.FLOAT32, VectorSimilarityFunction.DOT_PRODUCT, 16, 100, 1.2f, 1.2f);
        embeddingsProvider = new OpenAIEmbeddingsProvider();

        // create a cache directory for the project
        var cachePath = Path.of(PathManager.getSystemPath(), "codelocal", project.getName());
        try {
            Files.createDirectories(cachePath);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // mapdb and graph index both live in the cache directory
        var mapDBPath = cachePath.resolve("map.db");
        graphIndexPath = cachePath.resolve("jvector.db");
        debug("mapDBPath=%s, graphIndexPath=%s", mapDBPath, graphIndexPath);

        // create mapdb maps
        db = DBMaker.fileDB(mapDBPath.toFile()).fileMmapEnable().make();
        chunksByOrdinal = db.hashMap("chunksByOrdinal", Serializer.INTEGER, Serializer.STRING).createOrOpen();
        ordinalsByFile = db.hashMap("ordinalsByFile", Serializer.STRING, Serializer.INT_ARRAY).createOrOpen();
        fileContentHashes = db.hashMap("fileContentHashes", Serializer.STRING, Serializer.BYTE_ARRAY).createOrOpen();

        // load graph index
        if (Files.exists(graphIndexPath)) {
            try {
                builder.load(new SimpleMappedReader(graphIndexPath));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        scheduler.schedule(this::save, 1, java.util.concurrent.TimeUnit.MINUTES);
    }

    /**
     * Scans existing files in the project.
     */
    public void scanExistingFiles() {
        var visited = new AtomicInteger();
        var updated = new AtomicInteger();
        for (var root : ProjectRootManager.getInstance(project).getContentRoots()) {
            VfsUtil.visitChildrenRecursively(root, new VirtualFileVisitor<Void>() {
                @Override
                public boolean visitFile(@NotNull VirtualFile file) {
                    visited.incrementAndGet();
                    if (file.isDirectory()) {
                        return true;
                    }

                    if (shouldIndex(file)) {
                        if (maybeUpdateFile(file)) {
                            updated.incrementAndGet();
                        }
                    }
                    return false;
                }
            });
        }
        save();
        debug("%s: scanExistingFiles: visited=%d, updated=%d", projectName(), visited.get(), updated.get());
    }

    /**
     * Handles a single file during the scan. This method should include the logic
     * for processing each file (e.g., indexing content, checking file type).
     *
     * @param file the file to handle
     */
    private boolean maybeUpdateFile(VirtualFile file) {
        var hash = getHash(file);
        var oldHash = fileContentHashes.get(file.getPath());
        if (oldHash != null && MessageDigest.isEqual(hash, oldHash)) {
            return false;
        }

        removeEmbeddings(file);
        createEmbeddings(file);
        fileContentHashes.put(file.getPath(), hash);
        return true;
    }

    private static byte[] getHash(VirtualFile file) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        try (var fis = new FileInputStream(file.getPath())) {
            byte[] byteArray = new byte[1024];
            // Read the file data and update in digest
            int bytesCount;
            while ((bytesCount = fis.read(byteArray)) != -1) {
                digest.update(byteArray, 0, bytesCount);
            };
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // Get the hash's bytes
        return digest.digest();
    }

    /**
     * Log a debug message with String.format parameters.
     */
    private static void debug(String format, Object... args) {
        var message = String.format(format, args);
        log.warn(message);
    }

    public void save() {
        if (!dirty) {
            return;
        }

        debug("%s: save()", projectName());
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
        for (var body: chunkify(file)) {
            var embedding = embeddingsProvider.getEmbedding(body);
            chunks.add(new Chunk(body, embedding));
        }
        return chunks;
    }

    /**
     * Extracts all method bodies from a given Java file.
     *
     * @param file The virtual file to process, expected to be a Java file.
     * @return A list of strings, each representing the body of a method.
     */
    private Set<String> chunkify(@NotNull VirtualFile file) {
        var chunks = ConcurrentHashMap.<String>newKeySet();
        ApplicationManager.getApplication().runReadAction(() -> {
            PsiFile pf = PsiManager.getInstance(project).findFile(file);
            assert pf instanceof PsiJavaFile : pf;
            PsiJavaFile javaFile = (PsiJavaFile) pf;
            for (var psiClass : javaFile.getClasses()) {
                for (var method : psiClass.getMethods()) {
                    var methodBody = method.getBody();
                    if (methodBody == null) {
                        // interface or abstract method
                        continue;
                    }
                    String methodText = methodBody.getText();
                    chunks.addAll(chunkMethod(methodText));
                }
            }
        });

        // write the chunks to a file for debugging
        Path path = Paths.get("/tmp").resolve(file.getName() + ".chunks");
        try {
            Files.write(path, chunks);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return chunks;
    }

    private List<String> chunkMethod(String methodText) {
        // TODO refine the estimate of token count by actually looking at some examples
        // the generic guideline of 1 token per 4 English characters will probably undercount for code
        // so we use 1:1 which may well be too conservative
        var chunkLength = 8192;
        var chunks = new ArrayList<String>();
        for (int i = 0; i < methodText.length(); i += chunkLength) {
            chunks.add(methodText.substring(i, Math.min(i + chunkLength, methodText.length())));
        }
        return chunks;
    }

    private void createEmbeddings(@NotNull VirtualFile file) {
        var chunks = computeEmbeddings(file);
        var ordinals = new int[chunks.size()];
        // add each chunk to the index
        for (int i = 0; i < chunks.size(); i++) {
            var chunk = chunks.get(i);
            int ordinal = vectors.size();
            vectors.add(chunk.embedding);
            builder.addGraphNode(ordinal, ravv);
            chunksByOrdinal.put(ordinal, chunk.body);
            ordinals[i] = ordinal;
        }
        // update the ordinalsByFile map
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

    /**
     * Turn a list of VFileEvents into a ChangeApplier that updates the graph index and ordinalsByFile map.
     */
    @Override
    public ChangeApplier prepareChange(@NotNull List<? extends @NotNull VFileEvent> list) {
        var projectIndex = ProjectRootManager.getInstance(project).getFileIndex();
        return new ChangeApplier() {
            @Override
            public void afterVfsChange() {
                for (var event: list) {
                    if (event.getFile() == null
                        || !shouldIndex(event.getFile()) // TODO support non-Java code
                        || !projectIndex.isInContent(event.getFile()))
                    {
                        continue;
                    }

                    if (event instanceof VFileContentChangeEvent) {
                        debug("%s: contentsChanged(%s)", projectName(), event.getFile().getPath());
                        if (maybeUpdateFile(event.getFile())) {
                            dirty = true;
                        }
                    } else if (event instanceof VFileMoveEvent) {
                        var me = (VFileMoveEvent) event;
                        debug("%s: fileDeleted(%s -> %s)",
                              projectName(),
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
                        debug("%s: fileDeleted(%s)", projectName(), event.getFile().getPath());
                        removeEmbeddings(event.getFile());
                        dirty = true;
                    } else if (event instanceof VFileCreateEvent) {
                        debug("%s: fileCreated(%s)", projectName(), event.getFile().getPath());
                        createEmbeddings(event.getFile());
                        dirty = true;
                    }
                    db.commit();
                    // we do not have to implement fileCopied, since a fileCreated event is triggered for the new file
                }
            }
        };
    }

    private static boolean shouldIndex(@Nullable VirtualFile file) {
        return file.getPath().endsWith(".java");
    }

    @NotNull
    private String projectName() {
        return project.getName();
    }

    @Override
    public void close() {
        debug("%s: close()", projectName());
        save();
        db.close();
        scheduler.shutdown();
    }
}
