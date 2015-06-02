package scaps.searchEngine.index

import org.apache.lucene.queries.CustomScoreQuery
import scaps.searchEngine.ApiTypeQuery
import org.apache.lucene.search.BooleanQuery
import scaps.searchEngine.ApiQuery
import org.apache.lucene.search.TermQuery
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause.Occur
import org.apache.lucene.search.ConstantScoreQuery
import org.apache.lucene.index.AtomicReaderContext
import org.apache.lucene.queries.CustomScoreProvider
import scala.collection.mutable.ListBuffer
import org.apache.lucene.index.TermsEnum
import org.apache.lucene.queries.function.FunctionQuery
import org.apache.lucene.queries.function.valuesource.ConstValueSource
import org.apache.lucene.index.IndexReader
import org.apache.lucene.search.Explanation

class TypeFingerprintQuery(field: String, apiQuery: ApiTypeQuery)
  extends CustomScoreQuery(TypeFingerprintQuery.matcherQuery(field, apiQuery)) {

  val scorerQuery = TypeFingerprintQuery.FingerprintScorer(apiQuery)

  override def name() = "TypeFingerprint"

  override def rewrite(reader: IndexReader) = this

  override def getCustomScoreProvider(context: AtomicReaderContext) =
    new CustomScoreProvider(context) {
      override def customScore(doc: Int, subQueryScore: Float, valSrcScores: Array[Float]): Float =
        customScore(doc, subQueryScore, 0)

      override def customScore(doc: Int, subQueryScore: Float, valSrcScore: Float): Float = {
        val reader = context.reader()

        Option(reader.getTermVector(doc, field)).map { tv =>
          var terms: TermsEnum = null
          terms = tv.iterator(terms)

          val typesBuffer = new ListBuffer[String]
          var current = terms.next()

          while (current != null) {
            typesBuffer += current.utf8ToString()
            current = terms.next()
          }

          scorerQuery.score(typesBuffer)
        }.getOrElse {
          0f
        }
      }

      override def customExplain(doc: Int, subQueryExpl: Explanation, valSrcExpl: Explanation): Explanation = {
        customExplain(doc, subQueryExpl, Array(valSrcExpl))
      }

      override def customExplain(doc: Int, subQueryExpl: Explanation, valSrcExpls: Array[Explanation]): Explanation = {
        new Explanation(customScore(doc, 0, 0), "type fingerprint score")
      }
    }
}

object TypeFingerprintQuery {
  def matcherQuery(field: String, apiQuery: ApiTypeQuery) = {
    val q = new BooleanQuery
    for {
      tpe <- apiQuery.allTypes
      if tpe.boost > 0.01
    } {
      val term = s"${tpe.variance.prefix}${tpe.typeName}"
      q.add(new TermQuery(new Term(field, term)), Occur.SHOULD)
    }
    new ConstantScoreQuery(q)
  }

  object FingerprintScorer {
    def apply(q: ApiTypeQuery): FingerprintScorer = q match {
      case ApiTypeQuery.Sum(children) =>
        SumNode(children.map(apply))
      case ApiTypeQuery.Max(children) =>
        MaxNode(children.map(apply))
      case ApiTypeQuery.Type(v, name, boost) =>
        Leaf(s"${v.prefix}$name", boost.toFloat)
    }
  }

  sealed trait FingerprintScorer {
    def score(fpt: String): Option[(Float, FingerprintScorer)]

    def score(documentFingerprint: Seq[String]): Float = {
      /*
       * This is only a heuristic that generally yields accurate results but
       * may not return the maximum score for a fingerprint (see ignored test cases).
       *
       * Scoring a fingerprint against a query is a harder problem as one would
       * intuitively think. An additional term in the fingerprint may require
       * reassignment of all previously matched terms. Thus, the only approach
       * to yield an optimal result is probably to check all permutations of the
       * fingerprint.
       *
       * The following heuristic first orders the fingerprint by the maximum
       * achievable score of each individual term and uses this order to score
       * the fingerprint as a whole.
       */
      val termsWithMaxScore = documentFingerprint
        .map(t => (t, score(t).map(_._1).getOrElse(0f)))

      val terms = termsWithMaxScore.sortBy(-_._2).map(_._1)

      terms.foldLeft((0f, this)) {
        case ((score, scorer), fpt) =>
          scorer.score(fpt).fold((score, scorer)) {
            case (newScore, newScorer) => (score + newScore, newScorer)
          }
      }._1
    }
  }

  case class SumNode(children: List[FingerprintScorer]) extends FingerprintScorer {
    implicit val o: Ordering[(Int, Float, FingerprintScorer)] = Ordering.Float.on(_._2)

    def score(fpt: String) =
      (for {
        (child, idx) <- children.zipWithIndex
        (s, replacement) <- child.score(fpt)
      } yield (idx, s, replacement)) match {
        case Seq() => None
        case matches =>
          val (idx, score, newChild) = matches.max
          Some((score, SumNode(children.updated(idx, newChild))))
      }
  }

  case class MaxNode(children: List[FingerprintScorer]) extends FingerprintScorer {
    implicit val o: Ordering[(Float, FingerprintScorer)] = Ordering.Float.on(_._1)

    def score(fpt: String) =
      children.flatMap(_.score(fpt)) match {
        case Seq() => None
        case matches =>
          Some(matches.max)
      }
  }

  case class Leaf(fingerprint: String, boost: Float) extends FingerprintScorer {
    def score(fpt: String) =
      if (fingerprint == fpt)
        Some((boost, DeadLeaf))
      else
        None
  }

  object DeadLeaf extends FingerprintScorer {
    def score(fpt: String) = None
  }
}
