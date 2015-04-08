package scala.tools.apiSearch.featureExtraction

import scala.collection.mutable.ListBuffer
import scala.reflect.internal.util.SourceFile
import scala.tools.apiSearch.model.Entity
import scala.tools.nsc.interactive.Global
import scala.util.Failure
import scala.util.Success

class ScalaSourceExtractor(val compiler: Global) extends EntityFactory {
  import compiler._

  def apply(sourceFile: SourceFile): Seq[Entity] =
    withTypedTree(sourceFile) { root =>
      compiler.ask { () =>
        val classes = findClasses(root)

        val fragments = for {
          sym <- symsMayContributingComments(root)
        } yield (sym, sourceFile)

        def getDocComment(sym: Symbol, site: Symbol): String = {
          val cr = new Response[(String, String, Position)]
          compiler.askDocComment(sym, sourceFile, site, fragments.toList, cr)
          cr.get.fold({ case (raw, _, _) => raw }, { case _ => "" })
        }

        classes.flatMap { cls =>
          scala.util.Try(extractEntities(cls, getDocComment _)).getOrElse(Nil)
        }
      }
    }.getOrElse(Nil).distinct

  private def findClasses(tree: Tree): List[Symbol] =
    traverse(tree) {
      case impl: ImplDef =>
        (impl.symbol :: Nil, true)
      case _: ValOrDefDef =>
        (Nil, false)
      case _ =>
        (Nil, true)
    }

  private def symsMayContributingComments(tree: Tree): List[Symbol] =
    traverse(tree) {
      case impl: ImplDef =>
        val sym = impl.symbol
        if (sym.isPublic)
          (sym :: sym.tpe.decls.filter(isTermOfInterest).toList, true)
        else
          (Nil, false)
      case v: ValOrDefDef =>
        (Nil, false)
      case _ =>
        (Nil, true)
    }

  private def traverse[T](tree: Tree)(collect: Tree => (List[T], Boolean)): List[T] = {
    val ts = new ListBuffer[T]

    val traverser = new Traverser {
      override def traverse(t: Tree) = {
        val (newTs, descend) = collect(t)
        ts ++= newTs
        if (descend) {
          super.traverse(t)
        }
      }
    }

    traverser(tree)

    ts.toList
  }

  private def withTypedTree[T](sourceFile: SourceFile)(f: Tree => T): util.Try[T] = {
    val r = new Response[Tree]
    compiler.askLoadedTyped(sourceFile, r)

    r.get.fold(Success(_), Failure(_)).map { root =>
      val result = f(root)

      compiler.removeUnitOf(sourceFile)

      result
    }
  }
}
