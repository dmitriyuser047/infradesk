package ru.bitec.app.ops
package bootstrap

/** Which build is running.
  *
  * The image build passes the commit it was made from; a container that reports `unknown` was
  * not produced by the deployment pipeline.
  */
object BuildInfo {

  val version: String = value("INFRADESK_BUILD_VERSION")
  val gitSha: String = value("INFRADESK_GIT_SHA")

  private def value(key: String): String =
    sys.env.get(key).map(_.trim).filter(_.nonEmpty).getOrElse("unknown")
}
