package ru.bitec.app.ops
package application.account

/** The rules a new password and a display name must satisfy. The backend is the source of truth;
  * the frontend mirrors these for a faster message, but never decides.
  *
  * The password policy is deliberately modest — length over composition rules — so it is
  * predictable and does not push people towards patterns they write down. Uppercase / digit /
  * symbol requirements are intentionally not imposed.
  */
object AccountValidation {

  val MinPasswordLength: Int = 12
  val MaxPasswordLength: Int = 128
  val MaxDisplayNameLength: Int = 255

  /** Validates a new password against the current one. Both are plaintext here, so "same as the
    * current password" is a direct comparison and never a hash comparison.
    */
  def password(newPassword: String, currentPassword: String): Either[AccountError, Unit] =
    if (newPassword.length < MinPasswordLength) Left(AccountError.PasswordTooShort)
    else if (newPassword.length > MaxPasswordLength) Left(AccountError.PasswordTooLong)
    else if (newPassword.exists(_.isControl)) Left(AccountError.PasswordInvalidCharacters)
    else if (newPassword == currentPassword) Left(AccountError.PasswordSameAsCurrent)
    else Right(())

  /** Validates and normalizes a display name, returning the trimmed value to store. */
  def displayName(raw: String): Either[AccountError, String] = {
    val trimmed = raw.trim
    if (trimmed.isEmpty) Left(AccountError.DisplayNameEmpty)
    else if (trimmed.length > MaxDisplayNameLength) Left(AccountError.DisplayNameTooLong)
    // A name is a single line: control characters, including new lines and tabs, are rejected.
    else if (trimmed.exists(_.isControl)) Left(AccountError.DisplayNameInvalidCharacters)
    else Right(trimmed)
  }
}
