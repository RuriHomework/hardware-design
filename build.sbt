ThisBuild / version := "0.1.0"
ThisBuild / scalaVersion := "2.13.16"

lazy val chiselVersion = "6.7.0"

lazy val root = (project in file("."))
  .settings(
    name := "chisel-starter",
    libraryDependencies ++= Seq(
      "org.chipsalliance" %% "chisel" % chiselVersion,
      "edu.berkeley.cs"   %% "chiseltest" % "6.0.0" % Test
    ),
    addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full),
  )
