package io.onfhir.api.parsers

import io.onfhir.api.{FHIR_PARAMETER_TYPES}
import io.onfhir.config.{FhirSearchHandling, FhirServerConfig, SearchParameterConf}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class FhirQueryParserEncodingTest extends Specification {
  sequential

  private val config = new FhirServerConfig("R4")
  config.FHIR_RESULT_PARAMETERS = Nil
  config.FHIR_SPECIAL_PARAMETERS = Nil
  config.commonQueryParameters = Map.empty
  config.resourceQueryParameters = Map(
    "Patient" -> Map(
      "name" -> SearchParameterConf(
        url = "http://example.org/SearchParameter/name",
        pname = "name",
        ptype = FHIR_PARAMETER_TYPES.STRING,
        paths = Seq("name")
      ),
      "identifier" -> SearchParameterConf(
        url = "http://example.org/SearchParameter/identifier",
        pname = "identifier",
        ptype = FHIR_PARAMETER_TYPES.TOKEN,
        paths = Seq("identifier")
      )
    )
  )

  private val parser = new FhirQueryParser(config, FhirSearchHandling.Strict)

  "FhirQueryParser" should {
    "preserve duplicate query value order and decode encoded values" in {
      val (resourceType, parameters) =
        parser.parseQuery("Patient?name=Alice%2DBob&name=Carol%2ESmith")

      resourceType mustEqual "Patient"
      parameters.map(_.name) mustEqual List("name", "name")
      parameters.map(_.valuePrefixList) mustEqual List(
        Seq("" -> "Alice-Bob"),
        Seq("" -> "Carol.Smith")
      )
    }

    "reject a query whose path is not exactly one resource type" in {
      parser.parseQuery("base/Patient?name=Alice") must throwA[IllegalArgumentException]
    }

    "parse token values containing unencoded pipe characters" in {
      //FHIR token searches use raw '|' (system|code); a strict URI parser would reject these
      val (resourceType, parameters) =
        parser.parseQuery("Patient?identifier=http://example.org/mrn|12345")

      resourceType mustEqual "Patient"
      parameters.map(_.name) mustEqual List("identifier")
      parameters.head.valuePrefixList.head._2 must contain("|")
    }
  }
}
