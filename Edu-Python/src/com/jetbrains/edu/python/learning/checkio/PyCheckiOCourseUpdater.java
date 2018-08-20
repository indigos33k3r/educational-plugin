package com.jetbrains.edu.python.learning.checkio;

import com.intellij.openapi.project.Project;
import com.jetbrains.edu.learning.checkio.CheckiOCourseUpdater;
import com.jetbrains.edu.learning.checkio.courseFormat.CheckiOCourse;
import com.jetbrains.edu.python.learning.checkio.connectors.PyCheckiOApiConnector;
import com.jetbrains.edu.python.learning.checkio.newProject.PyCheckiOCourseContentGenerator;
import org.jetbrains.annotations.NotNull;

public class PyCheckiOCourseUpdater extends CheckiOCourseUpdater {
  public PyCheckiOCourseUpdater(@NotNull CheckiOCourse course,
                                @NotNull Project project) {
    super(
      course,
      project,
      PyCheckiOCourseContentGenerator.getInstance(),
      PyCheckiOApiConnector.getInstance()
    );
  }
}