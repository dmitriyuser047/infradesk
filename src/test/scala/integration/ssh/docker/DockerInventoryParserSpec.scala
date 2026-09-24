package ru.bitec.app.ops
package integration.ssh.docker

import munit.FunSuite

final class DockerInventoryParserSpec extends FunSuite {
  private val FullId = "73ac69cf50927aadda817a4a31fdcf6b56f2d3cfe782dabeab65b961c230fc6d"

  test("parses valid rows and preserves full IDs, names, images and states") {
    val result = parse(s"$FullId\tbackend\tbackend:1.0\trunning\nother\tworker\tworker:2\texited\n")
    assertEquals(result.head, DockerContainerInventory(FullId, "backend", Some("backend:1.0"), Some("running")))
    assertEquals(result(1), DockerContainerInventory("other", "worker", Some("worker:2"), Some("exited")))
  }

  test("treats empty stdout as an authoritative empty inventory") {
    assertEquals(parse("\n  \n"), Nil)
  }

  test("rejects malformed rows") {
    assert(DockerInventoryParser.parse("id\tname\timage\n").isLeft)
    assert(DockerInventoryParser.parse("\tname\timage\trunning\n").isLeft)
  }

  test("rejects duplicate container IDs instead of corrupting the snapshot") {
    assert(DockerInventoryParser.parse("same\tone\timage\trunning\nsame\ttwo\timage\texited\n").isLeft)
  }

  private def parse(value: String): List[DockerContainerInventory] =
    DockerInventoryParser.parse(value).fold(error => fail(error.message), identity)
}
