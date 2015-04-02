package scala.tools.apiSearch.featureExtraction

import scala.tools.nsc.doc.ScaladocGlobalTrait
import scala.tools.nsc.reporters.ConsoleReporter

/**
 * Provides an instance of the Scala presentation compiler
 */
object CompilerUtils {
  def initCompiler(classpath: List[String] = Nil) = {
    val settings = new scala.tools.nsc.Settings(msg => throw sys.error(msg))

    val scalaLibClassPath =
      if (classpath == Nil) scalaLibRef
      else None

    (scalaLibClassPath.toList ::: classpath).foreach { cp =>
      settings.classpath.append(cp)
      settings.bootclasspath.append(cp)
    }

    val reporter = new ConsoleReporter(settings)

    val compiler = new scala.tools.nsc.interactive.Global(settings, reporter) with ScaladocGlobalTrait /* tells compiler to keep doc comment internally */

    compiler.ask(() => new compiler.Run)

    compiler
  }

  def scalaLibRef =
    // in order to run the tests from sbt, we must add the scala library to the class path
    // but the protection domain might return null when run from eclipse
    Option(Class.forName("scala.Unit").getProtectionDomain.getCodeSource)
      .map(_.getLocation.toExternalForm())
}