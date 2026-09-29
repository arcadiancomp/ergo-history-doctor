import sbt._
import Keys._

ThisBuild / organization := "com.arcadiancomputers"
ThisBuild / version := "0.1.0"
ThisBuild / scalaVersion := "2.12.20"

name := "ergo-history-doctor"
publish / skip := true

lazy val ergoJarFile = settingKey[File]("Path to the exact Ergo node JAR used by the node being inspected")

ergoJarFile := {
  val configured = sys.env.get("ERGO_JAR")
    .orElse(sys.props.get("ergo.jar"))
    .map(file)
    .getOrElse(baseDirectory.value / "lib" / "ergo.jar")

  if (!configured.isFile) {
    sys.error(
      "Ergo node JAR not found. Set ERGO_JAR, pass -Dergo.jar=/path/to/ergo-x.y.z.jar, " +
        "or place the matching node JAR at lib/ergo.jar"
    )
  }
  configured.getAbsoluteFile
}

Compile / unmanagedJars := Seq(Attributed.blank(ergoJarFile.value))
Runtime / unmanagedJars := Seq(Attributed.blank(ergoJarFile.value))

Compile / mainClass := Some("org.ergoplatform.nodeView.history.ErgoHistoryDoctor")

scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-target:jvm-1.8"
)
