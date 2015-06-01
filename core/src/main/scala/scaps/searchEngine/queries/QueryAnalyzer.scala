package scaps.searchEngine.queries

import scalaz.{ \/ => \/ }
import scalaz.std.list.listInstance
import scalaz.syntax.traverse.ToTraverseOps
import scaps.searchEngine.ApiQuery
import scaps.searchEngine.ApiTypeQuery
import scaps.searchEngine.NameAmbiguous
import scaps.searchEngine.NameNotFound
import scaps.searchEngine.QueryFingerprint
import scaps.searchEngine.SemanticError
import scaps.searchEngine.UnexpectedNumberOfTypeArgs
import scaps.settings.Settings
import scaps.webapi.ClassEntity
import scaps.webapi.Contravariant
import scaps.webapi.Covariant
import scaps.webapi.Invariant
import scaps.webapi.TypeEntity
import scaps.webapi.Variance
import scaps.searchEngine.View

private[queries] sealed trait ResolvedQuery
private[queries] object ResolvedQuery {
  case object Wildcard extends ResolvedQuery
  case class Type(cls: ClassEntity, args: List[ResolvedQuery]) extends ResolvedQuery
}

sealed trait ExpandedQuery {
  import ExpandedQuery._

  def children: List[ExpandedQuery]
}

private[queries] object ExpandedQuery {
  sealed trait Part extends ExpandedQuery
  sealed trait Alternative extends ExpandedQuery

  case class Sum(parts: List[Part]) extends Alternative {
    val children = parts

    override def toString =
      parts.mkString("sum(", ", ", ")")
  }
  object Sum {
    def apply(parts: Part*): Sum =
      Sum(parts.toList)
  }

  case class Max(alternatives: List[Alternative]) extends Part {
    val children = alternatives

    override def toString =
      alternatives.mkString("max(", ", ", ")")
  }
  object Max {
    def apply(alts: Alternative*): Max =
      Max(alts.toList)
  }

  case class Leaf(tpe: TypeEntity, depth: Int, dist: Int) extends Part with Alternative {
    val children = Nil

    override def toString =
      s"$tpe^($depth, $dist)"
  }

  def minimizeClauses(q: ExpandedQuery): ExpandedQuery = q match {
    case l: Leaf           => l
    case Sum(parts)        => q
    case Max(alternatives) => q
  }
}

/**
 * Analyzes parsed queries.
 *
 * Instances require access to the class index which can be injected via
 * `findClassesBySuffix` and `findSubClasses`
 */
class QueryAnalyzer private[searchEngine] (
  settings: Settings,
  findClassesBySuffix: (String) => Seq[ClassEntity],
  findAlternativesWithDistance: (TypeEntity) => Seq[(TypeEntity, Int)]) {

  /**
   * Transforms a parsed query into a query that can be passed to the terms index.
   *
   * Fails when `findClassesBySuffix` or `findSubClasses` fails.
   */
  def apply(raw: RawQuery): SemanticError \/ ApiQuery =
    resolveNames(raw.tpe).map(
      (toType _) andThen
        (_.normalize(Nil)) andThen
        (expandQuery _) andThen
        (toApiTypeQuery _) andThen
        { typeQuery => ApiQuery(raw.keywords, typeQuery) })

  /**
   * Resolves all type names in the query and assigns the according class entities.
   *
   * Names that cannot be resolved and have length 1 are treated as type parameters.
   */
  private def resolveNames(raw: RawQuery.Type): SemanticError \/ ResolvedQuery = {
    val resolvedArgs: SemanticError \/ List[ResolvedQuery] =
      raw.args.map(arg => resolveNames(arg)).sequenceU

    resolvedArgs.flatMap { resolvedArgs =>
      findClassesBySuffix(raw.name) match {
        case Seq() if isTypeParam(raw.name) =>
          \/.right(ResolvedQuery.Wildcard)
        case Seq() =>
          \/.left(NameNotFound(raw.name))
        case Seq(cls) if resolvedArgs.length == cls.typeParameters.length =>
          \/.right(ResolvedQuery.Type(cls, resolvedArgs))
        case Seq(cls) if raw.args.length == 0 =>
          \/.right(ResolvedQuery.Type(cls, cls.typeParameters.map(_ => ResolvedQuery.Wildcard)))
        case Seq(cls) =>
          \/.left(UnexpectedNumberOfTypeArgs(raw.name, cls.typeParameters.length))
        case candidates =>
          \/.left(NameAmbiguous(raw.name, candidates))
      }
    }
  }

  private def isTypeParam(name: String): Boolean =
    name.length() == 1

  private def toType(resolved: ResolvedQuery): TypeEntity = {
    def rec(resolved: ResolvedQuery, variance: Variance): TypeEntity =
      resolved match {
        case ResolvedQuery.Wildcard =>
          TypeEntity.Unknown(variance)
        case ResolvedQuery.Type(cls, args) =>
          val tpeArgs = cls.typeParameters.zip(args).map {
            case (tpeParam, ResolvedQuery.Wildcard) =>
              (variance * tpeParam.variance) match {
                case Covariant     => TypeEntity(tpeParam.lowerBound, Covariant, Nil)
                case Contravariant => TypeEntity(tpeParam.upperBound, Contravariant, Nil)
                case Invariant     => TypeEntity.Unknown(Invariant)
              }
            case (tpeParam, arg) =>
              rec(arg, variance * tpeParam.variance)
          }
          TypeEntity(cls.name, variance, tpeArgs)
      }

    rec(resolved, Covariant)
  }

  /**
   * Builds the query structure of parts and alternatives for a type.
   */
  def expandQuery(tpe: TypeEntity): ExpandedQuery.Alternative = {
    import ExpandedQuery._

    def parts(tpe: TypeEntity, depth: Int, dist: Int): Alternative = tpe match {
      case TypeEntity.Ignored(args, v) =>
        Sum(args.map(alternatives(_, depth)))
      case tpe =>
        Sum(Leaf(tpe.withArgsAsParams, depth, dist) ::
          tpe.args.filterNot(_.isTypeParam).map(arg => alternatives(arg, depth + 1)))
    }

    def alternatives(tpe: TypeEntity, depth: Int): Part = {
      val originalTypeParts = parts(tpe, depth, 0)
      val alternativesParts =
        findAlternativesWithDistance(tpe).toList.map {
          case (alt, dist) =>
            parts(alt, depth, dist)
        }

      Max(originalTypeParts :: alternativesParts)
    }

    parts(tpe, 0, 0)
  }

  private def toApiTypeQuery(q: ExpandedQuery): ApiTypeQuery = q match {
    case ExpandedQuery.Sum(parts) => ApiTypeQuery.Sum(parts.map(toApiTypeQuery))
    case ExpandedQuery.Max(alts)  => ApiTypeQuery.Max(alts.map(toApiTypeQuery))
    case l: ExpandedQuery.Leaf    => ApiTypeQuery.Type(l.tpe.variance, l.tpe.name, boost(l))
  }

  private def boost(l: ExpandedQuery.Leaf): Double = {
    val maxFrequency = settings.index.typeFrequenciesSampleSize

    val freq = math.min(getFrequency(l.tpe.variance, l.tpe.name), maxFrequency)
    val itf = math.log((maxFrequency.toDouble + 1) / (freq + 1))

    weightedGeometricMean(
      itf -> settings.query.typeFrequencyWeight,
      1d / (l.depth + 1) -> settings.query.depthBoostWeight,
      1d / (l.dist + 1) -> settings.query.distanceBoostWeight)
  }

  /**
   * Implements http://en.wikipedia.org/wiki/Weighted_geometric_mean
   */
  private def weightedGeometricMean(elemsWithWeight: (Double, Double)*) =
    math.exp(
      elemsWithWeight.map { case (x, w) => w * math.log(x) }.sum / elemsWithWeight.map { case (_, w) => w }.sum)

  private def weightedArithmeticMean(elemsWithWeight: (Double, Double)*) =
    elemsWithWeight.map { case (x, w) => x * w }.sum / elemsWithWeight.map { case (_, w) => w }.sum

  private def getFrequency(v: Variance, t: String) =
    findClassesBySuffix(t).headOption.map(_.frequency(v)).filter(_ > 0).getOrElse(1)
}
