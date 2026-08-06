package io.onfhir.path

import org.json4s.JsonAST.{JNull, JString}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

/**
 * Contract for `%name` environment variable resolution.
 *
 * FHIRPath expressions reach this library from profile invariants, search
 * parameter definitions, subscription criteria and mapping templates, none of
 * which are necessarily written by the application operator. What an unknown
 * variable resolves to is therefore a trust boundary, not a convenience.
 */
@RunWith(classOf[JUnitRunner])
class FhirPathEnvironmentVariableTest extends Specification {

  private val evaluator = FhirPathEvaluator()

  /**
   * An OS environment variable that actually has a value on this host, whose
   * name is also a plain FHIRPath identifier so it can be written as `%name`.
   */
  private val osVariable: Option[(String, String)] =
    sys.env.find { case (name, value) => name.matches("[A-Za-z][A-Za-z0-9_]*") && value.nonEmpty }

  "FHIRPath environment variables" should {

    "resolve the fixed specification codes" in {
      evaluator.evaluateString("%ucum", JNull) mustEqual Seq("http://unitsofmeasure.org")
      evaluator.evaluateString("%sct", JNull) mustEqual Seq("http://snomed.info/sct")
      evaluator.evaluateString("%loinc", JNull) mustEqual Seq("http://loinc.org")
    }

    "resolve a variable supplied by the caller" in {
      evaluator
        .withEnvironmentVariable("targetCode", JString("15074-8"))
        .evaluateString("%targetCode", JNull) mustEqual Seq("15074-8")
    }

    "return empty for a variable nobody supplied" in {
      evaluator.evaluate("%noSuchVariableIsConfigured", JNull) must beEmpty
    }

    // Regression guard. Unknown variables used to fall through to sys.env, so
    // an expression carrying the name of a process secret - a database password
    // or an API token - resolved to its value and could surface it through
    // validation diagnostics or a mapped resource.
    "not fall back to the OS environment" in {
      osVariable must beSome

      val (name, value) = osVariable.get
      evaluator.evaluate(s"%$name", JNull) must beEmpty
      evaluator.evaluateString(s"%$name", JNull) must not(contain(value))
    }

    "let a caller-supplied variable shadow an OS variable of the same name" in {
      osVariable must beSome

      val (name, value) = osVariable.get
      evaluator
        .withEnvironmentVariable(name, JString("supplied"))
        .evaluateString(s"%$name", JNull) mustEqual Seq("supplied")

      "supplied" must not(beEqualTo(value))
    }
  }
}
