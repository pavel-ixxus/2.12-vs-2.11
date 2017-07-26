import sbt.Keys._
import sbt._

object BuildSettings {

  lazy val suspend = "n"

  lazy val noPublishing = Seq(
    publish :=(),
    publishLocal :=(),
    publishTo := None
  )

  lazy val globalScalacOptions = Seq(
    "-encoding", "UTF-8"
    , "-g:vars"
    , "-feature"
    , "-unchecked"
    , "-deprecation"
    , "-target:jvm-1.8"
    , "-Xlog-reflective-calls"
    //, "-Xlint"
    , if(V.scala.startsWith("2.11")) "" else "-Ywarn-unused:-imports"
    , "-Yno-adapted-args"
    , "-Ywarn-value-discard"
    //"-Xfatal-warnings" // be good!
  )

  val common =
    Defaults.coreDefaultSettings ++
      //assemblySettings ++
      Seq(
        organization := "ixxus",
        version := V.roa,
        scalaVersion := V.scala,
        scalacOptions := globalScalacOptions,
        scalacOptions in (Compile, console) := globalScalacOptions,
        scalacOptions in (Test, console) := globalScalacOptions,
        classpathTypes ~= (_ + "orbit"),
        //resolvers ++= repos,
        shellPrompt := ShellPrompt.buildShellPrompt,
        /*fork in run := true,*/
        fork in Test := true,
        javaOptions in Test ++= Seq("-Xmx4g", "-Xdebug", "-Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=5005"),
        /*fork in (Test, run) := true,
        javaOptions in (Test,run) ++= Seq("-Xmx4g", "-Xdebug", s"-Xrunjdwp:transport=dt_socket,server=y,suspend=${suspend},address=9997"),
        mainClass in (Test, run) := Some("roa"),*/
        parallelExecution := false
      )
}

