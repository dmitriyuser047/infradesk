package ru.bitec.app.ops
package infrastructure.http.dto

/** Self-service account requests. Neither carries a user id or an email: the account is the one
  * the session identifies, so these bodies say only what to change, never whose account.
  */
final case class ChangePasswordRequest(currentPassword: String, newPassword: String)
final case class UpdateAccountProfileRequest(displayName: String)
