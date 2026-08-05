package io.onfhir.template

import io.onfhir.expression.FhirExpressionException
import io.onfhir.path.FhirPathEvaluator
import org.json4s.JValue
import org.json4s.JsonAST.{JArray, JBool, JDouble, JNothing, JObject, JString}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

/**
 * Cardinality markers, JSON type preservation and the rules for placeholders embedded in strings
 */
@RunWith(classOf[JUnitRunner])
class FhirTemplatePlaceholderTest extends Specification with TemplateRenderSupport {
  sequential

  val twoCodings: JValue = JArray(List(
    JObject("system" -> JString("http://loinc.org"), "code" -> JString("15074-8")),
    JObject("system" -> JString("http://snomed.info/sct"), "code" -> JString("1234"))
  ))

  val observation: JValue = json(
    """{"resourceType":"Observation","id":"obs1","status":"final",
      |"effectiveDateTime":"2013-04-02T09:30:10+01:00",
      |"valueQuantity":{"value":6.3,"unit":"mmol/l","system":"http://unitsofmeasure.org","code":"mmol/L"},
      |"performer":[{"reference":"Practitioner/p1"},{"reference":"Practitioner/p2"}]}""".stripMargin)

  "A placeholder without a cardinality marker" should {

    "render the single result" in {
      render("""{"status":"{{ %code }}"}""", contextParams = Map("code" -> JString("final"))) \ "status" mustEqual
        JString("final")
    }

    "fail when the result is empty" in {
      val t = renderFailure("""{"status":"{{ %missing }}"}""")
      t must haveClass[FhirExpressionException]
      t.getMessage must contain("not marked as optional")
    }

    "fail when the result has multiple items" in {
      val t = renderFailure("""{"status":"{{ %codes }}"}""", contextParams = Map("codes" -> twoCodings))
      t must haveClass[FhirExpressionException]
      t.getMessage must contain("multiple results")
    }
  }

  "A placeholder marked with '?'" should {

    "render the single result" in {
      render("""{"issued":"{{? %issued }}"}""", contextParams = Map("issued" -> JString("2013-04-03"))) \ "issued" mustEqual
        JString("2013-04-03")
    }

    "remove the field when the result is empty" in {
      render("""{"status":"final","issued":"{{? %missing }}"}""") mustEqual json("""{"status":"final"}""")
    }

    "fail when the result has multiple items" in {
      val t = renderFailure("""{"status":"{{? %codes }}"}""", contextParams = Map("codes" -> twoCodings))
      t must haveClass[FhirExpressionException]
      t.getMessage must contain("multiple results")
    }
  }

  "A placeholder marked with '*'" should {

    "always render an array, even for a single result" in {
      render("""{"instantiatesUri":"{{* %uri }}"}""", contextParams = Map("uri" -> JString("http://a"))) \ "instantiatesUri" mustEqual
        JArray(List(JString("http://a")))
    }

    "render every result" in {
      render("""{"coding":"{{* %codes }}"}""", contextParams = Map("codes" -> twoCodings)) \ "coding" mustEqual twoCodings
    }

    "remove the field when the result is empty" in {
      render("""{"status":"final","category":"{{* %missing }}"}""") mustEqual json("""{"status":"final"}""")
    }
  }

  "A placeholder marked with '+'" should {

    "render every result" in {
      render("""{"coding":"{{+ %codes }}"}""", contextParams = Map("codes" -> twoCodings)) \ "coding" mustEqual twoCodings
    }

    "fail when the result is empty" in {
      val t = renderFailure("""{"coding":"{{+ %missing }}"}""")
      t must haveClass[FhirExpressionException]
      t.getMessage must contain("not marked as optional")
    }
  }

  "A complete-value placeholder" should {

    "preserve a JSON object result" in {
      val subject = JObject("reference" -> JString("Patient/p1"), "display" -> JString("P. van de Heuvel"))
      render("""{"subject":"{{ %subject }}"}""", contextParams = Map("subject" -> subject)) \ "subject" mustEqual subject
    }

    "preserve a decimal result" in {
      val result = render("""{"valueQuantity":{"value":"{{ %v }}","unit":"mmol/l"}}""", contextParams = Map("v" -> JDouble(7.2)))
      FhirPathEvaluator().evaluateNumerical("valueQuantity.value", result).map(_.toDouble) mustEqual Seq(7.2)
    }

    "preserve a boolean result" in {
      render("""{"active":"{{ %flag }}"}""", contextParams = Map("flag" -> JBool(true))) \ "active" mustEqual JBool(true)
    }

    "evaluate FHIR Path against the given input" in {
      render("""{"basedOn":{"reference":"Observation/{{ Observation.id }}"}}""", observation) \ "basedOn" \ "reference" mustEqual
        JString("Observation/obs1")
    }
  }

  "A placeholder embedded in a string" should {

    "render a string result" in {
      render("""{"text":"Status is {{ %code }}."}""", contextParams = Map("code" -> JString("final"))) \ "text" mustEqual
        JString("Status is final.")
    }

    "render an integer result without a decimal fraction" in {
      render("""{"text":"count: {{ 2 + 3 }}"}""") \ "text" mustEqual JString("count: 5")
    }

    "render a decimal result" in {
      render("""{"text":"value: {{ Observation.valueQuantity.value }}"}""", observation) \ "text" mustEqual
        JString("value: 6.3")
    }

    "render a boolean result" in {
      render("""{"text":"final? {{ Observation.status = 'final' }}"}""", observation) \ "text" mustEqual
        JString("final? true")
    }

    "render a dateTime result without JSON quotes" in {
      render("""{"text":"on {{ Observation.effectiveDateTime }}"}""", observation) \ "text" must beLike {
        case JString(rendered) => rendered must startWith("on 2013-04-02T09:30:10")
      }
    }

    "fail when the result is empty" in {
      val t = renderFailure("""{"text":"value: {{ %missing }}"}""")
      t must haveClass[FhirExpressionException]
      t.getMessage must contain("multiple or empty result")
    }

    "fail when the result has multiple items" in {
      val t = renderFailure("""{"text":"performers: {{ Observation.performer.reference }}"}""", observation)
      t must haveClass[FhirExpressionException]
      t.getMessage must contain("multiple or empty result")
    }

    "fail when the result is a complex JSON object" in {
      val t = renderFailure("""{"text":"subject: {{ %subject }}"}""",
        contextParams = Map("subject" -> JObject("reference" -> JString("Patient/p1"))))
      t must haveClass[FhirExpressionException]
      t.getMessage must contain("complex JSON object")
    }

    "fail when a cardinality marker is used" in {
      val t = renderFailure("""{"text":"value: {{? %missing }}"}""")
      t must haveClass[FhirExpressionException]
      t.getMessage must contain("Cardinality markers")
    }
  }

  "The evaluation input" should {

    "be optional when every placeholder resolves from the context" in {
      render("""{"subject":{"reference":"{{ %ref }}"}}""", JNothing, Map("ref" -> JString("Patient/p1"))) \ "subject" \ "reference" mustEqual
        JString("Patient/p1")
    }
  }
}
