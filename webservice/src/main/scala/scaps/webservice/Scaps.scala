/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package scaps.webservice

import scala.annotation.implicitNotFound
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import akka.actor.ActorRefFactory
import akka.actor.Props
import akka.actor.actorRef2Scala
import akka.pattern.ask
import akka.util.Timeout
import scalaz.{ \/ => \/ }
import scaps.searchEngine.SearchEngine
import scaps.settings.Settings
import scaps.api.IndexStatus
import scaps.api.Module
import scaps.api.ScapsApi
import scaps.api.ScapsControlApi
import scaps.api.ValueDef
import scaps.webservice.actors.Director
import scaps.webservice.actors.UserInteractionLogger
import scaps.api.Definition
import scaps.api.Result

class Scaps(context: ActorRefFactory) extends ScapsApi with ScapsControlApi {
  import scaps.webservice.actors.ActorProtocol._

  val director = context.actorOf(Director.props(Settings.fromApplicationConf)(), "director")
  val userInteractionLogger = context.actorOf(Props[UserInteractionLogger], "userInteractionLogger")

  implicit val _ = context.dispatcher
  implicit val timeout = Timeout(10.seconds)

  override def index(indexName: String, definitions: Seq[Definition]): Unit = {
    director ! Index(indexName, definitions)
  }

  override def finalizeIndex(indexName: String): Unit = {
    director ! FinalizeIndex(indexName)
  }

  override def getStatus(): Future[IndexStatus] =
    (director ? GetStatus).mapTo[IndexStatus]

  override def search(query: String, moduleIds: Set[String], noResults: Int, offset: Int): Future[Either[String, Seq[Result[ValueDef]]]] = {
    val searchMsg = Search(query, moduleIds, noResults, offset)

    val f = for {
      result <- (director ? searchMsg).mapTo[String \/ Seq[Result[ValueDef]]]
    } yield {
      userInteractionLogger ! ((searchMsg, result))
      result.toEither
    }

    f.recoverWith {
      case e =>
        userInteractionLogger ! ((searchMsg, \/.left(e.toString())))
        Future.failed(e)
    }
  }

  override def assessPositivley(query: String, moduleIds: Set[String], valueSignature: String): Unit =
    userInteractionLogger ! PositiveAssessement(query, moduleIds, valueSignature)
}
