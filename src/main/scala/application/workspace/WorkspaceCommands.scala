package ru.bitec.app.ops
package application.workspace

final case class CreateProjectCommand(code: String, name: String, description: Option[String])
final case class CreateEnvironmentCommand(code: String, name: String, kind: String)

final case class WorkspaceManagementError(code: String, override val getMessage: String)
  extends RuntimeException(getMessage)

private[workspace] object WorkspaceValidation {
  def codeAndName(code: String, name: String): Either[WorkspaceManagementError, (String, String)] = {
    val trimmedCode = code.trim
    val trimmedName = name.trim
    if (trimmedCode.isEmpty || trimmedCode.length > 64)
      Left(WorkspaceManagementError("INVALID_REQUEST", "Invalid code"))
    else if (trimmedName.isEmpty || trimmedName.length > 255)
      Left(WorkspaceManagementError("INVALID_REQUEST", "Invalid name"))
    else Right(trimmedCode -> trimmedName)
  }
}
