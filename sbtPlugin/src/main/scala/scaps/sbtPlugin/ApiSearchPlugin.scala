package scaps.sbtPlugin

import sbt.{ url => sbtUrl, _ }
import sbt.Keys._
import dispatch._
import dispatch.Defaults._
import scala.concurrent.Await
import scala.concurrent.duration._

object ApiSearchPlugin extends AutoPlugin {
  override def trigger = allRequirements

  object autoImport {
    lazy val apiSearchHost = SettingKey[String]("apiSearchHost", "Hostname of the Scala API Search service.")
    lazy val apiSearchIndex = TaskKey[Unit]("apiSearchIndex", "Requests indexing this project.")
  }

  import autoImport._

  override lazy val projectSettings = Seq(
    apiSearchHost := "localhost:8080",
    apiSearchIndex := {
      val logger = streams.value.log

      val deps = libraryDependencies.value.map(_.name)

      val modules = updateClassifiers.value.configuration(Compile.name).get.modules

      val sourceFiles = modules.flatMap(_.artifacts).filter {
        case (Artifact(name, _, _, Some(Artifact.SourceClassifier), _, _, _), file) if deps.contains(name) =>
          true
        case _ =>
          false
      }.map { case (a, f) => f.getAbsolutePath() }.distinct

      val classpath = (fullClasspath in Compile).value.map {
        case Attributed(f) => f.getAbsolutePath
      }

      val service = host(apiSearchHost.value)

      sourceFiles.foreach { sourceFile =>
        val body = s"""
            {
              "sourceFile": "${sourceFile}",
              "classpath": ${classpath.map(s => "\"" + s + "\"").mkString("[", ",", "]")}
            }
            """
        val req = (service / "index").POST
          .setContentType("application/json", "UTF-8")
          .<<(body)

        logger.info(s"Requested index for $body")

        val resp = Http(req).map { r =>
          logger.info(s"Response: ${r.getStatusCode} - ${r.getResponseBody}")
        }

        resp.onFailure {
          case f: Throwable => logger.info(s"Request failed with $f")
        }

        Await.ready(resp, 5.seconds)
      }
    })
}