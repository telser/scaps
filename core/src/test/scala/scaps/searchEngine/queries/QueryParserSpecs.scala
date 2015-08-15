package scaps.searchEngine.queries

import scala.language.postfixOps
import org.scalatest.FlatSpec
import org.scalatest.Matchers

class QueryParserSpecs extends FlatSpec with Matchers {

  import RawQuery._

  "the query parser" should "parse simple types" in {
    parse("Int").tpe should equal(Type("Int"))
  }

  it should "parse types with arguments" in {
    parse("List[Int]").tpe should equal(Type("List", List(Type("Int"))))
    parse("Map[Int, String]").tpe should equal(Type("Map", List(Type("Int"), Type("String"))))
    parse("List[Option[String]]").tpe should equal(Type("List", List(Type("Option", List(Type("String"))))))
  }

  it should "fail on empty argument lists" in {
    failParse("List[]")
    ()
  }

  it should "parse namespaces" in {
    parse("p.q.C").tpe should equal(Type("p.q.C"))
    parse("p.q.C[argument.Name]").tpe should equal(Type("p.q.C", List(Type("argument.Name"))))
  }

  val A = Type("A")
  val B = Type("B")
  val C = Type("C")

  it should "parse function types" in {
    parse("A => B").tpe should equal(function(A :: Nil, B))
  }

  it should "parse function types with multiple args" in {
    parse("(A, B) => C").tpe should equal(function(A :: B :: Nil, C))
  }

  it should "follow precedence rules in nested function types" in {
    parse("A => B => C").tpe should equal(function(A :: Nil, function(B :: Nil, C)))
  }

  it should "parse tuple types" in {
    parse("(A, B)").tpe should equal(tuple(A, B))
  }

  it should "allow tuples in function args" in {
    parse("((A, B)) => C").tpe should equal(function(tuple(A, B) :: Nil, C))
  }

  it should "allow tuples in return types" in {
    parse("A => (B, C)").tpe should equal(function(A :: Nil, tuple(B, C)))
  }

  it should "not parse Tuple1 literals" in {
    parse("(A)").tpe should equal(A)
  }

  it should "fail on empty tuples" in {
    failParse("()")
    failParse("A => ()")
    failParse("(())")
    ()
  }

  it should "fail on missing parens" in {
    val tpes = List(
      "(A, B",
      "A, B)",
      "(A, B => C",
      "A, B) => C",
      "A, B => C")

    tpes.flatMap(t => List(t, s"k1 k2: $t")).foreach { t =>
      failParse(t)
    }
  }

  it should "fail on missing brackets" in {
    val tpes = List(
      "List[A",
      "Map[A, List[C]")

    tpes.flatMap(t => List(t, s"k1 k2: $t")).foreach { t =>
      failParse(t)
    }
  }

  it should "allow empty argument lists" in {
    parse("() => A").tpe should equal(function(Nil, A))
  }

  it should "parse single keywords preceding the type" in {
    parse("keyword: Int").keywords should be("keyword")
  }

  it should "parse multiple keywords" in {
    parse("k1 k2 k3: Int").keywords should be("k1 k2 k3")
  }

  it should "parse multiple keywords in quotes" in {
    parse("\"k1 k2 k3\": Int").keywords should be("k1 k2 k3")
  }

  it should "allow colons in types" in {
    parse("::[A, B]").tpe should equal(Type("::", A :: B :: Nil))
  }

  it should "parse multiple keywords without type" in {
    parse("k1 k2 k3").keywords should be("k1 k2 k3")
  }

  def parse(query: String) = {
    val res = QueryParser(query)
    res should be('right)
    res.getOrElse(???)
  }

  def failParse(query: String) = {
    val res = QueryParser(query)
    res should be('left)
    res.swap.getOrElse(???)
  }
}
