package ru.bitec.app.ops
package domain.resource

trait ResourceDefinition {

  type Spec <: ResourceSpec
  type Status <: ResourceStatus

  def code: String
}