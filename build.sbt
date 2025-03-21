ThisBuild / organization := "io.github.dimitarg"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.3.5"

val http4sVersion = "0.23.30"
val monocleVersion = "3.3.0"
val circeVersion = "0.14.12"
val odinVersion = "0.16.0"
val catsEffectVersion = "3.5.7"

def module(name: String): Project = Project(id = s"haber-${name}",  base = file(s"modules/$name"))
  .settings(
    libraryDependencies ++= Seq(
      "io.github.dimitarg"  %%  "weaver-test-extra" % "0.5.11" % "test",
      "org.scalacheck" %% "scalacheck" % "1.18.1"  % "test",
      "com.disneystreaming" %% "weaver-scalacheck" % "0.8.4" % "test",
    )
  )
  .settings(
    scalacOptions ++= Seq(
      "-source:future",
      // ??? for some reason unused warnings don't work inside for comprehensions, need to investigate why.
      "-Wunused:all",
    ),
    Test / fork := true,
    run / fork := true,

    // these are used by Scalafix
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,
  )

lazy val logging = module("logging")
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "dev.scalafreaks" %% "odin-core" % odinVersion,
      "dev.scalafreaks" %% "odin-slf4j-provider" % odinVersion
    )
  )

lazy val core = module("core")
  .dependsOn(logging)
  .settings(
    libraryDependencies ++= Seq(
      "dev.optics" %% "monocle-core"  % monocleVersion,
      "dev.optics" %% "monocle-macro" % monocleVersion,
      "org.typelevel" %% "kittens" % "3.5.0",
      "co.fs2" %% "fs2-io" % "3.11.0",
    )
  )

lazy val rest = module("rest")
  .dependsOn(core)
  .settings(
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion,
      "org.http4s" %% "http4s-circe" % http4sVersion,
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion % "test",
      "org.http4s" %% "http4s-client" % http4sVersion % "test",
    )
  )

lazy val root = (project in file("."))
  .aggregate(logging, core, rest)

