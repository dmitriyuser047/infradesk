package ru.bitec.app.ops
package domain.operation

import munit.FunSuite

final class ResourceOperationDomainSpec extends FunSuite {
  test("operation codes round-trip and reject arbitrary commands") {
    ResourceOperationCode.All.foreach(value => assertEquals(ResourceOperationCode.fromCode(value.code), Right(value)))
    assert(ResourceOperationCode.fromCode("rm -rf /").isLeft)
  }

  test("execution statuses are a closed typed set") {
    OperationExecutionStatus.All.foreach(value => assertEquals(OperationExecutionStatus.fromCode(value.code), Right(value)))
    assert(OperationExecutionStatus.fromCode("RETRYING").isLeft)
  }
}

