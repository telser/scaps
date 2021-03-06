/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package scaps.nucleus.indexing

import org.scalatest.FlatSpec
import org.scalatest.Matchers
import scaps.nucleus.ValueDef
import scala.language.implicitConversions

class FingerprintSpecs extends FlatSpec with Matchers {

  import scaps.nucleus.TestLanguage._

  def fingerprint(v: ValueDef): List[String] = {
    val internal = InternalTypes.toInternal(v, testModel)
    Fingerprint(internal).map(_.toString)
  }

  def assertFingerprint(pairs: (ValueDef, List[String])*) =
    pairs.foreach {
      case (v, expected) =>
        fingerprint(v) should contain theSameElementsAs (expected)
    }

  it should "flatten value types" in {
    assertFingerprint(
      vall(+T.Int) -> List("+Int"),
      vall(+T.List(+T.Int)) -> List("+List", "+Int"))
  }

  it should "flatten method types" in {
    assertFingerprint(
      deff(-T.String)(+T.Int) -> List("-String", "+Int"))
  }
}
