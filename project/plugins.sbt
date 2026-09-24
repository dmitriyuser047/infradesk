addSbtPlugin("org.jetbrains.scala" % "sbt-ide-settings" % "1.1.4")
// Packages the production artifact as a start script plus a lib directory, so the runtime image
// needs a JRE and nothing else — no sbt, no source tree.
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.11.4")
