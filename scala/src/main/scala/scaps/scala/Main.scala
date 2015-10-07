package scaps.scala

import java.io.File

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random

import autowire._
import scalaz.std.list.listInstance
import scaps.api.ScapsControlApi
import scaps.featureExtraction.CompilerUtils
import scaps.featureExtraction.ExtractionError
import scaps.featureExtraction.JarExtractor
import scaps.utils.Logging

object Main extends App with Logging {
  val settings = ExtractionSettings.fromApplicationConf

  println(settings)

  val extractor = new JarExtractor(
    CompilerUtils.createCompiler(settings.classpath))

  val scaps = new DispatchClient(settings.controlHost, ScapsControlApi.apiPath)[ScapsControlApi]

  val indexName = Random.nextInt().toString()

  val defs = settings.modules.flatMap { m =>
    ExtractionError.logErrors(extractor(new File(m.artifactPath)), logger.info(_))
      .distinct
      .map(_.withModule(m.module))
  }

  defs.grouped(settings.maxDefinitionsPerRequest).foreach { ds =>
    Await.ready(scaps.index(indexName, ds).call(), settings.requestTimeout)
  }

  Await.ready(scaps.finalizeIndex(indexName).call(), settings.requestTimeout)

  System.exit(0)
}