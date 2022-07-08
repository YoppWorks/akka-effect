import sbt.Keys._
import sbt._
import sbtbuildinfo.BuildInfoKeys._
import sbtbuildinfo._

object BuildSettings {
  private val scalaVersions = List("2.12.15", "2.13.8", "3.1.2")
  private val versions: Map[String, String] = {
    scalaVersions.map { v =>
      val vs = v.split('.'); val init = vs.take(vs(0) match { case "2" => 2; case _ => 1 }); (init.mkString("."), v)
    }.toMap
  }

  private val stdOptions = Seq(
    "-deprecation",
    "-encoding",
    "UTF-8",
    "-feature",
    "-unchecked",
    "-Xfatal-warnings"
  )

  private def optimizerOptions(optimize: Boolean) =
    if (optimize)
      Seq(
        "-optimise",
        "-opt:l:inline",
        "-opt-inline-from:aio.**",
        "-opt-warnings:at-inline-failed"
      )
    else Nil

  private val std2xOptions = Seq(
    "-language:higherKinds",
    "-language:existentials",
    "-explaintypes",
    "-Yrangepos",
    "-Xlint:-missing-interpolator,-type-parameter-shadow,-infer-any",
    "-Ywarn-numeric-widen",
    "-Ywarn-value-discard"
  )

  val Scala212: String = versions("2.12")
  val Scala213: String = versions("2.13")
  val Scala3: String   = versions("3")

  val SilencerVersion = "1.7.8"

  def buildInfoSettings(packageName: String) =
    Seq(
      buildInfoKeys    := Seq[BuildInfoKey](organization, moduleName, name, version, scalaVersion, sbtVersion, isSnapshot),
      buildInfoPackage := packageName
    )

  def extraOptions(scalaVersion: String, optimize: Boolean) =
    CrossVersion.partialVersion(scalaVersion) match {
      case Some((3, _)) =>
        Seq(
          "-language:implicitConversions",
          "-Xignore-scala2-macros",
          "-noindent"
        )
      case Some((2, 13)) =>
        Seq(
          "-Ywarn-unused:params,-implicits"
        ) ++ std2xOptions ++ optimizerOptions(optimize)
      case Some((2, 12)) =>
        Seq(
          "-Ywarn-extra-implicit",
          "-Ywarn-unused:_,imports",
          "-Ywarn-unused:imports",
          "-Ypartial-unification",
          "-Yno-adapted-args",
          "-Ywarn-inaccessible",
          "-Ywarn-nullary-override",
          "-Ywarn-nullary-unit",
          "-Ywarn-unused:params,-implicits",
          "-Xfuture",
          "-Xsource:2.13",
          "-Xmax-classfile-name",
          "242"
        ) ++ std2xOptions ++ optimizerOptions(optimize)
      case _ => Seq.empty
    }

  def stdSettings(prjName: String) = Seq(
    name                     := s"$prjName",
    crossScalaVersions       := Seq(Scala212, Scala213, Scala3),
    ThisBuild / scalaVersion := Scala213,
    scalacOptions ++= stdOptions ++ extraOptions(scalaVersion.value, optimize = !isSnapshot.value),
    scalacOptions --= {
      if (scalaVersion.value == Scala3)
        List("-Xfatal-warnings")
      else
        List()
    },
    libraryDependencies ++= {
      if (scalaVersion.value == Scala3)
        Seq(
          "com.github.ghik" % s"silencer-lib_$Scala213" % SilencerVersion % Provided
        )
      else
        Seq(
          "com.github.ghik" % "silencer-lib" % SilencerVersion % Provided cross CrossVersion.full,
          compilerPlugin("com.github.ghik" % "silencer-plugin" % SilencerVersion cross CrossVersion.full)
        )
    },
    Test / parallelExecution := { scalaVersion.value != Scala3 },
    incOptions ~= (_.withLogRecompileOnMacro(false)),
    Compile / fork := true,
    Test / fork    := false,
    // For compatibility with Java 9+ module system;
    // without Automatic-Module-Name, the module name is derived from the jar file which is invalid because of the scalaVersion suffix.
    Compile / packageBin / packageOptions +=
      Package.ManifestAttributes(
        "Automatic-Module-Name" -> s"${organization.value}.$prjName".replaceAll("-", ".")
      ),
    // Add compiler plugins for all versions
    addCompilerPlugin(CompilerPlugins.betterForComp),
    addCompilerPlugin(CompilerPlugins.kindProjector)
  )

  implicit class EnrichedModule(p: Project) {
    def module: Project = p.in(file(p.id)).settings(stdSettings(p.id))
  }

}
