package scaps.evaluation

import java.io.File

import scala.sys.process.urlToProcess

import scalaz.{ \/ => \/ }
import scalaz.std.list.listInstance
import scalaz.std.stream.streamInstance
import scalaz.syntax.traverse.ToTraverseOps
import scaps.api.Module
import scaps.evaluation.stats.QueryStats
import scaps.evaluation.stats.Stats
import scaps.featureExtraction.CompilerUtils
import scaps.featureExtraction.ExtractionError
import scaps.featureExtraction.JarExtractor
import scaps.searchEngine.QueryError
import scaps.searchEngine.SearchEngine
import scaps.settings.Settings
import scaps.utils.Logging
import scaps.utils.timers

object Common extends Logging {
  def runQueries(engine: SearchEngine, queriesWithRelevantDocs: List[(String, Set[String])]): QueryError \/ Stats = {
    queriesWithRelevantDocs.map {
      case (query, relevantResults) =>
        val (res, dur) = timers.withTime(engine.search(query).get)
        res.map(results => QueryStats(query, results.map(_.signature), relevantResults, dur))
    }.sequenceU.map(Stats(_))
  }

  def initSearchEngine(settings: Settings, evaluationSettings: EvaluationSettings) = {
    val engine = SearchEngine(settings).get
    if (evaluationSettings.rebuildIndex) {
      evaluationSettings.downloadDir.mkdirs()

      val classPaths = for {
        project <- evaluationSettings.projects
        dependency <- project.dependencies
      } yield {
        val file = new File(evaluationSettings.downloadDir, dependency.name)

        if (!file.exists()) {
          import sys.process._
          (dependency.url #> file).!!
        }

        file.getAbsolutePath()
      }

      val compiler = CompilerUtils.createCompiler(classPaths)
      val extractor = new JarExtractor(compiler)

      engine.resetIndexes().get

      val modules = evaluationSettings.projects.map { project =>
        val jar = new File(evaluationSettings.downloadDir, project.name)

        if (!jar.exists()) {
          import sys.process._
          (project.url #> jar).!!
        }

        (Module("", project.name, ""), () => ExtractionError.logErrors(extractor(jar), logger.info(_)))
      }

      engine.indexEntities(modules).get
    }
    engine
  }

  def updateSearchEngine(engine: SearchEngine, newSettings: Settings) = {
    if (engine.settings.index != newSettings.index) {
      println("Index time settings have changed!")

      val entities = engine.valueIndex.allEntities().get ++ engine.typeIndex.allEntities().get
      val newEngine = {
        val e = SearchEngine(newSettings).get
        e.resetIndexes().get
        e
      }
      newEngine.indexEntities(Module.Unknown, entities.toStream).get

      newEngine
    } else {
      SearchEngine(newSettings).get
    }
  }
}
