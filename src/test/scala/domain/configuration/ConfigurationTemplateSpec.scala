package ru.bitec.app.ops
package domain.configuration

import munit.FunSuite

import ConfigurationValueType.{BooleanType, IntegerType, StringType}

/** The template language and its renderer, with nothing around them: pure functions of text. */
final class ConfigurationTemplateSpec extends FunSuite {

  private def variable(name: String, valueType: ConfigurationValueType = StringType, default: Option[String] = None) =
    ConfigurationVariableDefinition(name, valueType, required = true, default, None)

  private def codes(result: Either[List[ConfigurationDiagnostic], ValidatedConfiguration]): List[String] =
    result.swap.toOption.toList.flatten.map(_.code)

  private def valid(template: String, variables: ConfigurationVariableDefinition*): ValidatedConfiguration =
    ConfigurationValidation.validate(template, variables.toList).fold(errors => fail(s"not valid: $errors"), identity)

  test("placeholders are found with or without inner spaces, in order of first use") {
    val content = valid("listen {{ port }};\nproto {{protocol}} {{port}}", variable("port", IntegerType), variable("protocol"))
    assertEquals(content.referencedVariables, List("port", "protocol"))
    assertEquals(content.parts.collect { case TemplatePart.Placeholder(name, line, column) => (name, line, column) },
      List(("port", 1, 8), ("protocol", 2, 7), ("port", 2, 20)))
  }

  test("JSON braces, a nested `}}` and single braces are literal text") {
    val template = """{"routing": {"domainStrategy": "{{ strategy }}"}, "inbounds": [{"port": {{ port }}}]}"""
    val content = valid(template, variable("strategy"), variable("port", IntegerType))
    assertEquals(ConfigurationTemplateRenderer.render(content, List(ConfigurationVariableValue("strategy", "IPIfNonMatch"),
      ConfigurationVariableValue("port", "443"))),
      Right("""{"routing": {"domainStrategy": "IPIfNonMatch"}, "inbounds": [{"port": 443}]}"""))
  }

  test("a referenced variable that is not declared is an error naming it and where it is") {
    val result = ConfigurationValidation.validate("port={{ vpn_port }}", List(variable("protocol")))
    assertEquals(result.swap.toOption.map(_.map(d => (d.code, d.variableName, d.line, d.column))),
      Some(List(("CONFIGURATION_VARIABLE_UNDEFINED", Some("vpn_port"), Some(1), Some(6)))))
  }

  test("a declared variable the template never uses is a warning, not an error") {
    val content = valid("static", variable("unused"))
    assertEquals(content.warnings.map(w => (w.code, w.severity, w.variableName)),
      List(("CONFIGURATION_VARIABLE_UNUSED", DiagnosticSeverity.Warning, Some("unused"))))
  }

  test("an unclosed placeholder, one across lines and an invalid name are errors") {
    assertEquals(codes(ConfigurationValidation.validate("a {{ port", List(variable("port")))),
      List("CONFIGURATION_TEMPLATE_UNCLOSED_PLACEHOLDER"))
    assertEquals(codes(ConfigurationValidation.validate("a {{ port\n}}", List(variable("port")))),
      List("CONFIGURATION_TEMPLATE_UNCLOSED_PLACEHOLDER"))
    List("{{ foo.bar }}", "{{ foo-bar }}", "{{ ../../etc/passwd }}", "{{ with space }}", "{{ 9lives }}", "{{ }}", "{{{ port }}}")
      .foreach(template => assertEquals(codes(ConfigurationValidation.validate(template, List(variable("port")))),
        List("CONFIGURATION_TEMPLATE_INVALID_PLACEHOLDER"), template))
  }

  test("variable names are checked, case-sensitive, and unique") {
    List("foo.bar", "foo-bar", "{{foo}}", "../../etc/passwd", "variable with spaces", "", "a" * 65)
      .foreach(name => assert(codes(ConfigurationValidation.validate("x", List(variable(name))))
        .contains("CONFIGURATION_VARIABLE_INVALID_NAME"), name))
    List("port", "protocol", "routing_strategy", "server_id", "public_ip", "A1")
      .foreach(name => assertEquals(codes(ConfigurationValidation.validate("x", List(variable(name)))), List.empty, name))
    // Case-sensitive: two names, no duplicate.
    assertEquals(codes(ConfigurationValidation.validate("{{ port }}{{ Port }}", List(variable("port"), variable("Port")))), List.empty)
    assertEquals(codes(ConfigurationValidation.validate("{{ port }}", List(variable("port"), variable("port")))),
      List("CONFIGURATION_VARIABLE_DUPLICATE"))
  }

  test("defaults must be canonical values of their type, without coercion") {
    def defaultOk(valueType: ConfigurationValueType, value: String) =
      codes(ConfigurationValidation.validate("{{ v }}", List(variable("v", valueType, Some(value))))).isEmpty
    assert(defaultOk(StringType, "anything at all"))
    assert(!defaultOk(StringType, "x" * 4097))
    List("0", "443", "-7", "9223372036854775807").foreach(value => assert(defaultOk(IntegerType, value), value))
    List("+7", "007", "1e3", "1.5", "", " 1", "-0", "9223372036854775808").foreach(value => assert(!defaultOk(IntegerType, value), value))
    List("true", "false").foreach(value => assert(defaultOk(BooleanType, value), value))
    List("yes", "1", "on", "TRUE", "True").foreach(value => assert(!defaultOk(BooleanType, value), value))
  }

  test("limits: template size, variable count, description length") {
    val big = "x" * (ConfigurationLimits.MaxTemplateBytes + 1)
    assertEquals(codes(ConfigurationValidation.validate(big, List.empty)), List("CONFIGURATION_TEMPLATE_TOO_LARGE"))
    // UTF-8 bytes, not characters: 128 Ki two-byte letters are 256 KiB exactly and fit.
    assertEquals(codes(ConfigurationValidation.validate("ж" * (ConfigurationLimits.MaxTemplateBytes / 2), List.empty)), List.empty)
    assertEquals(codes(ConfigurationValidation.validate("ж" * (ConfigurationLimits.MaxTemplateBytes / 2 + 1), List.empty)),
      List("CONFIGURATION_TEMPLATE_TOO_LARGE"))
    assertEquals(codes(ConfigurationValidation.validate("   \n ", List.empty)), List("CONFIGURATION_TEMPLATE_EMPTY"))
    val hundred = (1 to 100).map(i => variable(s"v$i")).toList
    assertEquals(codes(ConfigurationValidation.validate("x", hundred)), List.empty)
    assertEquals(codes(ConfigurationValidation.validate("x", variable("v101") :: hundred)), List("CONFIGURATION_VARIABLES_TOO_MANY"))
    assertEquals(codes(ConfigurationValidation.validate("x",
      List(ConfigurationVariableDefinition("v", StringType, required = true, None, Some("d" * 1001))))),
      List("CONFIGURATION_VARIABLE_DESCRIPTION_TOO_LONG"))
  }

  test("Unicode and UTF-8 content is preserved exactly") {
    val template = "# Настройки — ✓ 日本語 🚀\nname = \"{{ name }}\"\r\n\ttab"
    val content = valid(template, variable("name"))
    assertEquals(ConfigurationTemplateRenderer.render(content, List(ConfigurationVariableValue("name", "узел-01"))),
      Right("# Настройки — ✓ 日本語 🚀\nname = \"узел-01\"\r\n\ttab"))
  }

  test("an explicit value beats the default, the default fills in, a missing one fails") {
    val content = valid("{{ a }}:{{ b }}", variable("a", default = Some("da")), variable("b", default = Some("db")))
    assertEquals(ConfigurationTemplateRenderer.render(content, List(ConfigurationVariableValue("a", "given"))), Right("given:db"))
    val missing = valid("{{ a }}", variable("a"))
    assertEquals(ConfigurationTemplateRenderer.render(missing, List.empty), Left(ConfigurationRenderError.MissingValue("a")))
  }

  test("values are checked against their type; unknown and duplicate values are refused") {
    val content = valid("{{ port }}", variable("port", IntegerType))
    assertEquals(ConfigurationTemplateRenderer.render(content, List(ConfigurationVariableValue("port", "http"))),
      Left(ConfigurationRenderError.InvalidValue("port")))
    assertEquals(ConfigurationTemplateRenderer.render(content, List(ConfigurationVariableValue("port", "1"), ConfigurationVariableValue("other", "x"))),
      Left(ConfigurationRenderError.UnknownVariable("other")))
    assertEquals(ConfigurationTemplateRenderer.render(content, List(ConfigurationVariableValue("port", "1"), ConfigurationVariableValue("port", "2"))),
      Left(ConfigurationRenderError.DuplicateValue("port")))
  }

  test("rendering is deterministic and never evaluates anything") {
    val template = "run $(rm -rf /) ${HOME} `id` %PATH% {{ cmd }} <?php ?>"
    val content = valid(template, variable("cmd"))
    val values = List(ConfigurationVariableValue("cmd", "$(whoami) {{ cmd }}"))
    val first = ConfigurationTemplateRenderer.render(content, values)
    assertEquals(first, Right("run $(rm -rf /) ${HOME} `id` %PATH% $(whoami) {{ cmd }} <?php ?>"))
    // A value that looks like a placeholder is text: it is not substituted again.
    assertEquals(ConfigurationTemplateRenderer.render(content, values), first)
  }

  test("rendered output is bounded") {
    val template = "{{ v }}" * 300
    val content = valid(template, variable("v"))
    assertEquals(ConfigurationTemplateRenderer.render(content, List(ConfigurationVariableValue("v", "x" * 4096))),
      Left(ConfigurationRenderError.RenderedTooLarge("v")))
  }
}
