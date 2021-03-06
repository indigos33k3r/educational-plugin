package com.jetbrains.edu.learning.coursera

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogBuilder
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.layout.*
import com.jetbrains.edu.learning.EduUtils
import com.jetbrains.edu.learning.checker.CheckResult
import com.jetbrains.edu.learning.checker.remote.RemoteTaskChecker
import com.jetbrains.edu.learning.courseFormat.CheckStatus
import com.jetbrains.edu.learning.courseFormat.ext.getVirtualFile
import com.jetbrains.edu.learning.courseFormat.tasks.Task
import org.apache.commons.codec.binary.Base64
import org.apache.http.HttpStatus
import org.apache.http.StatusLine
import org.apache.http.client.methods.CloseableHttpResponse
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.ContentType
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.HttpClientBuilder

class CourseraTaskChecker : RemoteTaskChecker {
  private val checkWithoutCredentials = CheckResult(CheckStatus.Unchecked, "Can't check on remote without credentials")
  override fun canCheck(project: Project, task: Task) =
    EduUtils.isStudentProject(project) && CourseraNames.COURSE_TYPE == task.course.courseType


  override fun check(project: Project, task: Task, indicator: ProgressIndicator): CheckResult {
    val courseraSettings = CourseraSettings.getInstance()
    var askedForCredentials = false
    if (!courseraSettings.haveFullCredentials()) {
      askToEnterCredentials(task)
      askedForCredentials = true

      if (!courseraSettings.haveFullCredentials()) {
        return checkWithoutCredentials
      }
    }

    val response = postSubmission(createSubmissionJson(project, task, courseraSettings))

    var statusLine = response.statusLine
    if (statusLine.statusCode != HttpStatus.SC_CREATED && !askedForCredentials) {
      askToEnterCredentials(task,statusLine.toCheckResult(task).message)
      statusLine = postSubmission(createSubmissionJson(project, task, courseraSettings)).statusLine
    }
    return statusLine.toCheckResult(task)
  }

  private fun StatusLine.toCheckResult(task: Task): CheckResult {
    return when (statusCode) {
      HttpStatus.SC_CREATED -> {
        val link = getLinkToSubmission(task)
        CheckResult(CheckStatus.Unchecked, SUCCESS.format(link), needEscape = false)
      }
      HttpStatus.SC_UNAUTHORIZED -> CheckResult(CheckStatus.Unchecked, "Invalid token or email")
      HttpStatus.SC_BAD_REQUEST -> CheckResult(CheckStatus.Unchecked, "Token is for a different assignment")
      else -> CheckResult(CheckStatus.Unchecked, "Failed to create new submission: $statusCode error received")
    }
  }

  private fun getLinkToSubmission(task: Task): String {
    return task.feedbackLink.link?.replace("/discussions", "/submission") ?: "https://www.coursera.org/"
  }

  private fun getLinkToToken(task: Task): String {
    return task.feedbackLink.link?.removeSuffix("/discussions") ?: "https://www.coursera.org/"
  }

  private fun createSubmissionJson(project: Project, task: Task, courseraSettings: CourseraSettings): String {
    val taskDir = task.getDir(project) ?: error("No directory for task ${task.name}")

    val assignmentKey = taskDir.getValueFromChildFile("assignmentKey")
    val partId = taskDir.getValueFromChildFile("partId")

    val output = task.taskFiles.mapValues {
      val file = it.value.getVirtualFile(project) ?: error("VirtualFile for ${it.key} not found")
      Base64.encodeBase64String(VfsUtilCore.loadBytes(file))
    }
    val submission = Submission(assignmentKey, courseraSettings.email, courseraSettings.token,
                                mapOf(Pair(partId, Part(ObjectMapper().writeValueAsString(output)))))
    return ObjectMapper().writeValueAsString(submission)
  }

  private fun postSubmission(json: String): CloseableHttpResponse {
    val client = HttpClientBuilder.create().build()
    val post = HttpPost(ON_DEMAND_SUBMIT)
    post.entity = StringEntity(json, ContentType.APPLICATION_JSON)
    return client.execute(post)
  }

  private fun askToEnterCredentials(task: Task, message: String? = null) {
    val courseraSettings = CourseraSettings.getInstance()

    val emailField = JBTextField(courseraSettings.email)
    val tokenField = JBTextField(courseraSettings.token)
    val credentialsPanel = panel {
      if (message != null) {
        row { JBLabel(message)() }
      }
      row("Email:") { emailField(growPolicy = GrowPolicy.MEDIUM_TEXT) }
      row("Token:") { tokenField(growPolicy = GrowPolicy.MEDIUM_TEXT) }
      noteRow("Token can be obtained <a href=\"${getLinkToToken(task)}\">here</a>")
    }
    var refusedToProvideCredentials = false

    ApplicationManager.getApplication().invokeAndWait {
      refusedToProvideCredentials = !DialogBuilder().centerPanel(credentialsPanel).title(NEED_CREDENTIALS).showAndGet()
    }

    if (!refusedToProvideCredentials) {
      CourseraSettings.getInstance().email = emailField.text
      CourseraSettings.getInstance().token = tokenField.text
    }
  }

  private fun VirtualFile.getValueFromChildFile(fileName: String): String {
    val file = this.findChild(fileName) ?: error("$fileName not found in ${this.path}")
    return VfsUtil.loadText(file)
  }

  private fun CourseraSettings.haveFullCredentials() = email.isNotEmpty() && token.isNotEmpty()

  companion object {
    private const val ON_DEMAND_SUBMIT = "https://www.coursera.org/api/onDemandProgrammingScriptSubmissions.v1"
    private const val NEED_CREDENTIALS = "${CourseraNames.COURSERA} Credentials"
    private const val SUCCESS = "<html>Submission successful, please <a href=\"%s\">check the status on Coursera</a></html>"
  }
}