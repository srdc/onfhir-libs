package io.onfhir

import com.typesafe.config.ConfigFactory
import io.onfhir.api.Resource
import io.onfhir.api.util.FHIRUtil
import io.onfhir.config.{FhirCapabilityDefaults, FhirConditionalReadSupport, FhirEndpointSettings, FhirPaginationMode, FhirRequestDefaults, FhirResultDefaults, FhirSearchHandling, FhirSearchTotalHandling, FhirVersioningPolicy}
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

  "the config-driven construction example" should {
    "build the documented settings from the documented subtree" in {
      // ConfigFactory.load() in the README; parsed here so the example does not
      // depend on the test classpath carrying an application.conf
      val config = ConfigFactory.parseString(
        """
          |fhir.default {
          |  versioning = versioned
          |  conditional-read = full-support
          |  page-count = 20
          |  search-handling = strict
          |}
        """.stripMargin)
      val capabilities = FhirCapabilityDefaults.fromConfig(config.getConfig("fhir.default"))
      val results = FhirResultDefaults.fromConfig(config.getConfig("fhir.default"))
      val requests = FhirRequestDefaults.fromConfig(config.getConfig("fhir.default"))

      capabilities.versioning mustEqual FhirVersioningPolicy.Versioned
      capabilities.conditionalRead mustEqual FhirConditionalReadSupport.FullSupport
      results.defaultPageSize mustEqual 20
      requests.searchHandling mustEqual FhirSearchHandling.Strict

      // keys absent from the subtree fall back to the Standard preset
      results.paginationMode mustEqual FhirResultDefaults.Standard.paginationMode
      requests.returnPreference mustEqual FhirRequestDefaults.Standard.returnPreference
      FhirCapabilityDefaults.fromConfig(ConfigFactory.empty()) mustEqual FhirCapabilityDefaults.Standard
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
