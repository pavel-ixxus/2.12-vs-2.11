import sbt._
//import sbt.{VersionNumber, VersionNumberCompatibility}

object CompatibleJavaVersion extends VersionNumberCompatibility {
    def name = "jdk compatibility"
    def isCompatible(current: VersionNumber, required: VersionNumber) = current.numbers.zip(required.numbers).forall(n => n._1 == n._2)
    def apply(current: VersionNumber, required: VersionNumber) = isCompatible(current, required)
}

object ShellPrompt {
  val buildShellPrompt =
    (state: State) => "%s> ".format(Project.extract(state).currentProject.id)
}

object devnull extends ProcessLogger {
  def info(s: => String) {}

  def error(s: => String) {}

  def buffer[T](f: => T): T = f
}

object Git {
  // TODO: git short sha only
  def gitRev =
  try {
    val gsi = ("git svn info" lines_! devnull) filter {
      _.startsWith("Revision:")
    }
    var rev = "\\d+".r.findFirstIn(gsi.headOption.getOrElse("0")).get
    if (rev == "0") {
      val si = ("svn info" lines_! devnull) filter {
        _.startsWith("Revision:")
      }
      rev = "\\d+".r.findFirstIn(si.headOption.getOrElse("0")).get
    }
    val prefix = if (rev == "0") "-g" else "-r"
    if (rev == "0") {
      val si = ("git rev-parse --short=0 HEAD" lines_! devnull)
      rev = "[\\dA-Fa-f]+".r.findFirstIn(si.headOption.getOrElse("0")).get
    }
    if (rev == "0") "" else prefix + rev
  } catch {
    case t: Throwable => ""
  }
}
