package ru.bitec.app.ops
package domain.connection

final case class ConnectionConfig(values: Map[String, String]) {

  def get(key: String): Option[String] =
    values.get(key)

  def updated(
               key: String,
               value: String
             ): ConnectionConfig =
    copy(
      values = values.updated(key, value)
    )
}