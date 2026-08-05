package io.onfhir.path

import org.json4s.JsonAST.JNull
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class FhirPathN1StringFunctionsTest extends Specification {

  private val evaluator = FhirPathEvaluator()

  "FHIRPath N1 toChars" should {

    "return the input characters in order" in {
      evaluator.evaluateString("'abc'.toChars()", JNull) mustEqual Seq("a", "b", "c")
    }

    "treat a supplementary Unicode code point as one character" in {
      evaluator.evaluateString("'A😀B'.toChars()", JNull) mustEqual Seq("A", "😀", "B")
    }

    "return empty for an empty string or empty input collection" in {
      evaluator.evaluate("''.toChars()", JNull) must beEmpty
      evaluator.evaluate("{}.toChars()", JNull) must beEmpty
    }

    "reject non-string input" in {
      evaluator.evaluate("1.toChars()", JNull) must throwA[FhirPathException]
    }

    "reject multi-item input" in {
      evaluator.evaluate("('a' | 'b').toChars()", JNull) must throwA[FhirPathException]
    }
  }
}
