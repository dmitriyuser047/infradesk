package ru.bitec.app.ops
package infrastructure.http.dto

import java.util.UUID

final case class LoginRequest(email: String, password: String)
final case class MeResponse(id: UUID, email: String, displayName: String, isAdministrator: Boolean = false)
final case class MyOrganizationResponse(id: UUID, code: String, name: String, role: String)
