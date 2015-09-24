package scaps.featureExtraction

import scala.tools.nsc.doc.ScaladocGlobal
import scala.tools.nsc.reporters.ConsoleReporter

import scaps.utils.Logging

object CompilerUtils extends Logging{
  def createCompiler(classpath: Seq[String]): ScaladocGlobal = {
    val settings = new scala.tools.nsc.doc.Settings(msg => throw sys.error(msg))

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

    new scala.tools.nsc.doc.ScaladocGlobal(settings, reporter)
  }

  def scalaLibRef =
    // in order to run the tests from sbt, we must add the scala library to the class path
    // but the protection domain might return null when run from eclipse
    Option(Class.forName("scala.Unit").getProtectionDomain.getCodeSource)
      .map(_.getLocation.toExternalForm())
}