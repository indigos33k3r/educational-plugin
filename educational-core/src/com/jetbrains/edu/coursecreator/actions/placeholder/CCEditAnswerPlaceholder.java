package com.jetbrains.edu.coursecreator.actions.placeholder;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiFile;
import com.jetbrains.edu.coursecreator.configuration.YamlFormatSynchronizer;
import com.jetbrains.edu.coursecreator.stepik.StepikCourseChangeHandler;
import com.jetbrains.edu.learning.courseFormat.AnswerPlaceholder;
import org.jetbrains.annotations.NotNull;

public class CCEditAnswerPlaceholder extends CCAnswerPlaceholderAction {

  public CCEditAnswerPlaceholder() {
    super("Edit", "Edit answer placeholder");
  }

  @Override
  protected void performAnswerPlaceholderAction(@NotNull CCState state) {
    final Project project = state.getProject();
    PsiFile file = state.getFile();
    final PsiDirectory taskDir = file.getContainingDirectory();
    final PsiDirectory lessonDir = taskDir.getParent();
    if (lessonDir == null) return;
    AnswerPlaceholder answerPlaceholder = state.getAnswerPlaceholder();
    if (answerPlaceholder == null) {
      return;
    }
    performEditPlaceholder(project, answerPlaceholder);
  }

  public static void performEditPlaceholder(@NotNull Project project, @NotNull AnswerPlaceholder answerPlaceholder) {
    CCCreateAnswerPlaceholderDialog dlg = new CCCreateAnswerPlaceholderDialog(project, answerPlaceholder.getPlaceholderText(), true);

    if (dlg.showAndGet()) {
      final String answerPlaceholderText = dlg.getTaskText();
      if (isChanged(answerPlaceholder, dlg)) {
        StepikCourseChangeHandler.changed(answerPlaceholder);
      }
      answerPlaceholder.setPlaceholderText(answerPlaceholderText);
      answerPlaceholder.setLength(StringUtil
                                    .notNullize(answerPlaceholderText).length());
      YamlFormatSynchronizer.saveItem(answerPlaceholder.getTaskFile().getTask());
    }
  }

  private static boolean isChanged(@NotNull AnswerPlaceholder answerPlaceholder, @NotNull CCCreateAnswerPlaceholderDialog dialog) {
    return !dialog.getTaskText().equals(answerPlaceholder.getPlaceholderText());
  }

  @Override
  public void update(AnActionEvent e) {
    Presentation presentation = e.getPresentation();
    presentation.setEnabledAndVisible(false);
    CCState state = getState(e);
    if (state == null || state.getAnswerPlaceholder() == null) {
      return;
    }
    presentation.setEnabledAndVisible(true);
  }
}