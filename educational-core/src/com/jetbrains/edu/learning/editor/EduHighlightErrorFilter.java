package com.jetbrains.edu.learning.editor;

import com.intellij.codeInsight.highlighting.HighlightErrorFilter;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.jetbrains.edu.learning.courseFormat.TaskFile;
import com.jetbrains.edu.learning.EduUtils;
import org.jetbrains.annotations.NotNull;

public class EduHighlightErrorFilter extends HighlightErrorFilter{
  @Override
  public boolean shouldHighlightErrorElement(@NotNull PsiErrorElement element) {
    PsiFile file = element.getContainingFile();
    if (file == null) {
      return true;
    }
    VirtualFile virtualFile = file.getVirtualFile();
    if (virtualFile == null) {
      return true;
    }
    TaskFile taskFile = EduUtils.getTaskFile(element.getProject(), virtualFile);
    return taskFile == null || taskFile.isHighlightErrors();
  }
}
