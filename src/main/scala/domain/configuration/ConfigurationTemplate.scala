package ru.bitec.app.ops
package domain.configuration

import java.nio.charset.StandardCharsets

/** A template, parsed: literal text as written, and `{{ name }}` substitutions. Nothing else. */
sealed trait TemplatePart

object TemplatePart {
  final case class Literal(text: String) extends TemplatePart
  /** Where it starts, 1-based, for diagnostics. */
  final case class Placeholder(name: String, line: Int, column: Int) extends TemplatePart
}

sealed trait DiagnosticSeverity { def code: String }

object DiagnosticSeverity {
  case object Error extends DiagnosticSeverity { val code = "ERROR" }
  case object Warning extends DiagnosticSeverity { val code = "WARNING" }
}

/** One finding about configuration content. It names a code and where, never echoes content. */
final case class ConfigurationDiagnostic(
  code: String,
  severity: DiagnosticSeverity,
  variableName: Option[String] = None,
  line: Option[Int] = None,
  column: Option[Int] = None
)

object ConfigurationDiagnostic {
  val TemplateEmpty = "CONFIGURATION_TEMPLATE_EMPTY"
  val TemplateTooLarge = "CONFIGURATION_TEMPLATE_TOO_LARGE"
  val UnclosedPlaceholder = "CONFIGURATION_TEMPLATE_UNCLOSED_PLACEHOLDER"
  val InvalidPlaceholder = "CONFIGURATION_TEMPLATE_INVALID_PLACEHOLDER"
  val VariablesTooMany = "CONFIGURATION_VARIABLES_TOO_MANY"
  val VariableInvalidName = "CONFIGURATION_VARIABLE_INVALID_NAME"
  val VariableDuplicate = "CONFIGURATION_VARIABLE_DUPLICATE"
  val VariableInvalidDefault = "CONFIGURATION_VARIABLE_INVALID_DEFAULT"
  val VariableDescriptionTooLong = "CONFIGURATION_VARIABLE_DESCRIPTION_TOO_LONG"
  val VariableUndefined = "CONFIGURATION_VARIABLE_UNDEFINED"
  val VariableUnused = "CONFIGURATION_VARIABLE_UNUSED"

  /** Enough to fix a template; a template with more problems is not worth listing in full. */
  val MaxReported = 100

  def error(code: String, variableName: Option[String] = None, line: Option[Int] = None, column: Option[Int] = None) =
    ConfigurationDiagnostic(code, DiagnosticSeverity.Error, variableName.map(_.take(ConfigurationLimits.MaxVariableNameLength)), line, column)
}

/** The grammar: `{{`, optional spaces, a variable name, optional spaces, `}}`, on one line.
  *
  * Only `{{` is special. A lone `{` or `}` — and a `}}` outside a placeholder, which nested JSON
  * such as `{"a":{"b":1}}` writes all the time — is literal text. There are no conditions, loops,
  * functions, includes, lookups or escapes: a template can do nothing but substitute values.
  */
object ConfigurationTemplateParser {
  import ConfigurationDiagnostic._

  def parse(template: String): Either[List[ConfigurationDiagnostic], List[TemplatePart]] = {
    val parts = List.newBuilder[TemplatePart]
    val errors = List.newBuilder[ConfigurationDiagnostic]
    var errorCount = 0
    val literal = new java.lang.StringBuilder
    var index = 0
    var line = 1
    var column = 1

    def advance(from: Int, to: Int): Unit = {
      var i = from
      while (i < to) {
        if (template.charAt(i) == '\n') { line += 1; column = 1 } else column += 1
        i += 1
      }
    }
    def report(diagnostic: ConfigurationDiagnostic): Unit = {
      if (errorCount < MaxReported) errors += diagnostic
      errorCount += 1
    }
    def flushLiteral(): Unit = if (literal.length > 0) {
      parts += TemplatePart.Literal(literal.toString)
      literal.setLength(0)
    }

    while (index < template.length) {
      val open = template.indexOf("{{", index)
      if (open < 0) {
        literal.append(template, index, template.length)
        advance(index, template.length)
        index = template.length
      } else {
        literal.append(template, index, open)
        advance(index, open)
        val startLine = line
        val startColumn = column
        val close = template.indexOf("}}", open + 2)
        val lineEnd = template.indexOf('\n', open + 2)
        if (close < 0 || (lineEnd >= 0 && lineEnd < close)) {
          report(error(UnclosedPlaceholder, None, Some(startLine), Some(startColumn)))
          // Keep reading after the opening braces, so later mistakes are reported too.
          literal.append("{{")
          advance(open, open + 2)
          index = open + 2
        } else {
          val name = template.substring(open + 2, close).trim
          if (name.matches(ConfigurationLimits.VariableNamePattern)) {
            flushLiteral()
            parts += TemplatePart.Placeholder(name, startLine, startColumn)
          } else {
            report(error(InvalidPlaceholder, None, Some(startLine), Some(startColumn)))
          }
          advance(open, close + 2)
          index = close + 2
        }
      }
    }
    flushLiteral()
    val found = errors.result()
    if (found.isEmpty) Right(parts.result()) else Left(found)
  }
}

/** Configuration content that passed validation: safe to store and to render. */
final case class ValidatedConfiguration(
  template: String,
  variables: List[ConfigurationVariableDefinition],
  parts: List[TemplatePart],
  /** Distinct, in the order of their first use. */
  referencedVariables: List[String],
  warnings: List[ConfigurationDiagnostic]
)

/** Checks content before it is stored. Pure and bounded: no I/O, nothing evaluated. */
object ConfigurationValidation {
  import ConfigurationDiagnostic._
  import ConfigurationLimits._

  def validate(
    template: String,
    variables: List[ConfigurationVariableDefinition]
  ): Either[List[ConfigurationDiagnostic], ValidatedConfiguration] = {
    val definitionErrors = validateDefinitions(variables)
    val templateResult: Either[List[ConfigurationDiagnostic], List[TemplatePart]] =
      if (template.getBytes(StandardCharsets.UTF_8).length > MaxTemplateBytes) Left(List(error(TemplateTooLarge)))
      else if (template.trim.isEmpty) Left(List(error(TemplateEmpty)))
      else ConfigurationTemplateParser.parse(template)

    val parts = templateResult.getOrElse(List.empty)
    val placeholders = parts.collect { case placeholder: TemplatePart.Placeholder => placeholder }
    val referenced = placeholders.map(_.name).distinct
    val declared = variables.map(_.name).toSet
    val undefined = placeholders
      .filterNot(placeholder => declared.contains(placeholder.name))
      .groupBy(_.name).values.map(_.head).toList
      .sortBy(placeholder => (placeholder.line, placeholder.column))
      .map(placeholder => error(VariableUndefined, Some(placeholder.name), Some(placeholder.line), Some(placeholder.column)))

    val errors = (definitionErrors ++ templateResult.swap.getOrElse(List.empty) ++ undefined).take(MaxReported)
    if (errors.nonEmpty) Left(errors)
    else {
      val used = referenced.toSet
      val unused = variables.filterNot(variable => used.contains(variable.name))
        .map(variable => ConfigurationDiagnostic(VariableUnused, DiagnosticSeverity.Warning, Some(variable.name)))
      Right(ValidatedConfiguration(template, variables, parts, referenced, unused))
    }
  }

  private def validateDefinitions(variables: List[ConfigurationVariableDefinition]): List[ConfigurationDiagnostic] = {
    val tooMany = if (variables.size > MaxVariables) List(error(VariablesTooMany)) else List.empty
    val names = variables.map(_.name)
    val duplicates = names.diff(names.distinct).distinct.map(name => error(VariableDuplicate, Some(name)))
    val perVariable = variables.take(MaxVariables).flatMap { variable =>
      val name = Option(variable.name)
      List(
        Option.when(!variable.name.matches(VariableNamePattern))(error(VariableInvalidName, name)),
        Option.when(variable.description.exists(_.length > MaxVariableDescriptionLength))(error(VariableDescriptionTooLong, name)),
        Option.when(variable.defaultValue.exists(value => value.length > MaxValueLength || !variable.valueType.accepts(value)))(
          error(VariableInvalidDefault, name))
      ).flatten
    }
    tooMany ++ perVariable ++ duplicates
  }
}

sealed trait ConfigurationRenderError {
  def code: String
  def variableName: String
}

object ConfigurationRenderError {
  /** A referenced variable has neither a value nor a default. */
  final case class MissingValue(variableName: String) extends ConfigurationRenderError { val code = "CONFIGURATION_VALUE_MISSING" }
  /** A value is not a canonical value of its variable's type. */
  final case class InvalidValue(variableName: String) extends ConfigurationRenderError { val code = "CONFIGURATION_VALUE_INVALID" }
  /** A value was given for a variable the revision does not declare. */
  final case class UnknownVariable(variableName: String) extends ConfigurationRenderError { val code = "CONFIGURATION_VALUE_UNKNOWN" }
  /** The same variable was given twice. */
  final case class DuplicateValue(variableName: String) extends ConfigurationRenderError { val code = "CONFIGURATION_VALUE_DUPLICATE" }
  /** The substituted text would exceed the rendered size limit. */
  final case class RenderedTooLarge(variableName: String) extends ConfigurationRenderError { val code = "CONFIGURATION_RENDERED_TOO_LARGE" }
}

/** Substitutes values into validated content. Pure, deterministic and bounded.
  *
  * Every referenced variable must resolve — an explicit value first, then its default — or the
  * render fails: a silently empty substitution would produce a configuration that looks valid
  * and is not. The output is text and is never interpreted; `$(...)` in a template stays `$(...)`.
  */
object ConfigurationTemplateRenderer {
  import ConfigurationRenderError._

  /** 1 MiB of characters: generous for a configuration file, fatal for an accidental blow-up. */
  val MaxRenderedLength: Int = 1024 * 1024

  def render(
    configuration: ValidatedConfiguration,
    values: List[ConfigurationVariableValue]
  ): Either[ConfigurationRenderError, String] = {
    val definitions = configuration.variables.map(variable => variable.name -> variable).toMap
    val names = values.map(_.name)
    names.diff(names.distinct).headOption match {
      case Some(duplicate) => Left(DuplicateValue(duplicate))
      case None =>
        values.find(value => !definitions.contains(value.name)) match {
          case Some(unknown) => Left(UnknownVariable(unknown.name))
          case None =>
            val given = values.map(value => value.name -> value.value).toMap
            val output = new java.lang.StringBuilder
            configuration.parts.foldLeft[Either[ConfigurationRenderError, Unit]](Right(())) {
              case (failed @ Left(_), _) => failed
              case (_, TemplatePart.Literal(text)) => append(output, text, "")
              case (_, TemplatePart.Placeholder(name, _, _)) =>
                definitions.get(name) match {
                  case None => Left(MissingValue(name))
                  case Some(definition) =>
                    given.get(name).orElse(definition.defaultValue) match {
                      case None => Left(MissingValue(name))
                      case Some(value) if !definition.valueType.accepts(value) => Left(InvalidValue(name))
                      case Some(value) => append(output, value, name)
                    }
                }
            }.map(_ => output.toString)
        }
    }
  }

  /** Renders a stored revision. Its content was validated when it was written. */
  def render(
    revision: ConfigurationRevision,
    values: List[ConfigurationVariableValue]
  ): Either[ConfigurationRenderError, String] =
    ConfigurationValidation.validate(revision.template, revision.variables) match {
      case Right(configuration) => render(configuration, values)
      // A stored revision always validates; if one ever did not, no value makes it renderable.
      case Left(_) => Left(MissingValue(""))
    }

  private def append(output: java.lang.StringBuilder, text: String, variableName: String): Either[ConfigurationRenderError, Unit] =
    if (output.length.toLong + text.length > MaxRenderedLength) Left(RenderedTooLarge(variableName))
    else { output.append(text); Right(()) }
}
