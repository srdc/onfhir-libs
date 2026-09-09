package io.onfhir.path

import org.json4s.JsonAST.{JDecimal, JLong, JObject, JNull}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

/**
 * Exactness contract for numeric values crossing into `FhirPathNumber`.
 *
 * `FhirPathNumber` is `BigDecimal` backed, but every value used to reach it
 * through a `Double`: the JSON input boundary narrowed `JLong` and `JDecimal`,
 * and the literal parser narrowed the `NUMBER` token. Above 2^53 a `Double`
 * cannot separate adjacent integers, so distinct source keys silently became
 * one value - and, where the expression fed an id, one resource id for many
 * records. Nothing in the evaluator reported an error, because as far as it
 * could tell the values really were equal.
 *
 * These are not rounding niceties: an identifier is only useful if it is
 * injective over its source keys, so the boundaries must be exact for the
 * whole `integer64` range rather than for the range a `Double` happens to
 * cover.
 */
@RunWith(classOf[JUnitRunner])
class FhirPathNumericPrecisionTest extends Specification {

  private val evaluator = FhirPathEvaluator()

  /** Eight consecutive keys above 2^53. A Double holds none of them apart. */
  private val adjacentKeys: Seq[Long] = 9012345678901234561L to 9012345678901234568L

  private def input(v: Long): JObject = JObject("key" -> JLong(v))

  private def renderedKey(v: Long): String = evaluator.evaluateString("key.toString()", input(v)).head

  "A JLong crossing the input boundary" should {

    "render its exact decimal representation" in {
      renderedKey(9012345678901234561L) mustEqual "9012345678901234561"
    }

    "keep adjacent keys distinct" in {
      adjacentKeys.map(renderedKey).distinct must haveSize(adjacentKeys.size)
    }

    "survive the full integer64 range" in {
      renderedKey(Long.MaxValue) mustEqual Long.MaxValue.toString
      renderedKey(Long.MinValue) mustEqual Long.MinValue.toString
    }

    "round-trip unchanged into the result JSON" in {
      evaluator.evaluateAndReturnJson("key", input(9012345678901234561L)) must beSome(
        JLong(9012345678901234561L): org.json4s.JValue
      )
    }
  }

  "The 2^53 boundary" should {

    "hold on both sides of the Double mantissa limit" in {
      val twoPow53 = 9007199254740992L
      Seq(twoPow53 - 1, twoPow53, twoPow53 + 1, twoPow53 + 3)
        .map(v => renderedKey(v)) mustEqual Seq(
        "9007199254740991",
        "9007199254740992",
        "9007199254740993",
        "9007199254740995"
      )
    }
  }

  "A number literal" should {

    "parse exactly above 2^53" in {
      evaluator.evaluateString("9012345678901234561.toString()", JNull).head mustEqual "9012345678901234561"
    }

    "not compare equal to a neighbouring key" in {
      evaluator.satisfies("key = 9012345678901234561", input(9012345678901234561L)) must beTrue
      evaluator.satisfies("key = 9012345678901234562", input(9012345678901234561L)) must beFalse
    }

    "keep more decimal digits than a Double can carry" in {
      evaluator.evaluateString("1.00000000000000000001.toString()", JNull).head mustEqual "1.00000000000000000001"
    }
  }

  "A JDecimal crossing the input boundary" should {

    "keep the precision json4s parsed" in {
      val value = BigDecimal("1.00000000000000000001")
      evaluator
        .evaluateString("key.toString()", JObject("key" -> JDecimal(value)))
        .head mustEqual "1.00000000000000000001"
    }
  }

  "Arithmetic on exact values" should {

    "stay exact rather than falling back to Double spacing" in {
      evaluator
        .evaluateString("(key + 1).toString()", input(9012345678901234561L))
        .head mustEqual "9012345678901234562"
    }
  }
}
