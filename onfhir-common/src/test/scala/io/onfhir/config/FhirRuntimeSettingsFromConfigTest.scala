package io.onfhir.config

import com.typesafe.config.{Config, ConfigFactory}
import io.onfhir.exception.InitializationException
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

/**
 * Config-driven construction of the typed runtime settings.
 *
 * Every companion takes an already-scoped subtree and never loads global configuration itself,
 * so the tests here pass exactly the leaf the caller would pass.
 */
@RunWith(classOf[JUnitRunner])
class FhirRuntimeSettingsFromConfigTest extends Specification {

  private def config(body: String): Config = ConfigFactory.parseString(body)

  "FhirCapabilityDefaults.fromConfig" should {
    "read a fully populated fhir.default subtree" in {
      val defaults = FhirCapabilityDefaults.fromConfig(config(
        """
          |versioning = "no-version"
          |read-history = true
          |update-create = true
          |conditional-create = true
          |conditional-read = "modified-since"
          |conditional-update = true
          |conditional-delete = "multiple"
        """.stripMargin))

      defaults mustEqual FhirCapabilityDefaults(
        FhirVersioningPolicy.NoVersion,
        readHistory = true,
        updateCreate = true,
        conditionalCreate = true,
        FhirConditionalReadSupport.ModifiedSince,
        conditionalUpdate = true,
        FhirConditionalDeleteSupport.Multiple)
    }

    "yield exactly Standard for an empty subtree" in {
      FhirCapabilityDefaults.fromConfig(ConfigFactory.empty()) mustEqual FhirCapabilityDefaults.Standard
    }

    "override only the keys that are present" in {
      val defaults = FhirCapabilityDefaults.fromConfig(config("""update-create = true"""))

      defaults mustEqual FhirCapabilityDefaults.Standard.copy(updateCreate = true)
    }

    "fall back for an absent conditional-read" in {
      FhirCapabilityDefaults.fromConfig(config("""versioning = "versioned-update"""")).conditionalRead mustEqual
        FhirConditionalReadSupport.FullSupport
    }

    "reject an invalid value and name the allowed ones" in {
      (FhirCapabilityDefaults.fromConfig(config("""versioning = "sometimes"""")) must
        throwAn[InitializationException](message = "no-version, versioned, versioned-update")) and
        (FhirCapabilityDefaults.fromConfig(config("""conditional-delete = "some"""")) must
          throwAn[InitializationException](message = "not-supported, single, multiple"))
    }

    "reproduce the values the shipped fhir.default block produces today" in {
      // Mirrors repofyr-core/src/main/resources/application.conf, which omits conditional-read
      val defaults = FhirCapabilityDefaults.fromConfig(config(
        """
          |return-preference = representation
          |page-count = 20
          |versioning = "versioned"
          |search-total = "accurate"
          |pagination = "page"
          |read-history = false
          |update-create = true
          |conditional-create = false
          |conditional-update = false
          |conditional-delete = "not-supported"
        """.stripMargin))

      defaults mustEqual FhirCapabilityDefaults(
        FhirVersioningPolicy.Versioned,
        readHistory = false,
        updateCreate = true,
        conditionalCreate = false,
        FhirConditionalReadSupport.FullSupport,
        conditionalUpdate = false,
        FhirConditionalDeleteSupport.NotSupported)
    }
  }

  "FhirResultDefaults.fromConfig" should {
    "read a fully populated fhir.default subtree" in {
      val defaults = FhirResultDefaults.fromConfig(config(
        """
          |page-count = 20
          |pagination = "offset"
          |search-total = "estimate"
        """.stripMargin))

      defaults mustEqual FhirResultDefaults(20, FhirPaginationMode.Offset, FhirSearchTotalHandling.Estimate)
    }

    "yield exactly Standard for an empty subtree" in {
      FhirResultDefaults.fromConfig(ConfigFactory.empty()) mustEqual FhirResultDefaults.Standard
    }

    "override only the keys that are present" in {
      FhirResultDefaults.fromConfig(config("""page-count = 20""")) mustEqual
        FhirResultDefaults.Standard.copy(defaultPageSize = 20)
    }

    "reject an invalid value and name the allowed ones" in {
      FhirResultDefaults.fromConfig(config("""pagination = "cursor"""")) must
        throwAn[InitializationException](message = "page, offset")
    }

    "keep the constructor invariant for a negative page count" in {
      FhirResultDefaults.fromConfig(config("""page-count = -1""")) must throwAn[InitializationException]
    }
  }

  "FhirRequestDefaults.fromConfig" should {
    "read both keys from the fhir.default subtree" in {
      val defaults = FhirRequestDefaults.fromConfig(config(
        """
          |search-handling = lenient
          |return-preference = minimal
        """.stripMargin))

      defaults mustEqual FhirRequestDefaults(FhirSearchHandling.Lenient, FhirReturnPreference.Minimal)
    }

    "yield exactly Standard for an empty subtree" in {
      FhirRequestDefaults.fromConfig(ConfigFactory.empty()) mustEqual FhirRequestDefaults.Standard
    }

    "override only the keys that are present" in {
      FhirRequestDefaults.fromConfig(config("""search-handling = lenient""")) mustEqual
        FhirRequestDefaults.Standard.copy(searchHandling = FhirSearchHandling.Lenient)
    }

    "accept both spellings of search-handling" in {
      FhirRequestDefaults.fromConfig(config("""search-handling = lenient""")) mustEqual
        FhirRequestDefaults.fromConfig(config("""search-handling = "handling=lenient""""))
    }

    "accept both spellings of return-preference" in {
      FhirRequestDefaults.fromConfig(config("""return-preference = minimal""")) mustEqual
        FhirRequestDefaults.fromConfig(config("""return-preference = "return=minimal""""))
    }

    "reject an invalid value and name the allowed ones" in {
      (FhirRequestDefaults.fromConfig(config("""search-handling = eventually""")) must
        throwAn[InitializationException](message = "handling=strict, handling=lenient")) and
        (FhirRequestDefaults.fromConfig(config("""return-preference = everything""")) must
          throwAn[InitializationException](message = "return=minimal, return=representation, return=OperationOutcome"))
    }
  }

  "FhirSubscriptionSettings.fromConfig" should {
    "read a fully populated fhir.subscription subtree" in {
      val settings = FhirSubscriptionSettings.fromConfig(config(
        """
          |active = true
          |allowed-resources = ["Observation", "Patient"]
        """.stripMargin))

      settings mustEqual FhirSubscriptionSettings(active = true, Some(Set("Observation", "Patient")))
    }

    "yield exactly Standard for an empty subtree" in {
      FhirSubscriptionSettings.fromConfig(ConfigFactory.empty()) mustEqual FhirSubscriptionSettings.Standard
    }

    "override only the keys that are present" in {
      FhirSubscriptionSettings.fromConfig(config("""active = true""")) mustEqual
        FhirSubscriptionSettings.Standard.copy(active = true)
    }

    "distinguish an absent allowed-resources from a configured empty list" in {
      FhirSubscriptionSettings.fromConfig(ConfigFactory.empty()).allowedResources must beNone
      FhirSubscriptionSettings.fromConfig(config("""allowed-resources = []""")).allowedResources mustEqual
        Some(Set.empty[String])
    }
  }

  "the runtime settings presets" should {
    "match the historical code-level fallbacks" in {
      FhirResultDefaults.Standard mustEqual
        FhirResultDefaults(50, FhirPaginationMode.Page, FhirSearchTotalHandling.Accurate)
      FhirRequestDefaults.Standard mustEqual
        FhirRequestDefaults(FhirSearchHandling.Strict, FhirReturnPreference.Representation)
      FhirSubscriptionSettings.Standard mustEqual
        FhirSubscriptionSettings(active = false, None)
    }
  }

  "the lenient configuration parsers" should {
    "leave fromCode strict for the bare token" in {
      (FhirSearchHandling.fromCode("strict") must throwAn[InitializationException]) and
        (FhirReturnPreference.fromCode("representation") must throwAn[InitializationException])
    }

    "map both spellings onto the same value" in {
      (FhirSearchHandling.fromConfigValue("strict") mustEqual FhirSearchHandling.Strict) and
        (FhirSearchHandling.fromConfigValue("handling=strict") mustEqual FhirSearchHandling.Strict) and
        (FhirReturnPreference.fromConfigValue("OperationOutcome") mustEqual FhirReturnPreference.OperationOutcome) and
        (FhirReturnPreference.fromConfigValue("return=OperationOutcome") mustEqual FhirReturnPreference.OperationOutcome)
    }
  }
}
