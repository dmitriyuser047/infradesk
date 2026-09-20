scalaVersion := "2.11.12"

lazy val root = rootProject
  .settings(
    name := "infradesk",
    idePackagePrefix := Some("ru.bitec.app.ops"),
    libraryDependencies ++= Seq(
      //You can add library dependencies here, for example,
      //"org.scalatest" %% "scalatest" % "3.2.19" % Test,
      //"org.scalameta" %% "munit" % "1.2.3" % Test
    )
  )
