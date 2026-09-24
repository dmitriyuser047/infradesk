scalaVersion := "2.13.18"

lazy val doobieVersion = "1.0.0-RC13"
lazy val dockerVersion = "3.7.1"
lazy val sshjVersion = "0.40.0"
lazy val http4sVersion = "0.23.30"
lazy val flywayVersion = "13.7.0"

lazy val root = rootProject
  .enablePlugins(JavaAppPackaging)
  .settings(
    name := "infradesk",
    Compile / run / fork := true,
    Compile / mainClass := Some("ru.bitec.app.ops.Main"),
    // Production images run this artifact; the version carries the commit the image was built
    // from, so a deployed container can be traced back to its source.
    version := sys.env.getOrElse("INFRADESK_BUILD_VERSION", "0.1.0-SNAPSHOT"),
    idePackagePrefix := Some("ru.bitec.app.ops"),
    libraryDependencies ++= Seq(
      //You can add library dependencies here, for example,
      //"org.scalatest" %% "scalatest" % "3.2.19" % Test,
      "org.scalameta" %% "munit" % "1.2.3" % Test,
      "org.typelevel" %% "cats-effect" % "3.7.0",
      "org.typelevel" %% "log4cats-slf4j" % "2.8.0",
      "ch.qos.logback" % "logback-classic" % "1.5.38",
      "org.flywaydb" % "flyway-core" % flywayVersion,
      "org.flywaydb" % "flyway-database-postgresql" % flywayVersion,
      "org.typelevel" %% "doobie-core"      % doobieVersion,
      "org.typelevel" %% "doobie-postgres"  % doobieVersion,
      "org.typelevel" %% "doobie-hikari"    % doobieVersion,
      "org.mindrot" % "jbcrypt" % "0.4",
      "io.circe" %% "circe-core"   % "0.14.10",
      "io.circe" %% "circe-parser" % "0.14.10",
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "org.http4s" %% "http4s-ember-client" % http4sVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion,
      "org.http4s" %% "http4s-circe" % http4sVersion,
      "com.github.docker-java" % "docker-java-core" % dockerVersion,
      "com.github.docker-java" % "docker-java-transport-httpclient5" % dockerVersion,
      "com.hierynomus" % "sshj" % sshjVersion
    )
  )
