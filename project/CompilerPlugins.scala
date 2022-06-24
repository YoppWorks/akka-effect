import sbt._

/**
  * [[CompilerPlugins]] shared/used by all Scala modules.
  */
object CompilerPlugins {

  // Compiler Plugin Versions
  object V {    
    val betterForComp = "0.3.1"
    val kindProjector = "0.13.2"
  }

  // Compiler Plugins
  val betterForComp   = "com.olegpy"    %% "better-monadic-for" % V.betterForComp
  val kindProjector   = "org.typelevel" %% "kind-projector"     % V.kindProjector   cross CrossVersion.full
}
