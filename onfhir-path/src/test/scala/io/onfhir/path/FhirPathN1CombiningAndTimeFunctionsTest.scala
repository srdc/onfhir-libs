package io.onfhir.path

import org.json4s.JsonAST.{JArray, JInt, JNull}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import java.time.{Duration, LocalTime}

@RunWith(classOf[JUnitRunner])
class FhirPathN1CombiningAndTimeFunctionsTest extends Specification {

  private val evaluator = FhirPathEvaluator()

  "FHIRPath N1 union" should {

    "merge collections and eliminate duplicate values" in {
      evaluator.evaluateNumerical("(1 | 2).union(2 | 3)", JNull) mustEqual Seq(1, 2, 3)
    }

    "eliminate duplicates already present in the input collection" in {
      val input = JArray(List(JInt(1), JInt(1), JInt(2)))
      evaluator.evaluateNumerical("union(2 | 3)", input) mustEqual Seq(1, 2, 3)
    }

    "handle an empty collection on either side" in {
      evaluator.evaluateString("('a' | 'b').union({})", JNull) mustEqual Seq("a", "b")
      evaluator.evaluateString("{}.union('a' | 'b')", JNull) mustEqual Seq("a", "b")
    }

    "produce the same result as the union operator" in {
      val functionResult = evaluator.evaluate("('a' | 'b').union('b' | 'c')", JNull)
      val operatorResult = evaluator.evaluate("('a' | 'b') | ('b' | 'c')", JNull)
      functionResult mustEqual operatorResult
    }
  }

  "FHIRPath N1 timeOfDay" should {

    "return a Time close to the system local time" in {
      val before = LocalTime.now()
      val result = evaluator.evaluate("timeOfDay()", JNull).head.asInstanceOf[FhirPathTime]
      val after = LocalTime.now()

      circularDifference(before, result.lt) must beLessThanOrEqualTo(Duration.ofSeconds(2))
      circularDifference(result.lt, after) must beLessThanOrEqualTo(Duration.ofSeconds(2))
      result.zone must beNone
    }

    "use millisecond precision" in {
      val result = evaluator.evaluate("timeOfDay()", JNull).head.asInstanceOf[FhirPathTime]
      result.lt.getNano % 1000000 mustEqual 0
    }

    "return the same value for repeated calls in one expression" in {
      evaluator.evaluateBoolean("timeOfDay() = timeOfDay()", JNull) mustEqual Seq(true)
    }

    "retain the evaluation timestamp in nested environment copies" in {
      evaluator.evaluateBoolean(
        "(1 | 2).select(timeOfDay()).distinct().count() = 1",
        JNull
      ) mustEqual Seq(true)
    }

    "share the same evaluation snapshot with now and today" in {
      evaluator.evaluateBoolean("now() = now()", JNull) mustEqual Seq(true)
      evaluator.evaluateBoolean("today() = now().toDate()", JNull) mustEqual Seq(true)
    }
  }

  private def circularDifference(first: LocalTime, second: LocalTime): Duration = {
    val nanosPerDay = Duration.ofDays(1).toNanos
    val direct = Math.abs(Duration.between(first, second).toNanos)
    Duration.ofNanos(Math.min(direct, nanosPerDay - direct))
  }
}
