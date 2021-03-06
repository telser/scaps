/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package scaps.searchEngine.index

import org.scalatest.FlatSpec
import scaps.api.Contravariant
import scaps.api.Covariant
import scaps.api.Invariant
import scaps.api.ValueDef
import scaps.api.TypeRef
import scaps.api.ViewDef
import scaps.api.Module
import scaps.searchEngine.queries.QueryAnalyzer

class TypeFrequenciesSpecs extends FlatSpec with IndexUtils {
  it should "calculate type frequencies at covariant positions" in {
    val (tfs, maxFreq) = typeFrequenciesWithMaxAbsoluteFrequency("""
      package p

      trait A
      class B extends A
      class B2 extends A
      class C extends B

      object O {
        def m1: A = ???
        def m2: B = ???
        def m3: C = ???
      }
      """)

    val tfAny = tfs((Covariant, TypeRef.Any.name))
    val tfA = tfs((Covariant, "p.A"))
    val tfB = tfs((Covariant, "p.B"))
    val tfB2 = tfs((Covariant, "p.B2"))
    val tfC = tfs((Covariant, "p.C"))
    val tfNothing = tfs((Covariant, TypeRef.Nothing.name))

    tfAny should be(0f)
    tfA should be(1f / maxFreq) // m1
    tfB should be(3f / maxFreq) // m1, m2, new B
    tfB2 should be(2f / maxFreq) // m1, new B2
    tfC should be(5f / maxFreq) // m1, m2, m3, new C, new B
    tfNothing should be(1f)
  }

  it should "calculate type frequencies at contravariant positions" in {
    val (tfs, maxFreq) = typeFrequenciesWithMaxAbsoluteFrequency("""
      package p

      trait A
      class B extends A
      class B2 extends A
      class C extends B

      object O {
       /** m1 */
       def m1(x: A) = ???
       def m2(x: B) = ???
       def m3(x: C) = ???
      }
      """)

    val tfAny = tfs((Contravariant, TypeRef.Any.name))
    val tfA = tfs((Contravariant, "p.A"))
    val tfB = tfs((Contravariant, "p.B"))
    val tfB2 = tfs((Contravariant, "p.B2"))
    val tfC = tfs((Contravariant, "p.C"))
    val tfNothing = tfs((Contravariant, TypeRef.Nothing.name))

    tfAny should be >= tfA
    tfA should (
      be >= (3f / maxFreq) and
      be > tfB)
    tfB should (
      be >= (2f / maxFreq) and
      be > tfC)
    tfC should (
      be >= (1f / maxFreq))
    tfNothing should be(0f)
  }

  it should "calculate type frequencies at invariant positions" in {
    val (tfs, maxFreq) = typeFrequenciesWithMaxAbsoluteFrequency("""
      package p

      trait A
      class B extends A
      class C extends B

      object O {
       def m1(x: Array[B]) = ???
      }
      """)

    tfs((Invariant, "p.A")) should be >= (tfs((Covariant, "p.A")))
    tfs((Invariant, "p.A")) should be >= (tfs((Contravariant, "p.A")))

    tfs((Invariant, "p.B")) should be > (tfs((Covariant, "p.B")))
    tfs((Invariant, "p.B")) should be > (tfs((Contravariant, "p.B")))

    tfs((Invariant, "p.C")) should be >= (tfs((Covariant, "p.C")))
    tfs((Invariant, "p.C")) should be >= (tfs((Contravariant, "p.C")))
  }

  it should "calculate type frequencies of generic types" in {
    val (tfs, maxFreq) = typeFrequenciesWithMaxAbsoluteFrequency("""
      package p

      class A[+T]
      class B extends A[Int]
      class C[+T] extends A[T]

      object O {
        def m1: A[Char] = ???
        def m2: A[Int] = ???
        def m3: B = ???
        def m4: B = ???
        def m5: C[Float] = ???
      }
      """)

    val tfA = tfs((Covariant, "p.A"))
    val tfB = tfs((Covariant, "p.B"))

    tfA should be(3f / maxFreq) // new A, m1, m2
    tfB should be(4f / maxFreq) // new A, new B, m3, m4
  }

  def typeFrequenciesWithMaxAbsoluteFrequency(source: String) = {
    val entities = extractAll(source)
    val values = entities.collect { case t: ValueDef => t }
    val views = entities.collect { case v: ViewDef => v }.sortBy(_.from.name)

    withViewIndex { viewIndex =>
      viewIndex.addEntities(views)

      (TypeFrequencies((t: TypeRef) => viewIndex.findAlternatives(t, 3, Set()).getOrElse(Nil), values, Int.MaxValue),
        values.length)
    }
  }
}
