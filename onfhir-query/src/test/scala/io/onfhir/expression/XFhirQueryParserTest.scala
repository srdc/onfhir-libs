package io.onfhir.expression

import io.onfhir.api.FHIR_PARAMETER_TYPES
import io.onfhir.api.model.Parameter
import io.onfhir.config.{FhirSearchHandling, FhirServerConfig, SearchParameterConf}
import io.onfhir.path.FhirPathEvaluator
import org.json4s.JsonAST._
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class XFhirQueryParserTest extends Specification {
  sequential

  private val fhirServerConfig = new FhirServerConfig("R4")
  fhirServerConfig.FHIR_RESULT_PARAMETERS = Nil
  fhirServerConfig.FHIR_SPECIAL_PARAMETERS = Nil
  fhirServerConfig.commonQueryParameters = Map.empty
  fhirServerConfig.resourceQueryParameters = Map(
    "Observation" -> Seq(
      searchParameter("subject", FHIR_PARAMETER_TYPES.REFERENCE, targets = Seq("Patient")),
      searchParameter("code", FHIR_PARAMETER_TYPES.TOKEN),
      searchParameter("status", FHIR_PARAMETER_TYPES.TOKEN),
      searchParameter("value-quantity", FHIR_PARAMETER_TYPES.QUANTITY),
      searchParameter("date", FHIR_PARAMETER_TYPES.DATE),
      searchParameter("value-number", FHIR_PARAMETER_TYPES.NUMBER),
      searchParameter("note", FHIR_PARAMETER_TYPES.STRING),
      searchParameter("source", FHIR_PARAMETER_TYPES.URI)
    ).map(parameter => parameter.pname -> parameter).toMap
  )

  private val parser = new XFhirQueryParser(
    fhirServerConfig,
    FhirSearchHandling.Strict,
    FhirPathEvaluator()
  )

  private def searchParameter(
      name: String,
      parameterType: String,
      targets: Seq[String] = Nil): SearchParameterConf =
    SearchParameterConf(
      url = s"http://example.org/SearchParameter/$name",
      pname = name,
      ptype = parameterType,
      paths = Seq(name),
      targets = targets
    )

  private def getValue(parameters: List[Parameter], name: String): Option[String] =
    parameters.find(_.name == name).map(_.valuePrefixList.map(_._2).mkString(","))

  private def getAllValues(parameters: List[Parameter], name: String): List[String] =
    parameters.filter(_.name == name).flatMap(_.valuePrefixList.map(_._2))

  "XFhirQueryParser" should {
    "resolve reference values from strings" in {
      val context = Map("refs" -> JArray(List(JString("Patient/1"), JString("Patient/2"))))

      val parameters = parser.parseXFhirQuery("Observation", "subject={{%refs}}", context)

      getValue(parameters, "subject") must beSome("Patient/1,Patient/2")
    }

    "resolve reference values from Reference objects" in {
      val context = Map("refs" -> JArray(List(
        JObject("reference" -> JString("Patient/11")),
        JObject("reference" -> JString("Patient/22"))
      )))

      val parameters = parser.parseXFhirQuery("Observation", "subject={{%refs}}", context)

      getValue(parameters, "subject") must beSome("Patient/11,Patient/22")
    }

    "resolve a token from a string" in {
      val parameters = parser.parseXFhirQuery(
        "Observation",
        "code={{%code}}&status=final",
        Map("code" -> JString("4548-4"))
      )

      getValue(parameters, "code") must beSome("4548-4")
      getValue(parameters, "status") must beSome("final")
    }

    "resolve a token from a Coding object" in {
      val coding = JObject(
        "system" -> JString("http://loinc.org"),
        "code" -> JString("4548-4")
      )

      val parameters = parser.parseXFhirQuery(
        "Observation",
        "code={{%coding}}",
        Map("coding" -> coding)
      )

      getValue(parameters, "code") must beSome("http://loinc.org|4548-4")
    }

    "resolve every Coding in a CodeableConcept" in {
      val concept = JObject("coding" -> JArray(List(
        JObject("system" -> JString("http://loinc.org"), "code" -> JString("4548-4")),
        JObject("system" -> JString("http://snomed.info/sct"), "code" -> JString("43396009"))
      )))

      val parameters = parser.parseXFhirQuery(
        "Observation",
        "code={{%concept}}",
        Map("concept" -> concept)
      )

      getValue(parameters, "code") must beSome(
        "http://loinc.org|4548-4,http://snomed.info/sct|43396009"
      )
    }

    "resolve token values from a string collection" in {
      val tokens = JArray(List(
        JString("http://loinc.org|4548-4"),
        JString("4575-5")
      ))

      val parameters = parser.parseXFhirQuery(
        "Observation",
        "code={{%tokens}}",
        Map("tokens" -> tokens)
      )

      getValue(parameters, "code") must beSome("http://loinc.org|4548-4,4575-5")
    }

    "resolve a quantity literal using a UCUM search token" in {
      val parameters = parser.parseXFhirQuery(
        "Observation",
        "value-quantity={{4.5 'mg'}}"
      )

      getValue(parameters, "value-quantity") must beSome(
        "4.5|http://unitsofmeasure.org|mg"
      )
    }

    "resolve a Quantity object" in {
      val quantity = JObject(
        "value" -> JDecimal(BigDecimal("4.5")),
        "system" -> JString("http://unitsofmeasure.org"),
        "code" -> JString("mg")
      )

      val parameters = parser.parseXFhirQuery(
        "Observation",
        "value-quantity={{%quantity}}",
        Map("quantity" -> quantity)
      )

      getValue(parameters, "value-quantity") must beSome(
        "4.5|http://unitsofmeasure.org|mg"
      )
    }

    "reject a Quantity object without a numeric value" in {
      val quantity = JObject("unit" -> JString("mg"))

      parser.parseXFhirQuery(
        "Observation",
        "value-quantity={{%quantity}}",
        Map("quantity" -> quantity)
      ) must throwA[FhirExpressionException]
    }

    "resolve today as a date search value" in {
      val parameters = parser.parseXFhirQuery("Observation", "date={{today()}}")

      getValue(parameters, "date") must beSome.which(_.matches("\\d{4}-\\d{2}-\\d{2}"))
    }

    "resolve a number and remove unnecessary trailing zeroes" in {
      val parameters = parser.parseXFhirQuery("Observation", "value-number={{5.0}}")

      getValue(parameters, "value-number") must beSome("5")
    }

    "reject a string result for a number parameter" in {
      parser.parseXFhirQuery(
        "Observation",
        "value-number={{'5'}}"
      ) must throwA[FhirExpressionException]
    }

    "resolve a string parameter from a string result" in {
      val parameters = parser.parseXFhirQuery("Observation", "note={{'alpha'}}")

      getValue(parameters, "note") must beSome("alpha")
    }

    "reject a numeric result for a string parameter" in {
      parser.parseXFhirQuery("Observation", "note={{5}}") must throwA[FhirExpressionException]
    }

    "resolve a URI parameter from a string result" in {
      val parameters = parser.parseXFhirQuery(
        "Observation",
        "source={{'http://example.org/source'}}"
      )

      getValue(parameters, "source") must beSome("http://example.org/source")
    }

    "reject a string result for a date parameter" in {
      parser.parseXFhirQuery(
        "Observation",
        "date={{'2024-01-01'}}"
      ) must throwA[FhirExpressionException]
    }

    "preserve a date prefix around a placeholder" in {
      val parameters = parser.parseXFhirQuery("Observation", "date=gt{{today()}}")
      val date = parameters.find(_.name == "date")

      getValue(parameters, "date") must beSome.which(_.matches("\\d{4}-\\d{2}-\\d{2}"))
      date.flatMap(_.valuePrefixList.headOption.map(_._1)) must beSome("gt")
    }

    "preserve a quantity prefix around a placeholder" in {
      val parameters = parser.parseXFhirQuery(
        "Observation",
        "value-quantity=le{{4.5 'mg'}}"
      )
      val quantity = parameters.find(_.name == "value-quantity")

      getValue(parameters, "value-quantity") must beSome(
        "4.5|http://unitsofmeasure.org|mg"
      )
      quantity.flatMap(_.valuePrefixList.headOption.map(_._1)) must beSome("le")
    }

    "resolve placeholders and plain parameters together" in {
      val context = Map(
        "codes" -> JArray(List(JString("http://loinc.org|4548-4"), JString("4575-5"))),
        "refs" -> JArray(List(JString("Patient/1"), JString("Patient/2")))
      )

      val parameters = parser.parseXFhirQuery(
        "Observation",
        "code={{%codes}}&status=final&subject={{%refs}}",
        context
      )

      getValue(parameters, "code") must beSome("http://loinc.org|4548-4,4575-5")
      getValue(parameters, "status") must beSome("final")
      getValue(parameters, "subject") must beSome("Patient/1,Patient/2")
      parameters must haveSize(3)
    }

    "retain repeated parameter names as separate entries" in {
      val context = Map(
        "first" -> JString("http://loinc.org|4548-4"),
        "second" -> JString("4575-5")
      )

      val parameters = parser.parseXFhirQuery(
        "Observation",
        "code={{%first}}&code={{%second}}",
        context
      )

      getAllValues(parameters, "code") must contain(exactly(
        "http://loinc.org|4548-4",
        "4575-5"
      )).inOrder
    }

    "parse a query without placeholders unchanged" in {
      val parameters = parser.parseXFhirQuery(
        "Observation",
        "status=final&code=http://loinc.org|4548-4"
      )

      getValue(parameters, "status") must beSome("final")
      getValue(parameters, "code") must beSome("http://loinc.org|4548-4")
    }

    "validate query shape while retaining a placeholder" in {
      val parameters = parser.parseXFhirQueryShape(
        "Observation",
        "date=gt{{today()}}"
      )
      val date = parameters.find(_.name == "date")

      date.flatMap(_.valuePrefixList.headOption) must beSome("gt" -> "{{today()}}")
    }

    "reject invalid FHIRPath while validating query shape" in {
      parser.parseXFhirQueryShape(
        "Observation",
        "date={{today(}}"
      ) must throwA[FhirExpressionException]
    }

    "reject a search prefix that is invalid for the parameter type" in {
      parser.parseXFhirQueryShape(
        "Observation",
        "note=gt{{'alpha'}}"
      ) must throwA[FhirExpressionException]
    }

    "reject placeholders for composite parameters" in {
      val compositeConfig = searchParameter(
        "code-value",
        FHIR_PARAMETER_TYPES.COMPOSITE,
        targets = Seq("code", "value-quantity")
      )
      fhirServerConfig.resourceQueryParameters = fhirServerConfig.resourceQueryParameters.updated(
        "Observation",
        fhirServerConfig.resourceQueryParameters("Observation").updated(
          compositeConfig.pname,
          compositeConfig
        )
      )

      parser.parseXFhirQueryShape(
        "Observation",
        "code-value={{%value}}"
      ) must throwA[FhirExpressionException]

      parser.parseXFhirQuery(
        "Observation",
        "code-value={{%value}}",
        Map("value" -> JString("http://loinc.org|4548-4$4.5|mg"))
      ) must throwA[FhirExpressionException]
    }
  }
}
