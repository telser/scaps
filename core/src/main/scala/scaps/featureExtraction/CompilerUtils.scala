package scaps.featureExtraction

import scala.tools.nsc.doc.ScaladocGlobalTrait
import scala.tools.nsc.interactive.Global
import scala.tools.nsc.reporters.ConsoleReporter
import scaps.utils.Logging

/**
 * Provides an instance of the Scala presentation compiler
 */
object CompilerUtils extends Logging {
  def withCompiler[A](classpath: Seq[String] = Nil)(fn: Global => A): A = {
    val compiler = createCompiler(classpath)

    compiler.ask(() => new compiler.Run)

    val res = fn(compiler)

    compiler.askShutdown()

    res
  }

  def createCompiler(classpath: Seq[String]): Global = {
    val settings = new scala.tools.nsc.Settings(msg => throw sys.error(msg))

    val scalaLibClassPath =
      if (classpath == Nil) scalaLibRef
      else None

    (scalaLibClassPath.toList ++ classpath).foreach { cp =>
      settings.classpath.append(cp)
      settings.bootclasspath.append(cp)
    }

    {
      import settings.{ languageFeatures => lf }
      settings.language.add(lf.postfixOps.name)
      settings.language.add(lf.implicitConversions.name)
      settings.language.add(lf.existentials.name)
      settings.language.add(lf.higherKinds.name)
    }

    logger.trace(s"Setup presentation compiler with settings ${settings.toConciseString}")

    val reporter = new ConsoleReporter(settings)

    new scala.tools.nsc.interactive.Global(settings, reporter) with ScaladocGlobalTrait /* tells compiler to keep doc comment internally */
  }

  def scalaLibRef =
    // in order to run the tests from sbt, we must add the scala library to the class path
    // but the protection domain might return null when run from eclipse
    Option(Class.forName("scala.Unit").getProtectionDomain.getCodeSource)
      .map(_.getLocation.toExternalForm())
}
