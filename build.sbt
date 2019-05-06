name := "error_handling_and_scheduling_with_zio.md"

version := "0.1"

scalaVersion := "2.12.8"

libraryDependencies ++=
  Seq(
    "org.scalaz" %% "scalaz-zio" % "1.0-RC4",
    "org.scalatest" %% "scalatest" % "3.0.0" % "test"
  )