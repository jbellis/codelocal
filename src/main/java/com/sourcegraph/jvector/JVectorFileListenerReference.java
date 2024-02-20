package com.sourcegraph.jvector;

import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFileManager;
import org.jetbrains.annotations.NotNull;

public class JVectorFileListenerReference {
    private volatile JVectorFileListener listener;

    public JVectorFileListenerReference() {
    }

    public void initialize(Project project) {
        // Schedule the heavy lifting for the background thread
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Initializing JVector", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                listener = new JVectorFileListener(project);
                // TODO add the listener to VFS
            }
        });
    }

    public void shutdown() {
        if (listener != null) {
            listener.close();
        }
    }
}
