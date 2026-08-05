package io.onfhir.validation

import io.onfhir.api.FHIR_ROOT_URL_FOR_DEFINITIONS
import io.onfhir.api.validation.ConstraintKeys
import io.onfhir.config.{FhirServerConfig, ResourceConf}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import scala.concurrent.ExecutionContext.Implicits.global

@RunWith(classOf[JUnitRunner])
class ReferenceRestrictionsTest extends Specification {
  import ValidationTestFixtures._

  private val targetProfileUrl = "http://example.org/fhir/StructureDefinition/TargetResource"
  private val referenceProfileUrl = s"$FHIR_ROOT_URL_FOR_DEFINITIONS/StructureDefinition/Reference"
  private val referenceProfile = profile(
    url = referenceProfileUrl,
    resourceType = "Reference",
    elementRestrictions = Seq(element("reference", Map(
      ConstraintKeys.DATATYPE -> TypeRestriction(Seq("string" -> Nil))
    ), profileDefinedIn = referenceProfileUrl))
  )
  private val targetProfile = profile(url = targetProfileUrl, resourceType = "TargetResource")
  private val sourceProfile = profile(elementRestrictions = Seq(element("subject", Map(
    ConstraintKeys.DATATYPE -> TypeRestriction(Seq("Reference" -> Nil)),
    ConstraintKeys.REFERENCE_TARGET -> ReferenceRestrictions(
      referenceDataTypes = Set("Reference"),
      targetProfiles = Set(targetProfileUrl),
      versioning = None,
      aggregationMode = Set.empty
    )
  ))))

  private def enforcingConfig: FhirServerConfig = {
    val baseConfig = config(
      Seq(sourceProfile, targetProfile, referenceProfile),
      resourceTypes = Set("TestResource", "TargetResource"),
      complexTypes = Set("Reference")
    )
    val serverConfig = new FhirServerConfig("test")
    serverConfig.fhirVersion = baseConfig.fhirVersion
    serverConfig.profileRestrictions = baseConfig.profileRestrictions
    serverConfig.valueSetRestrictions = baseConfig.valueSetRestrictions
    serverConfig.FHIR_RESOURCE_TYPES = baseConfig.FHIR_RESOURCE_TYPES
    serverConfig.FHIR_COMPLEX_TYPES = baseConfig.FHIR_COMPLEX_TYPES
    serverConfig.FHIR_PRIMITIVE_TYPES = baseConfig.FHIR_PRIMITIVE_TYPES
    serverConfig.resourceConfigurations = Map(
      TestProfileUrl -> ResourceConf(resource = "TestResource", referencePolicies = Set("literal", "enforced"))
    )
    serverConfig
  }

  "ReferenceRestrictions" should {
    "accept a literal reference to the configured target resource type" in {
      val validator = FhirContentValidator(
        config(Seq(sourceProfile, targetProfile, referenceProfile), resourceTypes = Set("TestResource", "TargetResource"), complexTypes = Set("Reference")),
        TestProfileUrl
      )

      awaitResult(validator.validateComplexContent(resource(
        """{"resourceType":"TestResource","subject":{"reference":"TargetResource/1"}}"""
      ))) must beEmpty
    }

    "report a literal reference whose resource type is not allowed by the target profile" in {
      val validator = FhirContentValidator(
        config(Seq(sourceProfile, targetProfile, referenceProfile), resourceTypes = Set("TestResource", "TargetResource"), complexTypes = Set("Reference")),
        TestProfileUrl
      )

      val issues = awaitResult(validator.validateComplexContent(resource(
        """{"resourceType":"TestResource","subject":{"reference":"OtherResource/1"}}"""
      )))

      issues.map(_.expression.head) must contain("subject")
      issues.exists(_.diagnostics.exists(_.contains("Referenced type 'OtherResource'"))) must beTrue
    }

    "report an enforced reference that the resolver cannot find" in {
      val validator = FhirContentValidator(
        enforcingConfig,
        TestProfileUrl,
        new TestReferenceResolver(exists = (_, _) => false)
      )

      val issues = awaitResult(validator.validateComplexContent(resource(
        """{"resourceType":"TestResource","subject":{"reference":"TargetResource/1"}}"""
      )))

      issues.exists(_.diagnostics.exists(_.contains("Referenced resource TargetResource/1 does not exist"))) must beTrue
    }
  }
}
