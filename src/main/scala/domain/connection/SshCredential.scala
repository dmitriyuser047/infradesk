package ru.bitec.app.ops
package domain.connection

/** How a connection authenticates. This is configuration, not a secret: it is stored next to the
  * host and the username, and it is safe to return from the API.
  */
sealed trait SshAuthenticationType {
  def code: String
}

object SshAuthenticationType {

  case object Password extends SshAuthenticationType { override val code: String = "PASSWORD" }
  case object PrivateKey extends SshAuthenticationType { override val code: String = "PRIVATE_KEY" }

  val All: List[SshAuthenticationType] = List(Password, PrivateKey)

  def fromCode(code: String): Either[IllegalArgumentException, SshAuthenticationType] =
    All.find(_.code == code)
      .toRight(new IllegalArgumentException(s"Unsupported SSH authentication type '$code'"))
}

/** The secret itself: what is encrypted at rest and handed to the SSH transport, and nothing
  * that may be rendered, logged or returned.
  *
  * Every case redacts itself, because a case class prints its fields by default and one
  * accidental interpolation of a credential would put a private key in a log line forever.
  */
sealed trait SshCredential {
  def authenticationType: SshAuthenticationType
}

object SshCredential {

  final case class Password(value: String) extends SshCredential {
    override def authenticationType: SshAuthenticationType = SshAuthenticationType.Password
    override def toString: String = "SshCredential.Password(<redacted>)"
  }

  final case class PrivateKey(pem: String, passphrase: Option[String]) extends SshCredential {
    override def authenticationType: SshAuthenticationType = SshAuthenticationType.PrivateKey
    override def toString: String =
      s"SshCredential.PrivateKey(<redacted>, passphrase=${if (passphrase.isDefined) "set" else "none"})"
  }
}
