package com.sourcegraph.cody;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManagerListener;
import com.sourcegraph.cody.agent.CodyAgent;
import com.sourcegraph.jvector.JVectorFileListenerReference;
import org.jetbrains.annotations.NotNull;

public class CodyAgentProjectListener implements ProjectManagerListener {
  @Override
  public void projectOpened(@NotNull Project project) {
    var service = project.getService(JVectorFileListenerReference.class);
      if (service == null) {
          return;
      }
      service.initialize(project);
  }

  @Override
  public void projectClosed(@NotNull Project project) {
    var service = project.getService(JVectorFileListenerReference.class);
    if (service == null) {
      return;
    }
    service.shutdown();
  }
}
