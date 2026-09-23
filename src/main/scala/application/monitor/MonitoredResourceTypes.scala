package ru.bitec.app.ops
package application.monitor

import domain.resource.node.NodeDefinition

/** Resource types monitoring applies to. Metrics are collected for nodes only, so a rule on any
  * other type is not evaluated. This is a monitoring feature rule, not a persistence one.
  */
object MonitoredResourceTypes {
  val codes: List[String] = List(NodeDefinition.code)
}
