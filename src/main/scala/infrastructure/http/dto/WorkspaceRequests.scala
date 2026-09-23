package ru.bitec.app.ops
package infrastructure.http.dto

final case class CreateProjectRequest(code: String, name: String, description: Option[String])
final case class CreateEnvironmentRequest(code: String, name: String, kind: String)
