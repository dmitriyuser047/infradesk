package ru.bitec.app.ops
package integration.remnawave

import io.circe.parser.parse

/** Only the stable response envelope is needed for an authenticated connectivity probe. */
object RemnawaveModels {
  def isStatsEnvelope(json: String): Boolean =
    parse(json).toOption.exists { document =>
      val response = document.hcursor.downField("response")
      response.focus.exists(_.isObject) &&
        response.get[Double]("uptime").toOption.exists(value => value.isFinite && value >= 0) &&
        response.downField("users").get[Double]("totalUsers").toOption
          .exists(value => value.isFinite && value >= 0)
    }
}
