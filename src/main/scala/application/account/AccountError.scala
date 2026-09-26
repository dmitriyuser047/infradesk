package ru.bitec.app.ops
package application.account

/** Why a self-service account change was refused, as a stable code the transport maps to a status
  * and the frontend maps to a localized sentence.
  *
  * These are expected outcomes of a request, not failures: none of them carries a password, a hash
  * or anything that would help an attacker learn about an account, and none of them is logged as an
  * error. An unexpected database or hashing failure is a throwable and is handled as one.
  */
sealed trait AccountError {
  def code: String
}

object AccountError {
  case object CurrentPasswordInvalid extends AccountError { val code = "CURRENT_PASSWORD_INVALID" }
  case object PasswordTooShort extends AccountError { val code = "PASSWORD_TOO_SHORT" }
  case object PasswordTooLong extends AccountError { val code = "PASSWORD_TOO_LONG" }
  case object PasswordInvalidCharacters extends AccountError { val code = "PASSWORD_INVALID_CHARACTERS" }
  case object PasswordSameAsCurrent extends AccountError { val code = "PASSWORD_SAME_AS_CURRENT" }
  case object DisplayNameEmpty extends AccountError { val code = "DISPLAY_NAME_EMPTY" }
  case object DisplayNameTooLong extends AccountError { val code = "DISPLAY_NAME_TOO_LONG" }
  case object DisplayNameInvalidCharacters extends AccountError { val code = "DISPLAY_NAME_INVALID_CHARACTERS" }

  /** The authenticated account is gone or was deactivated between the read and the write. */
  case object AccountNotFound extends AccountError { val code = "ACCOUNT_NOT_FOUND" }
}
