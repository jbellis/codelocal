package com.sourcegraph.jvector;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFileManager;
import org.jetbrains.annotations.NotNull;

public class JVectorFileListenerReference implements Disposable {
    private volatile JVectorFileListener listener;

    public JVectorFileListenerReference() {
    }

    public void initialize(Project project) {
        // Schedule the heavy lifting for the background thread
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Initializing JVector", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true); // TODO refactor so we can give actual progress
                listener = new JVectorFileListener(project);
                // TODO not sure if this is an appropriate Disposable
                VirtualFileManager.getInstance().addAsyncFileListener(listener, JVectorFileListenerReference.this);
                listener.scanExistingFiles();
            }
        });
    }

    @Override
    public void dispose() {
        if (listener != null) {
            listener.close();
        }
    }
}
