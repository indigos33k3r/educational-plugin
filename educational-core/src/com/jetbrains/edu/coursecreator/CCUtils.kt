package com.jetbrains.edu.coursecreator

import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeAndWaitIfNeed
import com.intellij.openapi.application.runUndoTransparentWriteAction
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.*
import com.intellij.psi.PsiDirectory
import com.intellij.util.Function
import com.intellij.util.PathUtil
import com.jetbrains.edu.coursecreator.configuration.YamlFormatSynchronizer
import com.jetbrains.edu.coursecreator.stepik.StepikCourseChangeHandler
import com.jetbrains.edu.learning.*
import com.jetbrains.edu.learning.courseFormat.*
import com.jetbrains.edu.learning.courseFormat.ext.configurator
import com.jetbrains.edu.learning.courseFormat.ext.getVirtualFile
import com.jetbrains.edu.learning.courseFormat.tasks.EduTask
import com.jetbrains.edu.learning.courseFormat.tasks.Task
import org.apache.commons.codec.binary.Base64
import java.io.IOException
import java.util.*

object CCUtils {
  private val LOG = Logger.getInstance(CCUtils::class.java)

  const val GENERATED_FILES_FOLDER = ".coursecreator"
  const val COURSE_MODE = "Course Creator"

  /**
   * This method decreases index and updates directory names of
   * all tasks/lessons that have higher index than specified object
   *
   * @param dirs         directories that are used to get tasks/lessons
   * @param getStudyItem function that is used to get task/lesson from VirtualFile. This function can return null
   * @param threshold    index is used as threshold
   */
  @JvmStatic
  fun updateHigherElements(dirs: Array<VirtualFile>,
                           getStudyItem: Function<VirtualFile, out StudyItem>,
                           threshold: Int,
                           delta: Int) {
    val itemsToUpdate = dirs.filterTo(mutableListOf()) { dir ->
      val item = getStudyItem.`fun`(dir) ?: return@filterTo false
      item.index > threshold
    }.sortedWith(Comparator { o1, o2 ->
      val item1 = getStudyItem.`fun`(o1)
      val item2 = getStudyItem.`fun`(o2)
      //if we delete some dir we should start increasing numbers in dir names from the end
      -delta * EduUtils.INDEX_COMPARATOR.compare(item1, item2)
    })

    for (dir in itemsToUpdate) {
      val item = getStudyItem.`fun`(dir)
      val newIndex = item.index + delta
      item.index = newIndex
      StepikCourseChangeHandler.infoChanged(item)
    }
  }

  @JvmStatic
  fun getGeneratedFilesFolder(project: Project, module: Module): VirtualFile? {
    val baseDir = project.baseDir
    val folder = baseDir.findChild(GENERATED_FILES_FOLDER)
    if (folder != null) return folder
    return runWriteAction {
      try {
        val generatedRoot = baseDir.createChildDirectory(this, GENERATED_FILES_FOLDER)
        val contentRootForFile = ProjectRootManager.getInstance(module.project).fileIndex.getContentRootForFile(generatedRoot)
                                 ?: return@runWriteAction null
        ModuleRootModificationUtil.updateExcludedFolders(module, contentRootForFile, emptyList(), listOf(generatedRoot.url))
        generatedRoot
      } catch (e: IOException) {
        LOG.info("Failed to create folder for generated files", e)
        null
      }
    }
  }

  @JvmStatic
  fun generateFolder(project: Project, module: Module, name: String): VirtualFile? {
    val generatedRoot = getGeneratedFilesFolder(project, module) ?: return null

    var folder = generatedRoot.findChild(name)
    //need to delete old folder
    runWriteAction {
      try {
        folder?.delete(CCUtils::class.java)
        folder = generatedRoot.createChildDirectory(null, name)
      } catch (e: IOException) {
        LOG.info("Failed to generate folder $name", e)
      }
    }
    return folder
  }

  @JvmStatic
  fun isCourseCreator(project: Project): Boolean {
    val course = StudyTaskManager.getInstance(project).course ?: return false
    return COURSE_MODE == course.courseMode || COURSE_MODE == EduUtils.getCourseModeForNewlyCreatedProject(project)
  }

  @JvmStatic
  fun updateActionGroup(e: AnActionEvent) {
    val presentation = e.presentation
    val project = e.project
    presentation.isEnabledAndVisible = project != null && isCourseCreator(project)
  }

  @JvmStatic
  fun createAdditionalLesson(course: Course, project: Project,
                             name: String): Lesson? {
    ApplicationManager.getApplication().invokeAndWait { FileDocumentManager.getInstance().saveAllDocuments() }

    val baseDir = project.baseDir
    val configurator = course.configurator

    val lesson = Lesson()
    lesson.name = name
    lesson.course = course
    val task = EduTask()
    task.lesson = lesson
    task.name = name
    task.index = 1

    val sanitizedName = FileUtil.sanitizeFileName(course.name)
    val archiveName = String.format("%s.zip", if (sanitizedName.startsWith("_")) EduNames.COURSE else sanitizedName)

    VfsUtilCore.visitChildrenRecursively(baseDir, object : VirtualFileVisitor<Any>(VirtualFileVisitor.NO_FOLLOW_SYMLINKS) {
      override fun visitFile(file: VirtualFile): Boolean {
        @Suppress("NAME_SHADOWING")
        val name = file.name
        if (name == archiveName) return false
        if (file.isDirectory) {
          // All files inside task directory are already handled by `CCVirtualFileListener`
          // so here we don't need to process them again
          return EduUtils.getTask(file, course) == null
        }
        if (EduUtils.isTestsFile(project, file)) return true
        if (configurator != null && configurator.excludeFromArchive(project, file)) return false

        val taskFile = EduUtils.getTaskFile(project, file)
        if (taskFile == null) {
          addFileInAdditionalTask(task, baseDir, file)
        }
        return true
      }
    })
    if (taskIsEmpty(task)) return null
    lesson.addTask(task)
    lesson.index = course.items.size + 1
    return lesson
  }

  private fun taskIsEmpty(task: Task): Boolean = task.taskFiles.isEmpty() &&
                                                 task.testsText.isEmpty() &&
                                                 task.additionalFiles.isEmpty()

  private fun addFileInAdditionalTask(additionalTask: Task, baseDir: VirtualFile, file: VirtualFile) {
    val path = VfsUtilCore.getRelativePath(file, baseDir) ?: return
    try {
      additionalTask.addAdditionalFile(path, AdditionalFile(loadText(file), false))
    } catch (e: IOException) {
      LOG.error(e)
    }
  }

  @JvmStatic
  @Throws(IOException::class)
  fun loadText(file: VirtualFile): String {
    return if (EduUtils.isImage(file.name)) {
      Base64.encodeBase64URLSafeString(VfsUtilCore.loadBytes(file))
    } else {
      VfsUtilCore.loadText(file)
    }
  }

  @JvmStatic
  fun initializeCCPlaceholders(project: Project, course: Course) {
    for (item in course.items) {
      when (item) {
        is Section -> initializeSectionPlaceholders(project, item)
        is Lesson -> initializeLessonPlaceholders(project, item)
        else -> LOG.warn("Unknown study item type: `${item.javaClass.canonicalName}`")
      }
    }
    VirtualFileManager.getInstance().refreshWithoutFileWatcher(true)
  }

  private fun initializeSectionPlaceholders(project: Project, section: Section) {
    EduUtils.getCourseDir(project).findChild(section.name) ?: return
    for (item in section.lessons) {
      initializeLessonPlaceholders(project, item)
    }
  }

  private fun initializeLessonPlaceholders(project: Project, lesson: Lesson) {
    for (task in lesson.getTaskList()) {
      initializeTaskPlaceholders(task, project)
    }
  }

  fun initializeTaskPlaceholders(task: Task, project: Project) {
    val taskDir = task.getTaskDir(project) ?: return
    for (entry in task.taskFiles.entries) {
      invokeAndWaitIfNeed { runWriteAction { initializeTaskFilePlaceholders(project, taskDir, entry.value) } }
    }
  }

  private fun initializeTaskFilePlaceholders(project: Project, userFileDir: VirtualFile, taskFile: TaskFile) {
    val file = EduUtils.findTaskFileInDir(taskFile, userFileDir)
    if (file == null) {
      LOG.warn("Failed to find file $file")
      return
    }
    val document = FileDocumentManager.getInstance().getDocument(file) ?: return
    val listener = EduDocumentTransformListener(project, taskFile)
    document.addDocumentListener(listener)
    taskFile.sortAnswerPlaceholders()
    taskFile.isTrackLengths = false

    try {
      for (placeholder in taskFile.answerPlaceholders) {
        replaceAnswerPlaceholder(document, placeholder)
        placeholder.useLength = false
      }

      CommandProcessor.getInstance().executeCommand(project, {
        runWriteAction { FileDocumentManager.getInstance().saveDocumentAsIs(document) }
      }, "Create answer document", "Create answer document")
    } finally {
      document.removeDocumentListener(listener)
      taskFile.isTrackLengths = true
    }
  }

  fun replaceAnswerPlaceholder(document: Document, placeholder: AnswerPlaceholder) {
    val offset = placeholder.offset
    val text = document.getText(TextRange.create(offset, offset + placeholder.length))
    placeholder.placeholderText = text
    placeholder.init()
    val replacementText = placeholder.possibleAnswer

    runUndoTransparentWriteAction {
      document.replaceString(offset, offset + placeholder.length, replacementText)
      FileDocumentManager.getInstance().saveDocumentAsIs(document)
    }
  }

  class PathInputValidator @JvmOverloads constructor(
    private val myParentDir: VirtualFile?,
    private val myName: String? = null
  ) : InputValidatorEx {

    private var myErrorText: String? = null

    override fun checkInput(inputString: String): Boolean {
      if (myParentDir == null) {
        myErrorText = "Invalid parent directory"
        return false
      }
      myErrorText = null
      if (!PathUtil.isValidFileName(inputString)) {
        myErrorText = "Invalid name"
        return false
      }
      if (myParentDir.findChild(inputString) != null && inputString != myName) {
        myErrorText = String.format("%s already contains directory named %s", myParentDir.name, inputString)
      }
      return myErrorText == null
    }

    override fun canClose(inputString: String): Boolean = checkInput(inputString)

    override fun getErrorText(inputString: String): String? = myErrorText
  }

  @JvmStatic
  fun wrapIntoSection(project: Project, course: Course, lessonsToWrap: List<Lesson>, sectionName: String): Section? {
    Collections.sort(lessonsToWrap, EduUtils.INDEX_COMPARATOR)
    val minIndex = lessonsToWrap[0].index
    val maxIndex = lessonsToWrap[lessonsToWrap.size - 1].index

    val sectionDir = createSectionDir(project, sectionName) ?: return null

    val section = createSection(lessonsToWrap, sectionName, minIndex)
    section.course = course

    for (i in lessonsToWrap.indices) {
      val lesson = lessonsToWrap[i]
      val lessonDir = lesson.getLessonDir(project)
      if (lessonDir != null) {
        moveLesson(lessonDir, sectionDir)
        lesson.index = i + 1
        lesson.section = section
      }
      course.removeLesson(lesson)
    }

    val delta = -lessonsToWrap.size + 1

    updateHigherElements(EduUtils.getCourseDir(project).children, Function { file ->  course.getItem(file.name) }, maxIndex, delta)
    course.addItem(section, section.index - 1)
    synchronizeChanges(project, course, section)
    return section
  }

  private fun synchronizeChanges(project: Project, course: Course, section: Section) {
    YamlFormatSynchronizer.saveItem(section)
    YamlFormatSynchronizer.saveItem(course)
    ProjectView.getInstance(project).refresh()
    StepikCourseChangeHandler.contentChanged(course)
    for (lesson in section.lessons) {
      if (lesson.id != 0) {
        StepikCourseChangeHandler.infoChanged(lesson)
      }
    }
  }

  private fun createSection(lessonsToWrap: List<Lesson>, sectionName: String, index: Int): Section {
    val section = Section()
    section.index = index
    section.name = sectionName
    section.addLessons(lessonsToWrap)
    return section
  }

  private fun moveLesson(lessonDir: VirtualFile, sectionDir: VirtualFile) {
    ApplicationManager.getApplication().runWriteAction(object : Runnable {
      override fun run() {
        try {
          lessonDir.move(this, sectionDir)
        }
        catch (e1: IOException) {
          LOG.error("Failed to move lesson " + lessonDir.name + " to the new section " + sectionDir.name)
        }

      }
    })
  }

  @JvmStatic
  fun createSectionDir(project: Project, sectionName: String): VirtualFile? {
    return ApplicationManager.getApplication().runWriteAction(Computable<VirtualFile> {
      try {
        return@Computable VfsUtil.createDirectoryIfMissing(EduUtils.getCourseDir(project), sectionName)
      }
      catch (e1: IOException) {
        LOG.error("Failed to create directory for section $sectionName")
      }

      null
    })
  }

  @JvmStatic
  fun lessonFromDir(course: Course, lessonDir: PsiDirectory, project: Project): Lesson? {
    val parentDir = lessonDir.parent
    if (parentDir != null && parentDir.name == EduUtils.getCourseDir(project).name) {
      return course.getLesson(lessonDir.name)
    }
    else {
      val sectionDir = lessonDir.parent ?: return null
      val section = course.getSection(sectionDir.name) ?: return null
      return section.getLesson(lessonDir.name)
    }
  }

  @JvmStatic
  fun loadTestTextsToTask(task: Task, taskDir: VirtualFile) {
    for ((path, _) in task.testsText) {
      val text = loadTextByPath(taskDir, path) ?: continue
      task.addTestsTexts(path, text)
    }
  }

  @JvmStatic
  fun loadAdditionalFileTextsToTask(task: Task, taskDir: VirtualFile) {
    for ((path, additionalFile) in task.additionalFiles) {
      val text = loadTextByPath(taskDir, path) ?: continue
      additionalFile.setText(text)
    }
  }

  private fun loadTextByPath(dir: VirtualFile, relativePath: String): String? {
    val file = dir.findFileByRelativePath(relativePath)
    if (file != null) {
      try {
        return loadText(file)
      } catch (e: IOException) {
        LOG.warn("Failed to load text for `$file`")
      }
    } else {
      LOG.warn("Can't find file by `$relativePath` path")
    }
    return null
  }

  @JvmOverloads
  @JvmStatic
  fun hidePlaceholders(project: Project, taskFile: TaskFile, file: VirtualFile? = null) {
    @Suppress("NAME_SHADOWING")
    val file = file ?: (taskFile.getVirtualFile(project) ?: return)
    for (editor in getEditors(project, file)) {
      for (placeholder in taskFile.answerPlaceholders) {
        NewPlaceholderPainter.removePainter(editor, placeholder)
      }
    }
  }

  @JvmOverloads
  @JvmStatic
  fun showPlaceholders(project: Project, taskFile: TaskFile, file: VirtualFile? = null) {
    @Suppress("NAME_SHADOWING")
    val file = file ?: (taskFile.getVirtualFile(project) ?: return)
    for (editor in getEditors(project, file)) {
      EduUtils.drawAllAnswerPlaceholders(editor, taskFile)
    }
  }

  @JvmStatic
  private fun getEditors(project: Project, file: VirtualFile): List<Editor> {
    return FileEditorManager.getInstance(project).getEditors(file)
      .filterIsInstance<TextEditor>()
      .map { it.editor }
  }
}
