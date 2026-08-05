package io.onfhir.path

import org.json4s.JsonAST.{JInt, JObject, JString}
import org.json4s.jackson.JsonMethods.parse
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class FhirPathReadmeExampleTest extends Specification {

  private val observation = parse(
    """
      |{
      |  "resourceType": "Observation",
      |  "id": "f001",
      |  "status": "final",
      |  "code": {
      |    "coding": [
      |      {"system": "http://loinc.org", "code": "15074-8"},
      |      {"system": "http://snomed.info/sct", "code": "4544556"}
      |    ]
      |  },
      |  "valueQuantity": {"value": 6.3, "unit": "mmol/L"}
      |}
      |""".stripMargin
  )

  "The onfhir-path README examples" should {

    "navigate, filter, and evaluate constraints" in {
      val evaluator = FhirPathEvaluator()

      evaluator.evaluateString(
        "Observation.code.coding.code",
        observation
      ) mustEqual Seq("15074-8", "4544556")

      evaluator.evaluateString(
        "Observation.code.coding.where(system = %loinc).code",
        observation
      ) mustEqual Seq("15074-8")

      evaluator.satisfies(
        "Observation.code.coding.exists(code = '15074-8')",
        observation
      ) must beTrue
    }

    "evaluate FHIR choice types and configured variables" in {
      val evaluator = FhirPathEvaluator()
        .withEnvironmentVariable("targetCode", JString("15074-8"))

      evaluator.evaluateOptionalNumerical(
        "Observation.value.ofType(Quantity).value",
        observation
      ) must beSome(BigDecimal("6.3"))

      evaluator.evaluateString(
        "Observation.code.coding.where(code = %targetCode).code",
        observation
      ) mustEqual Seq("15074-8")
    }

    "use bundled utility functions" in {
      val evaluator = FhirPathEvaluator().withDefaultFunctionLibraries()

      evaluator.evaluateAndReturnJson(
        "utl:createFhirReference('Observation', id)",
        observation
      ) must beSome(JObject("reference" -> JString("Observation/f001")))

      evaluator.evaluateString(
        "'1+1+2'.utl:split('+')",
        observation
      ) mustEqual Seq("1", "1", "2")
    }

    "evaluate ordinary JSON without FHIR choice-name handling" in {
      val row = JObject(
        "code" -> JString("C505"),
        "version" -> JInt(10)
      )
      val evaluator = new FhirPathEvaluator(isContentFhir = false)

      evaluator.evaluateString(
        "code.substring(0, 3) & '.' & code.substring(3)",
        row
      ) mustEqual Seq("C50.5")
    }

    "find concrete paths and extract structural restrictions" in {
      val evaluator = FhirPathEvaluator()

      evaluator.evaluateToFindPaths(
        "Observation.code.coding.system",
        observation
      ) mustEqual Seq(
        Seq("code" -> None, "coding" -> Some(0), "system" -> None),
        Seq("code" -> None, "coding" -> Some(1), "system" -> None)
      )

      evaluator.getPathItemsWithRestrictions(
        "ActivityDefinition.relatedArtifact.where(type='composed-of').resource"
      ) mustEqual Seq(
        "ActivityDefinition" -> Nil,
        "relatedArtifact" -> Seq("type" -> "composed-of"),
        "resource" -> Nil
      )
    }
  }
}
