package ru.bitec.app.ops
package domain.configuration

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

/** The desired state of one file on one resource: an exact, immutable revision of a profile, the
  * path it belongs at, and the values a user gave explicitly.
  *
  * It pins `profileRevisionNumber`, never "latest": a newer revision of the profile changes nothing
  * here until the assignment is explicitly moved to it. `version` is the assignment's own optimistic
  * concurrency number — not a configuration revision — and every change advances it by one.
  * Nothing about delivery lives here: no connection, host or credential. Delivering is not this.
  */
final case class ConfigurationAssignment(
  id: UUID,
  organizationId: UUID,
  resourceId: UUID,
  profileId: UUID,
  profileRevisionNumber: Int,
  targetPath: String,
  version: Int,
  removedAt: Option[Instant],
  createdAt: Instant,
  updatedAt: Instant,
  /** The rule that created or adopted this assignment and now manages its profile, revision and path. */
  sourceRuleId: Option[UUID] = None
) {
  def active: Boolean = removedAt.isEmpty
  def managed: Boolean = sourceRuleId.isDefined
}

/** Where the desired file belongs: a normalized absolute POSIX path, checked as text only.
  *
  * The path is never resolved, never touched on the backend's own filesystem and never followed:
  * it is metadata about a remote file. Only an already normalized form is accepted, so one file has
  * exactly one spelling and two assignments cannot claim it under two names.
  */
object ConfigurationTargetPath {
  val MaxLength: Int = 4096
  /** POSIX NAME_MAX, in bytes. */
  val MaxSegmentBytes: Int = 255

  def validate(raw: String): Either[String, String] =
    if (raw.isEmpty) Left("empty")
    else if (raw.length > MaxLength) Left("too long")
    else if (!raw.startsWith("/")) Left("not absolute")
    else if (raw.exists(c => Character.isISOControl(c) || c == '\u2028' || c == '\u2029')) Left("control character")
    else if (raw == "/" || raw.endsWith("/")) Left("not a file")
    else {
      val segments = raw.substring(1).split("/", -1).toList
      if (segments.exists(_.isEmpty)) Left("not normalized")
      else if (segments.exists(segment => segment == "." || segment == "..")) Left("not normalized")
      else if (segments.exists(_.getBytes(StandardCharsets.UTF_8).length > MaxSegmentBytes)) Left("segment too long")
      else Right(raw)
    }
}

/** Explicit values checked against the exact revision they are for, then rendered by the one
  * renderer. Pure and bounded; nothing is stored or executed.
  *
  * Every explicit value is checked, not only the referenced ones: a value that would be stored must
  * be valid for its variable even if the template does not use it today. A required variable must
  * resolve — an explicit value or its default — and every placeholder must resolve too; a missing
  * one is an error, never an empty substitution.
  */
object ConfigurationDesiredState {
  import ConfigurationRenderError._

  def render(revision: ConfigurationRevision, values: List[ConfigurationVariableValue]): Either[ConfigurationRenderError, String] = {
    val definitions = revision.variables.map(variable => variable.name -> variable).toMap
    val names = values.map(_.name)
    val given = names.toSet
    names.diff(names.distinct).headOption.map[ConfigurationRenderError](DuplicateValue(_))
      .orElse(values.collectFirst { case value if !definitions.contains(value.name) => UnknownVariable(value.name) })
      .orElse(values.collectFirst {
        case value if value.value.length > ConfigurationLimits.MaxValueLength || !definitions(value.name).valueType.accepts(value.value) =>
          InvalidValue(value.name)
      })
      .orElse(revision.variables.collectFirst {
        case variable if variable.required && !given.contains(variable.name) && variable.defaultValue.isEmpty => MissingValue(variable.name)
      }) match {
      case Some(error) => Left(error)
      case None => ConfigurationTemplateRenderer.render(revision, values)
    }
  }
}
