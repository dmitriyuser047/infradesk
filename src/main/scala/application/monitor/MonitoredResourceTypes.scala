package ru.bitec.app.ops
package application.monitor

import domain.resource.node.NodeDefinition

/** Resource types monitoring applies to. Metrics are collected for nodes only, so a rule on any
  * other type is neither evaluated nor accepted. This is a monitoring feature rule, not a
  * persistence one.
  */
object MonitoredResourceTypes {

  private val supported: Set[String] = Set(NodeDefinition.code)

  val codes: List[String] = supported.toList.sorted

  def supports(resourceTypeCode: String): Boolean = supported.contains(resourceTypeCode)
}
