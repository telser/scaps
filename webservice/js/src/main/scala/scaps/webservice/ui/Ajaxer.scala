package scaps.webservice.ui

import scala.concurrent.ExecutionContext.Implicits.global
import org.scalajs.dom

object Ajaxer extends autowire.Client[String, upickle.Reader, upickle.Writer] {
  override def doCall(req: Request) = {
    dom.ext.Ajax.post(
      url = "/api/" + req.path.mkString("/"),
      data = upickle.write(req.args)).map(_.responseText)
  }

  def read[Result: upickle.Reader](p: String) = upickle.read[Result](p)
  def write[Result: upickle.Writer](r: Result) = upickle.write(r)
}