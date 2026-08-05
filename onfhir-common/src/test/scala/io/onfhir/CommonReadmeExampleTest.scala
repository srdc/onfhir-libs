package io.onfhir

import io.onfhir.api.Resource
import io.onfhir.api.util.FHIRUtil
import io.onfhir.config.{FhirEndpointSettings, FhirPaginationMode, FhirResultDefaults, FhirSearchHandling, FhirSearchTotalHandling}
import io.onfhir.util.JsonFormatter._
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

/**
 * Executable versions of the examples in the module README
 */
@RunWith(classOf[JUnitRunner])
class CommonReadmeExampleTest extends Specification {

  "the typed runtime settings example" should {
    "construct the documented settings" in {
      val endpoint = FhirEndpointSettings("https://example.org/fhir")
      val handling = FhirSearchHandling.Strict          // Prefer: handling=strict
      val results = FhirResultDefaults(
        defaultPageSize = 20,
        paginationMode = FhirPaginationMode.Offset,
        totalHandling = FhirSearchTotalHandling.Accurate)

      endpoint.rootUrl mustEqual "https://example.org/fhir"
      handling.code mustEqual "handling=strict"
      results.defaultPageSize mustEqual 20
      FhirSearchHandling.fromCode(handling.code) mustEqual FhirSearchHandling.Strict
    }
  }

  "the FHIR JSON example" should {
    "parse and serialize a resource" in {
      val patient: Resource = """{"resourceType":"Patient","id":"p1"}""".parseJson
      val serialized: String = patient.toJson

      FHIRUtil.extractIdFromResource(patient) mustEqual "p1"
      serialized must contain("\"resourceType\":\"Patient\"")
    }
  }

  "the FHIRUtil example" should {
    "produce the documented values" in {
      val endpoint = FhirEndpointSettings("https://example.org/fhir")

      FHIRUtil.parseReferenceValue("http://example.org/fhir/Observation/1x2/_history/2") mustEqual
        ((Some("http://example.org/fhir"), "Observation", "1x2", Some("2")))

      FHIRUtil.parseTokenValue("http://loinc.org|500-5") mustEqual ((Some("http://loinc.org"), Some("500-5")))

      FHIRUtil.resourceLocation(endpoint, "Patient", "p1") mustEqual "https://example.org/fhir/Patient/p1"
    }
  }
}
