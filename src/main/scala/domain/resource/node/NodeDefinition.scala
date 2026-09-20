package ru.bitec.app.ops
package domain.resource.node

import domain.resource.ResourceDefinition

object NodeDefinition extends ResourceDefinition {

  private val sCodeNode = "NODE"

  override type Spec = NodeSpec
  override type Status = NodeStatus

  override def code: String = sCodeNode
}
