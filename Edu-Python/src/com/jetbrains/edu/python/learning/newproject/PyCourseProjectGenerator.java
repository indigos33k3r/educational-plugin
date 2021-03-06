package com.jetbrains.edu.python.learning.newproject;

import com.intellij.execution.ExecutionException;
import com.intellij.ide.fileTemplates.FileTemplate;
import com.intellij.ide.fileTemplates.FileTemplateManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.jetbrains.edu.learning.EduNames;
import com.jetbrains.edu.learning.StudyTaskManager;
import com.jetbrains.edu.learning.courseFormat.Course;
import com.jetbrains.edu.learning.courseGeneration.GeneratorUtils;
import com.jetbrains.edu.learning.newproject.CourseProjectGenerator;
import com.jetbrains.edu.python.learning.PyConfigurator;
import com.jetbrains.edu.python.learning.PyCourseBuilder;
import com.jetbrains.python.newProject.PyNewProjectSettings;
import com.jetbrains.python.packaging.PyPackageManager;
import com.jetbrains.python.psi.LanguageLevel;
import com.jetbrains.python.sdk.PyDetectedSdk;
import com.jetbrains.python.sdk.PySdkExtKt;
import com.jetbrains.python.sdk.PythonSdkType;
import com.jetbrains.python.sdk.PythonSdkUpdater;
import com.jetbrains.python.sdk.flavors.PythonSdkFlavor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

public class PyCourseProjectGenerator extends CourseProjectGenerator<PyNewProjectSettings> {
  private static final Logger LOG = Logger.getInstance(PyCourseProjectGenerator.class);

  public PyCourseProjectGenerator(@NotNull PyCourseBuilder builder, @NotNull Course course) {
    super(builder, course);
  }

  @Override
  protected void createAdditionalFiles(@NotNull Project project, @NotNull VirtualFile baseDir) throws IOException {
    final String testHelper = EduNames.TEST_HELPER;
    if (baseDir.findChild(testHelper) != null) return;
    final FileTemplate template = FileTemplateManager.getInstance(project).getInternalTemplate("test_helper");
    GeneratorUtils.createChildFile(baseDir, testHelper, template.getText());
  }

  @Override
  public void afterProjectGenerated(@NotNull Project project, @NotNull PyNewProjectSettings settings) {
    super.afterProjectGenerated(project, settings);
    Sdk sdk = settings.getSdk();

    if (sdk != null && sdk.getSdkType() == PyFakeSdkType.INSTANCE) {
      createAndAddVirtualEnv(project, settings);
      sdk = settings.getSdk();
    }
    sdk = updateSdkIfNeeded(project, sdk);
    SdkConfigurationUtil.setDirectoryProjectSdk(project, sdk);
  }

  public void createAndAddVirtualEnv(@NotNull Project project, @NotNull PyNewProjectSettings settings) {
    Course course = StudyTaskManager.getInstance(project).getCourse();
    if (course == null) {
      return;
    }
    final String baseSdkPath = getBaseSdk(course);
    if (baseSdkPath != null) {
      final PyDetectedSdk baseSdk = new PyDetectedSdk(baseSdkPath);
      final String virtualEnvPath = project.getBasePath() + "/.idea/VirtualEnvironment";
      final Sdk sdk = PySdkExtKt.createSdkByGenerateTask(new Task.WithResult<String, ExecutionException>(project,
              "Creating Virtual Environment",
              false) {
        @Override
        protected String compute(@NotNull ProgressIndicator indicator) throws ExecutionException {
          indicator.setIndeterminate(true);
          final PyPackageManager packageManager = PyPackageManager.getInstance(baseSdk);
          return packageManager.createVirtualEnv(virtualEnvPath, false);
        }
      }, getAllSdks(), baseSdk, project.getBasePath(), null);
      if (sdk == null) {
        LOG.warn("Failed to create virtual env in " + virtualEnvPath);
        return;
      }
      settings.setSdk(sdk);
      SdkConfigurationUtil.addSdk(sdk);
      PySdkExtKt.associateWithModule(sdk, null, project.getBasePath());
    }
  }

  @Nullable
  static String getBaseSdk(@NotNull final Course course) {
    LanguageLevel baseLevel;
    final String version = course.getLanguageVersion();
    if (PyConfigurator.PYTHON_2.equals(version)) {
      baseLevel = LanguageLevel.PYTHON27;
    }
    else if (PyConfigurator.PYTHON_3.equals(version)) {
      baseLevel = LanguageLevel.PYTHON37;
    }
    else if (version != null) {
      baseLevel = LanguageLevel.fromPythonVersion(version);
    }
    else {
      baseLevel = LanguageLevel.PYTHON37;
    }
    final PythonSdkFlavor flavor = PythonSdkFlavor.getApplicableFlavors(false).get(0);
    String baseSdk = null;
    final Collection<String> baseSdks = flavor.suggestHomePaths();
    for (String sdk : baseSdks) {
      final String versionString = flavor.getVersionString(sdk);
      final String prefix = flavor.getName() + " ";
      if (versionString != null && versionString.startsWith(prefix)) {
        final LanguageLevel level = LanguageLevel.fromPythonVersion(versionString.substring(prefix.length()));
        if (level.isAtLeast(baseLevel)) {
          baseSdk = sdk;
          break;
        }
      }
    }
    if (baseSdk != null) return baseSdk;
    return baseSdks.isEmpty() ? null : baseSdks.iterator().next();
  }

  @Nullable
  protected Sdk updateSdkIfNeeded(@NotNull Project project, @Nullable Sdk sdk) {
    if (!(sdk instanceof PyDetectedSdk)) {
      return sdk;
    }
    String name = sdk.getName();
    VirtualFile sdkHome = WriteAction.compute(() -> LocalFileSystem.getInstance().refreshAndFindFileByPath(name));
    Sdk newSdk = SdkConfigurationUtil.createAndAddSDK(sdkHome.getPath(), PythonSdkType.getInstance());
    if (newSdk != null) {
      PythonSdkUpdater.updateOrShowError(newSdk, null, project, null);
      SdkConfigurationUtil.addSdk(newSdk);
    }
    return newSdk;
  }

  @NotNull
  protected List<Sdk> getAllSdks() {
    return ProjectJdkTable.getInstance().getSdksOfType(PythonSdkType.getInstance());
  }
}
