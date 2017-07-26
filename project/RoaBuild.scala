import sbt.Keys._
import sbt._
import scalikejdbc.mapper.SbtPlugin.scalikejdbcSettings

object RoaBuild extends Build {

  import Deps._

  val jdkVersionRequired = "1.8"

  lazy val roaRoot = Project(id = "roaRoot", base = file("."))
    .aggregate(roa)
    .settings(scalacOptions := BuildSettings.globalScalacOptions)
    .settings(scalaVersion := V.scala)
    .settings(BuildSettings.noPublishing: _*)
    .settings(initialize := {
      val _ = initialize.value
      val required = VersionNumber(jdkVersionRequired)
      val curr = VersionNumber(sys.props("java.specification.version"))
      assert(CompatibleJavaVersion(curr, required), s"jdk required: $required; current: $curr")
    })

  val roaName = "roa"
  lazy val roa = Project(id = roaName, base = file(roaName))
    .settings(BuildSettings.common: _*)
    .settings(scalikejdbcSettings)
    .settings(excludeDependencies ++= Seq(
      "commons-logging" % "commons-logging",
      "org.slf4j" % "slf4j-log4j12"))
    .settings(
      name := roaName,
      libraryDependencies ++=
        compile(
          logback ++
            scalaLogging ++
            slf4j ++
            jodaConvert ++
            jodaTime ++
            solrj ++ solr ++ zookeeper))
  /* https://github.com/scala/bug/issues/10171
     https://github.com/wix/accord/issues/103
    Seq("org.scala-lang" % "scala-compiler" % V.scala % "compile") ++
  */
}

object V {
  // pick a side :)
  //val scala =  "2.12.2"
  val scala = "2.11.11"

  val jodaConvert = "1.8.2"
  val jodaTime = "2.9.9"
  val logback = "1.2.3"
  val roa = "1.0.0"
  val scalaLogging = "3.7.1"
  val slf4j = "1.7.25"
  val solr = "6.6.0"
  val zookeeper = "3.4.10"
}

object Deps {
  val jodaConvert = "org.joda" % "joda-convert" % V.jodaConvert
  val jodaTime = "joda-time" % "joda-time" % V.jodaTime
  val slf4j = "org.slf4j" % "slf4j-api" % V.slf4j
  val scalaLogging = "com.typesafe.scala-logging" %% "scala-logging" % V.scalaLogging

  val solrj = "org.apache.solr" % "solr-solrj" % V.solr
  val solr = "org.apache.solr" % "solr-core" % V.solr

  val zookeeper = "org.apache.zookeeper" % "zookeeper" % V.zookeeper

  val logback = Seq(
    "ch.qos.logback" % "logback-classic" % V.logback,
    "ch.qos.logback" % "logback-core" % V.logback)

  def compile(deps: Seq[ModuleID]): Seq[ModuleID] = deps map (_ % "compile")

  def test(deps: Seq[ModuleID]): Seq[ModuleID] = deps map (_ % "test")

  def provided(deps: Seq[ModuleID]): Seq[ModuleID] = deps map (_ % "provided")

  def runtime(deps: Seq[ModuleID]): Seq[ModuleID] = deps map (_ % "runtime")

  def container(deps: Seq[ModuleID]): Seq[ModuleID] = deps map (_ % "container")

  implicit def mod2seq(m: ModuleID): Seq[ModuleID] = Seq(m)
}
