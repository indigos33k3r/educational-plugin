package com.jetbrains.edu.javascript.learning.checkio;

import com.intellij.lang.javascript.JavascriptLanguage;
import com.intellij.openapi.application.Experiments;
import com.jetbrains.edu.javascript.learning.checkio.utils.JsCheckiONames;
import com.jetbrains.edu.learning.CoursesProvider;
import com.jetbrains.edu.learning.EduExperimentalFeatures;
import com.jetbrains.edu.learning.checkio.courseFormat.CheckiOCourse;
import com.jetbrains.edu.learning.courseFormat.Course;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

public class JsCheckiOCourseProvider implements CoursesProvider {
  @NotNull
  @Override
  public List<Course> loadCourses() {
    return Experiments.isFeatureEnabled(EduExperimentalFeatures.JAVASCRIPT_COURSES)
           ? Collections.singletonList(new CheckiOCourse(JsCheckiONames.JS_CHECKIO, JavascriptLanguage.INSTANCE.getID()))
           : Collections.emptyList();
  }
}
