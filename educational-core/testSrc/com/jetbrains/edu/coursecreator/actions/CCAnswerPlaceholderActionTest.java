package com.jetbrains.edu.coursecreator.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.jetbrains.edu.coursecreator.CCTestCase;
import com.jetbrains.edu.coursecreator.CCTestsUtil;
import com.jetbrains.edu.coursecreator.actions.placeholder.CCAddAnswerPlaceholder;
import com.jetbrains.edu.coursecreator.actions.placeholder.CCCreateAnswerPlaceholderDialog;
import com.jetbrains.edu.coursecreator.actions.placeholder.CCDeleteAnswerPlaceholder;
import com.jetbrains.edu.learning.EduUtils;
import com.jetbrains.edu.learning.courseFormat.AnswerPlaceholder;
import com.jetbrains.edu.learning.courseFormat.TaskFile;
import org.jetbrains.annotations.NotNull;

public class CCAnswerPlaceholderActionTest extends CCTestCase {
  static class CCTestAction extends CCAddAnswerPlaceholder {
    @Override
    protected CCCreateAnswerPlaceholderDialog createDialog(Project project, AnswerPlaceholder answerPlaceholder) {
      String placeholderText = answerPlaceholder.getPlaceholderText();
      return new CCCreateAnswerPlaceholderDialog(project, placeholderText == null ? "type here" : placeholderText, false) {
        @Override
        public boolean showAndGet() {
          return true;
        }

        @Override
        @NotNull
        public String getTaskText() {
          return "type here";
        }
      };
    }
  }

  public void testPlaceholderWithSelection() {
    doTest("onePlaceholder", new CCTestAction());
  }

  public void testPlaceholderWithoutSelection() {
    doTest("withoutSelection", new CCTestAction());
  }

  public void testPlaceholderIntersection() {
    configureByTaskFile("placeholderIntersection.txt");
    Presentation presentation = myFixture.testAction(new CCTestAction());
    assertTrue(presentation.isVisible() && !presentation.isEnabled());
  }

  public void testPlaceholderDeleted() {
    doTest("deletePlaceholder", new CCDeleteAnswerPlaceholder());
  }

  private void doTest(String name, AnAction action) {
    VirtualFile virtualFile = configureByTaskFile(name + CCTestsUtil.BEFORE_POSTFIX);
    myFixture.testAction(action);
    TaskFile taskFile = EduUtils.getTaskFile(getProject(), virtualFile);
    checkByFile(taskFile, name + CCTestsUtil.AFTER_POSTFIX, false);
    checkPainters(taskFile);
    UndoManager.getInstance(getProject()).undo(FileEditorManager.getInstance(getProject()).getSelectedEditor(virtualFile));
    checkByFile(taskFile, name + CCTestsUtil.BEFORE_POSTFIX, false);
  }

  @Override
  protected String getBasePath() {
    return super.getBasePath()  + "/actions/addPlaceholder";
  }
}
