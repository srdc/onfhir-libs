package io.onfhir.path

import org.json4s.JsonAST.{JNull, JObject, JString}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import java.time.{LocalTime, ZoneOffset}

@RunWith(classOf[JUnitRunner])
class FhirPathN1ConversionFunctionsTest extends Specification {

  sequential

  private val evaluator = FhirPathEvaluator()

  private def booleanResult(expression: String): Boolean =
    evaluator.evaluateBoolean(expression, JNull).head

  "FHIRPath N1 conversion predicates" should {

    "implement convertsToBoolean" in {
      booleanResult("true.convertsToBoolean()") must beTrue
      booleanResult("1.convertsToBoolean()") must beTrue
      booleanResult("'yes'.convertsToBoolean()") must beTrue
      booleanResult("2.convertsToBoolean()") must beFalse
      booleanResult("'unknown'.convertsToBoolean()") must beFalse
    }

    "implement convertsToInteger" in {
      booleanResult("1.convertsToInteger()") must beTrue
      booleanResult("true.convertsToInteger()") must beTrue
      booleanResult("'-12'.convertsToInteger()") must beTrue
      booleanResult("1.2.convertsToInteger()") must beFalse
      booleanResult("'1.2'.convertsToInteger()") must beFalse
    }

    "implement convertsToDate" in {
      booleanResult("@2012-01-01.convertsToDate()") must beTrue
      booleanResult("@2012-01-01T10:00.convertsToDate()") must beTrue
      booleanResult("'2012-01'.convertsToDate()") must beTrue
      booleanResult("'not-a-date'.convertsToDate()") must beFalse
      booleanResult("true.convertsToDate()") must beFalse
    }

    "implement convertsToDateTime" in {
      booleanResult("@2012-01-01.convertsToDateTime()") must beTrue
      booleanResult("@2012-01-01T10:00.convertsToDateTime()") must beTrue
      booleanResult("'2012-01-01T10:00:00Z'.convertsToDateTime()") must beTrue
      booleanResult("'not-a-date-time'.convertsToDateTime()") must beFalse
      booleanResult("true.convertsToDateTime()") must beFalse
    }

    "implement convertsToQuantity with and without a target unit" in {
      booleanResult("1.convertsToQuantity()") must beTrue
      booleanResult("true.convertsToQuantity()") must beTrue
      booleanResult("(1 'mg').convertsToQuantity()") must beTrue
      booleanResult("'4 days'.convertsToQuantity()") must beTrue
      booleanResult("'3'.convertsToQuantity()") must beTrue
      booleanResult("'not a quantity'.convertsToQuantity()") must beFalse
      evaluator.evaluate("'3'.toQuantity()", JNull) mustEqual
        Seq(FhirPathQuantity(FhirPathNumber(3), "1"))

      booleanResult("(1 'mg').convertsToQuantity('mg')") must beTrue
      booleanResult("(1 'mg').convertsToQuantity('g')") must beFalse
      booleanResult("1.convertsToQuantity('1')") must beTrue
      booleanResult("1.convertsToQuantity('mg')") must beFalse
      booleanResult("'4 days'.convertsToQuantity('d')") must beTrue
    }

    "implement convertsToString" in {
      booleanResult("3.convertsToString()") must beTrue
      booleanResult("true.convertsToString()") must beTrue
      booleanResult("@2012-01-01.convertsToString()") must beTrue
      booleanResult("@T10:30.convertsToString()") must beTrue
      booleanResult("(1 'mg').convertsToString()") must beTrue

      val input = JObject("value" -> JObject("nested" -> JString("x")))
      evaluator.evaluateBoolean("value.convertsToString()", input) mustEqual Seq(false)
    }

    "implement convertsToTime" in {
      booleanResult("@T10:30.convertsToTime()") must beTrue
      booleanResult("'10:30'.convertsToTime()") must beTrue
      booleanResult("'10:30:00.125+03:00'.convertsToTime()") must beTrue
      booleanResult("'not-a-time'.convertsToTime()") must beFalse
      booleanResult("1.convertsToTime()") must beFalse
    }

    "return empty for empty input" in {
      Seq(
        "convertsToBoolean()",
        "convertsToInteger()",
        "convertsToDate()",
        "convertsToDateTime()",
        "convertsToQuantity()",
        "convertsToQuantity('mg')",
        "convertsToString()",
        "convertsToTime()"
      ).foreach { functionCall =>
        evaluator.evaluate(s"{}.$functionCall", JNull) must beEmpty
      }
      ok
    }

    "reject multi-item input" in {
      Seq(
        "(true | false).convertsToBoolean()",
        "(1 | 2).convertsToInteger()",
        "(@2012-01-01 | @2013-01-01).convertsToDate()",
        "(@2012-01-01T10:00 | @2013-01-01T10:00).convertsToDateTime()",
        "(1 'mg' | 2 'mg').convertsToQuantity()",
        "(1 'mg' | 2 'mg').convertsToQuantity('mg')",
        "('a' | 'b').convertsToString()",
        "(@T10:00 | @T11:00).convertsToTime()"
      ).foreach { expression =>
        evaluator.evaluate(expression, JNull) must throwA[FhirPathException]
      }
      ok
    }
  }

  "FHIRPath N1 toTime" should {

    "convert strings and preserve an offset" in {
      evaluator.evaluate("'10:30'.toTime()", JNull) mustEqual
        Seq(FhirPathTime(LocalTime.of(10, 30), None))

      evaluator.evaluate("'10:30:00.125+03:00'.toTime()", JNull) mustEqual
        Seq(FhirPathTime(LocalTime.of(10, 30, 0, 125000000), Some(ZoneOffset.ofHours(3))))
    }

    "return an existing Time unchanged" in {
      evaluator.evaluate("@T10:30.toTime()", JNull) mustEqual
        Seq(FhirPathTime(LocalTime.of(10, 30), None))
    }

    "return empty for invalid, unsupported, and empty input" in {
      evaluator.evaluate("'not-a-time'.toTime()", JNull) must beEmpty
      evaluator.evaluate("1.toTime()", JNull) must beEmpty
      evaluator.evaluate("{}.toTime()", JNull) must beEmpty
    }

    "reject multi-item input" in {
      evaluator.evaluate("(@T10:00 | @T11:00).toTime()", JNull) must throwA[FhirPathException]
    }
  }

  "FHIRPath N1 value conversions" should {

    "return empty when a numeric string cannot be converted" in {
      evaluator.evaluate("'not-an-integer'.toInteger()", JNull) must beEmpty
      evaluator.evaluate("'not-a-decimal'.toDecimal()", JNull) must beEmpty
    }

    "convert Boolean values to strings without embedding literal delimiters" in {
      evaluator.evaluateString("true.toString()", JNull) mustEqual Seq("true")
      evaluator.evaluateString("false.toString()", JNull) mustEqual Seq("false")
    }
  }
}
