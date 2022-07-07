name := "akka-effect"

version := "0.1.0"

scalaVersion := "2.13.8"

// scalac options
scalacOptions ++= Seq(
  "-feature",
  "-optimise",
  "-opt:l:inline",
  "-opt-inline-from:aio.**",
  "-opt-warnings:at-inline-failed"
)

lazy val akkaVersion = "2.6.19"
lazy val specs2Version = "4.16.0"

// Run in a separate JVM, to make sure sbt waits until all threads have
// finished before returning.
// If you want to keep the application running while executing other
// sbt tasks, consider https://github.com/spray/sbt-revolver/
fork := true

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion % Test,
  "org.specs2" %% "specs2-core" % specs2Version % Test,
  "org.specs2" %% "specs2-scalacheck" % specs2Version % Test
)

// Better monadic for-comprehensions
addCompilerPlugin(CompilerPlugins.betterForComp)

// Kind projector for type-lambdas
addCompilerPlugin(CompilerPlugins.kindProjector)