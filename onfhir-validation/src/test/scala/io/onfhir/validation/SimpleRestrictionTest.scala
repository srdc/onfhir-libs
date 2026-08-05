package io.onfhir.validation

import org.json4s.JsonAST.{JArray, JInt, JObject, JString}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class SimpleRestrictionTest extends Specification {
  private val unusedValidator = null

  "simple validation restrictions" should {
    "enforce array and cardinality rules" in {
      ArrayRestriction().evaluate(JArray(List(JString("one"))), unusedValidator) must beEmpty
      ArrayRestriction().evaluate(JString("one"), unusedValidator) must not(beEmpty)
      ArrayRestriction(isArray = false).evaluate(JString("one"), unusedValidator) must beEmpty
      ArrayRestriction(isArray = false).evaluate(JArray(List(JString("one"))), unusedValidator) must not(beEmpty)

      CardinalityMinRestriction(2).evaluate(JArray(List(JString("one"), JString("two"))), unusedValidator) must beEmpty
      CardinalityMinRestriction(2).evaluate(JArray(List(JString("one"))), unusedValidator) must not(beEmpty)
      CardinalityMaxRestriction(1).evaluate(JArray(List(JString("one"))), unusedValidator) must beEmpty
      CardinalityMaxRestriction(1).evaluate(JArray(List(JString("one"), JString("two"))), unusedValidator) must not(beEmpty)
    }

    "enforce max length, fixed values, and partial patterns" in {
      MaxLengthRestriction(3).evaluate(JString("abc"), unusedValidator) must beEmpty
      MaxLengthRestriction(3).evaluate(JString("abcd"), unusedValidator) must not(beEmpty)

      FixedOrPatternRestriction(JString("fixed"), isFixed = true).evaluate(JString("fixed"), unusedValidator) must beEmpty
      FixedOrPatternRestriction(JString("fixed"), isFixed = true).evaluate(JString("other"), unusedValidator) must not(beEmpty)

      val pattern = JObject("code" -> JString("ok"), "nested" -> JObject("value" -> JInt(1)))
      FixedOrPatternRestriction(pattern, isFixed = false)
        .evaluate(JObject("code" -> JString("ok"), "nested" -> JObject("value" -> JInt(1), "extra" -> JString("allowed"))), unusedValidator) must beEmpty
      FixedOrPatternRestriction(pattern, isFixed = false)
        .evaluate(JObject("code" -> JString("wrong"), "nested" -> JObject("value" -> JInt(1))), unusedValidator) must not(beEmpty)
    }

    "enforce primitive minimum and maximum values" in {
      MinMaxValueRestriction(JString("10"), isMin = true).evaluate(JString("11"), unusedValidator) must beEmpty
      MinMaxValueRestriction(JString("10"), isMin = true).evaluate(JString("9"), unusedValidator) must not(beEmpty)
      MinMaxValueRestriction(JString("10"), isMin = false).evaluate(JString("9"), unusedValidator) must beEmpty
      MinMaxValueRestriction(JString("10"), isMin = false).evaluate(JString("11"), unusedValidator) must not(beEmpty)
    }
  }
}
