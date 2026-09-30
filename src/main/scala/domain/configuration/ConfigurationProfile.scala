package ru.bitec.app.ops
package domain.configuration

import java.time.Instant
import java.util.UUID

/** The type of a template variable. Values travel as text and are checked against the type. */
sealed trait ConfigurationValueType {
  def code: String
  /** Whether a textual value is a canonical value of this type. No coercion: "yes" is not true. */
  def accepts(value: String): Boolean
}

object ConfigurationValueType {
  case object StringType extends ConfigurationValueType {
    val code = "STRING"
    def accepts(value: String): Boolean = value.length <= ConfigurationLimits.MaxValueLength
  }

  case object IntegerType extends ConfigurationValueType {
    val code = "INTEGER"
    /** A canonical decimal integer that fits a 64-bit signed long: "42", "-7", never "+7", "007" or "1e3". */
    def accepts(value: String): Boolean =
      value.matches("-?(0|[1-9][0-9]{0,18})") && value != "-0" && scala.util.Try(value.toLong).isSuccess
  }

  case object BooleanType extends ConfigurationValueType {
    val code = "BOOLEAN"
    def accepts(value: String): Boolean = value == "true" || value == "false"
  }

  val All: List[ConfigurationValueType] = List(StringType, IntegerType, BooleanType)

  def fromCode(code: String): Either[IllegalArgumentException, ConfigurationValueType] =
    All.find(_.code == code).toRight(new IllegalArgumentException(s"Unsupported configuration value type '$code'"))
}

/** A variable a template may reference. Secret-like types do not exist here on purpose. */
final case class ConfigurationVariableDefinition(
  name: String,
  valueType: ConfigurationValueType,
  required: Boolean,
  defaultValue: Option[String],
  description: Option[String]
)

/** A value given for a variable, as text; its type is the definition's. */
final case class ConfigurationVariableValue(name: String, value: String)

sealed trait ConfigurationProfileKind { def code: String }
object ConfigurationProfileKind {
  case object FileTemplate extends ConfigurationProfileKind { val code = "FILE_TEMPLATE" }
  case object RemnawaveConfig extends ConfigurationProfileKind { val code = "REMNAWAVE_CONFIG" }
  val All: List[ConfigurationProfileKind] = List(FileTemplate, RemnawaveConfig)
  def fromCode(code: String): Either[IllegalArgumentException, ConfigurationProfileKind] =
    All.find(_.code == code).toRight(new IllegalArgumentException("Unknown configuration profile kind"))
}

/** A reusable definition of desired configuration, owned by an organization.
  *
  * It holds metadata only. Its content lives in immutable revisions; `latestRevisionNumber` is
  * the number of the newest one and is advanced under a row lock, which is what makes revision
  * numbers sequential without gaps or duplicates.
  */
final case class ConfigurationProfile(
  id: UUID,
  organizationId: UUID,
  code: String,
  name: String,
  description: Option[String],
  archived: Boolean,
  latestRevisionNumber: Int,
  createdAt: Instant,
  updatedAt: Instant,
  kind: ConfigurationProfileKind = ConfigurationProfileKind.FileTemplate
)

/** One immutable version of a profile's content. Once written it is never changed. */
final case class ConfigurationRevision(
  id: UUID,
  organizationId: UUID,
  profileId: UUID,
  revisionNumber: Int,
  template: String,
  variables: List[ConfigurationVariableDefinition],
  createdByUserId: UUID,
  createdAt: Instant
)

/** The hard bounds of configuration content. The backend enforces them; the UI only mirrors them. */
object ConfigurationLimits {
  /** UTF-8 bytes of a template. */
  val MaxTemplateBytes: Int = 256 * 1024
  val MaxVariables: Int = 100
  val MaxVariableNameLength: Int = 64
  val MaxVariableDescriptionLength: Int = 1000
  /** Of a default, a preview value, or any variable value. */
  val MaxValueLength: Int = 4096
  val MaxProfileNameLength: Int = 255
  val MaxProfileDescriptionLength: Int = 4000
  val MaxProfileCodeLength: Int = 64

  /** Case-sensitive: `port` and `Port` are two names, and a duplicate means an exact match. */
  val VariableNamePattern: String = "[A-Za-z][A-Za-z0-9_]{0,63}"
  /** A stable machine identifier, lower case: `vpn-production`, `nginx_edge`. */
  val ProfileCodePattern: String = "[a-z0-9][a-z0-9_-]{0,63}"
}
