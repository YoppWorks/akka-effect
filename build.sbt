import BuildSettings._
import MimaSettings._

Global / onChangedBuildSource := ReloadOnSourceChanges

lazy val akkaVersion        = "2.6.19"
lazy val catsEffectVersion  = "3.2.5"
lazy val fs2Version         = "3.1.1"
lazy val logbackVersion     = "1.2.11"
lazy val scalaCheckVersion  = "1.15.4"
lazy val specs2Version      = "4.16.0"
lazy val zioVersion         = "1.0.15"

inThisBuild(
  Seq(
    organization := "aio",
    organizationName := "Yoppworks",
    organizationHomepage := Some(url("https://www.yoppworks.com")),
    licenses := List(
      "Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0")
    )
  )
)

lazy val root = project
  .in(file("."))
  .settings(
    name := "akka-effect",
    publish / skip := true)
  .aggregate(
    core,
    `core-tests`,
    benchmarks
  )

lazy val core = project
  .in(file("core"))
  .settings(stdSettings("akka-effect"))
  .settings(buildInfoSettings("aio"))
  .settings(mimaSettings(failOnProblem = true))
  .enablePlugins(BuildInfoPlugin)
  .settings(
    // For benchmarking
    isSnapshot := false,
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
      "ch.qos.logback" % "logback-classic" % logbackVersion
    ))

lazy val `core-tests` = project
  .in(file("core-tests"))
  .dependsOn(core)
  .settings(stdSettings("core-tests"))
  .settings(buildInfoSettings("aio"))
  .settings(mimaSettings(failOnProblem = false))
  .enablePlugins(BuildInfoPlugin)
  .settings(publish / skip := true)
  .settings(
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion % Test,
      "org.specs2" %% "specs2-core" % specs2Version % Test,
      "org.specs2" %% "specs2-scalacheck" % specs2Version % Test
    ))

lazy val benchmarks = project.module
  .dependsOn(core)
  .enablePlugins(JmhPlugin)
  .settings(
    // skip Scala3 benchmarks for now
    crossScalaVersions --= List(Scala3),
    publish / skip := true,
    libraryDependencies ++= Seq(
      "co.fs2"                    %% "fs2-core"         % fs2Version,
      "com.google.code.findbugs"   % "jsr305"           % "3.0.2",
      "com.twitter"               %% "util-core"        % "21.9.0",
      "com.typesafe.akka"         %% "akka-stream"      % "2.6.19",
      "io.github.timwspence"      %% "cats-stm"         % "0.10.3",
      "io.projectreactor"          % "reactor-core"     % "3.4.11",
      "io.reactivex.rxjava2"       % "rxjava"           % "2.2.21",
      "org.jctools"                % "jctools-core"     % "3.3.0",
      "org.ow2.asm"                % "asm"              % "9.2",
      "org.scala-lang"             % "scala-compiler"   % scalaVersion.value % Provided,
      "org.scala-lang"             % "scala-reflect"    % scalaVersion.value,
      "org.typelevel"             %% "cats-effect"      % catsEffectVersion,
      "org.typelevel"             %% "cats-effect-std"  % catsEffectVersion,
      "org.scalacheck"            %% "scalacheck"       % scalaCheckVersion,
      "qa.hedgehog"               %% "hedgehog-core"    % "0.7.0",
      "com.github.japgolly.nyaya" %% "nyaya-gen"        % "0.10.0",
      "dev.zio"                   %% "zio"              % zioVersion
    ),
    Compile / console / scalacOptions := Seq(
      "-language:higherKinds",
      "-language:existentials",
      "-Xsource:2.13",
      "-Yrepl-class-based"
    ))
