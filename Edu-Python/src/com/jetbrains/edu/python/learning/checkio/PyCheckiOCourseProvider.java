package com.jetbrains.edu.python.learning.checkio;

import com.jetbrains.edu.learning.CoursesProvider;
import com.jetbrains.edu.learning.checkio.courseFormat.CheckiOCourse;
import com.jetbrains.edu.learning.courseFormat.Course;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

public class PyCheckiOCourseProvider implements CoursesProvider {
  @NotNull
  @Override
  public List<Course> loadCourses() {
    return Collections.singletonList(new CheckiOCourse());
  }
}