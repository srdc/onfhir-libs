package io.onfhir.validation

import io.onfhir.api.FHIR_ROOT_URL_FOR_DEFINITIONS
import io.onfhir.api.Resource
import io.onfhir.api.model.{FhirCanonicalReference, FhirLiteralReference, FhirLogicalReference}
import io.onfhir.api.service.IFhirTerminologyService
import io.onfhir.api.validation.{ConstraintKeys, IExternalFhirReferenceResolver}
import io.onfhir.config.{FhirServerConfig, ResourceConf, TerminologyServiceConf}
import org.junit.runner.RunWith
import org.json4s.JsonAST.JObject
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.{Duration, DurationInt}

@RunWith(classOf[JUnitRunner])
class FhirValidatorTest extends Specification {
  import ValidationTestFixtures._

  private val baseProfileUrl = s"$FHIR_ROOT_URL_FOR_DEFINITIONS/StructureDefinition/TestResource"
  private val derivedProfileUrl = "http://example.org/fhir/StructureDefinition/DerivedTestResource"
  private val valueSetUrl = "http://example.org/fhir/ValueSet/status"

  private val baseProfile = profile(
    url = baseProfileUrl,
    elementRestrictions = Seq(element("status", Map(
      ConstraintKeys.MIN -> CardinalityMinRestriction(1),
      ConstraintKeys.DATATYPE -> TypeRestriction(Seq("code" -> Nil))
    ), profileDefinedIn = baseProfileUrl))
  )

  private val derivedProfile = profile(
    url = derivedProfileUrl,
    baseUrl = Some(baseProfileUrl -> None),
    elementRestrictions = Seq(element("category", Map(
      ConstraintKeys.MIN -> CardinalityMinRestriction(1),
      ConstraintKeys.DATATYPE -> TypeRestriction(Seq("code" -> Nil))
    ), profileDefinedIn = derivedProfileUrl))
  )

  "FhirValidator" should {
    "validate against the base profile inferred from resourceType" in {
      val validator = FhirValidator(config(Seq(baseProfile)))

      awaitResult(validator.validateResource(resource("""{"resourceType":"TestResource","status":"active"}"""))) must beEmpty
    }

    "validate known meta profiles and return a warning for unknown claims" in {
      val validator = FhirValidator(config(Seq(baseProfile, derivedProfile)))
      val issues = awaitResult(validator.validateResource(resource(
        s"""{"resourceType":"TestResource","status":"active","meta":{"profile":["$derivedProfileUrl","http://example.org/unknown-profile"]}}}"""
      )))

      issues.map(_.expression.head) must contain("category")
      issues.map(_.severity) must contain("warning")
    }

    "avoid validating a base profile twice when a selected derived profile already includes it" in {
      val validator = FhirValidator(config(Seq(baseProfile, derivedProfile)))
      val issues = awaitResult(validator.validateResourceAgainstProfiles(
        resource("""{"resourceType":"TestResource"}"""),
        Seq(baseProfileUrl, derivedProfileUrl)
      ))

      issues.count(_.expression.head == "status") mustEqual 1
      issues.count(_.expression.head == "category") mustEqual 1
    }

    "report missing resourceType and an unknown explicitly selected profile as validation issues" in {
      val validator = FhirValidator(config(Seq(baseProfile)))

      val missingType = awaitResult(validator.validateResource(resource("""{"status":"active"}""")))
      val unknownProfile = awaitResult(validator.validateResourceAgainstProfile(
        resource("""{"resourceType":"TestResource","status":"active"}"""),
        "http://example.org/fhir/StructureDefinition/Unknown"
      ))

      missingType.head.expression mustEqual Seq("resourceType")
      missingType.head.severity mustEqual "error"
      unknownProfile.head.severity mustEqual "error"
      unknownProfile.head.code mustEqual "not-supported"
    }

    "combine configured local ValueSets with routed external terminology services" in {
      val calls = new AtomicInteger(0)
      val externalService = new TestTerminologyService(calls)
      val externalOnlyConfig = config(Seq(baseProfile))
      externalOnlyConfig.valueSetRestrictions = null
      val validator = FhirValidator(
        externalOnlyConfig,
        Seq(TerminologyServiceConf("external", 1.second, Map(valueSetUrl -> None)) -> externalService)
      )

      validator.terminologyValidator.isValueSetSupported(valueSetUrl, Some("1")) must beTrue
      validator.terminologyValidator.validateCodeAgainstValueSet(valueSetUrl, Some("1"), Some("http://example.org/system"), "external") must beTrue
      calls.get() mustEqual 1
    }

    "recursively validate nested resources through the facade bridge" in {
      val resourceProfileUrl = s"$FHIR_ROOT_URL_FOR_DEFINITIONS/StructureDefinition/Resource"
      val recursiveBaseProfile = baseProfile.copy(elementRestrictions = baseProfile.elementRestrictions :+ element("child", Map(
        ConstraintKeys.DATATYPE -> TypeRestriction(Seq("Resource" -> Nil))
      ), profileDefinedIn = baseProfileUrl))
      val resourceProfile = profile(url = resourceProfileUrl, resourceType = "Resource")
      val validator = FhirValidator(config(
        Seq(recursiveBaseProfile, resourceProfile),
        resourceTypes = Set("TestResource", "Resource"),
        complexTypes = Set("Resource")
      ))

      val issues = awaitResult(validator.validateResource(resource(
        """{"resourceType":"TestResource","status":"active","child":{"resourceType":"TestResource"}}"""
      )))

      issues.map(_.expression.head) must contain("child.status")
    }

    "validate every present Bundle entry resource" in {
      val bundleProfileUrl = s"$FHIR_ROOT_URL_FOR_DEFINITIONS/StructureDefinition/Bundle"
      val bundleEntryProfileUrl = s"$FHIR_ROOT_URL_FOR_DEFINITIONS/StructureDefinition/BundleEntry"
      val resourceProfileUrl = s"$FHIR_ROOT_URL_FOR_DEFINITIONS/StructureDefinition/Resource"
      val bundleProfile = profile(
        url = bundleProfileUrl,
        resourceType = "Bundle",
        elementRestrictions = Seq(element("entry", Map(
          ConstraintKeys.ARRAY -> ArrayRestriction(),
          ConstraintKeys.DATATYPE -> TypeRestriction(Seq("BundleEntry" -> Nil))
        ), profileDefinedIn = bundleProfileUrl))
      )
      val bundleEntryProfile = profile(
        url = bundleEntryProfileUrl,
        resourceType = "BundleEntry",
        elementRestrictions = Seq(element("resource", Map(
          ConstraintKeys.DATATYPE -> TypeRestriction(Seq("Resource" -> Nil))
        ), profileDefinedIn = bundleEntryProfileUrl))
      )
      val resourceProfile = profile(url = resourceProfileUrl, resourceType = "Resource")
      val validator = FhirValidator(config(
        Seq(baseProfile, bundleProfile, bundleEntryProfile, resourceProfile),
        resourceTypes = Set("Bundle", "BundleEntry", "Resource", "TestResource"),
        complexTypes = Set("BundleEntry", "Resource")
      ))

      val issues = awaitResult(validator.validateResource(resource(
        """{"resourceType":"Bundle","entry":[{"fullUrl":"urn:uuid:1","resource":{"resourceType":"TestResource"}}]}"""
      )))

      issues.map(_.expression.head) must contain("entry[0].resource.status")
    }

    "resolve enforced external and contained references without exposing Bundle context" in {
      val targetProfileUrl = "http://example.org/fhir/StructureDefinition/TargetResource"
      val referenceProfileUrl = s"$FHIR_ROOT_URL_FOR_DEFINITIONS/StructureDefinition/Reference"
      val referenceProfile = profile(
        url = referenceProfileUrl,
        resourceType = "Reference",
        elementRestrictions = Seq(element("reference", Map(
          ConstraintKeys.DATATYPE -> TypeRestriction(Seq("string" -> Nil))
        ), profileDefinedIn = referenceProfileUrl))
      )
      val targetProfile = profile(url = targetProfileUrl, resourceType = "TargetResource")
      val sourceProfile = baseProfile.copy(elementRestrictions = baseProfile.elementRestrictions :+ element("subject", Map(
        ConstraintKeys.DATATYPE -> TypeRestriction(Seq("Reference" -> Nil)),
        ConstraintKeys.REFERENCE_TARGET -> ReferenceRestrictions(
          referenceDataTypes = Set("Reference"),
          targetProfiles = Set(targetProfileUrl),
          versioning = None,
          aggregationMode = Set.empty
        )
      ), profileDefinedIn = baseProfileUrl))
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
        "TestResource" -> ResourceConf(resource = "TestResource", referencePolicies = Set("literal", "enforced"))
      )

      val external = new TestExternalReferenceResolver(Some(resource(
        s"""{"resourceType":"TargetResource","id":"external","meta":{"profile":["$targetProfileUrl"]}}"""
      )))
      val externalValidator = FhirValidator(serverConfig, externalReferenceResolver = Some(external))
      val externalIssues = awaitResult(externalValidator.validateResource(resource(
        """{"resourceType":"TestResource","status":"active","subject":{"reference":"TargetResource/external"}}"""
      )))
      val containedValidator = FhirValidator(serverConfig)
      val containedIssues = awaitResult(containedValidator.validateResource(resource(
        s"""{
          "resourceType":"TestResource",
          "status":"active",
          "contained":[{"resourceType":"TargetResource","id":"contained","meta":{"profile":["$targetProfileUrl"]}}],
          "subject":{"reference":"#contained"}
        }"""
      )))

      externalIssues must beEmpty
      containedIssues.exists(_.diagnostics.exists(_.contains("Referenced resource #contained does not exist"))) must beFalse
      external.literalReferences mustEqual Seq(FhirLiteralReference(None, "TargetResource", "external", None))
    }
  }

  private final class TestTerminologyService(calls: AtomicInteger) extends IFhirTerminologyService {
    override def getTimeout: Duration = 1.second

    override def validateCode(url: String, valueSetVersion: Option[String], code: String, system: Option[String], systemVersion: Option[String], display: Option[String]): Future[JObject] = {
      calls.incrementAndGet()
      Future.successful(resource("""{"resourceType":"Parameters","parameter":[{"name":"result","valueBoolean":true}]}"""))
    }

    override def lookup(code: String, system: String, version: Option[String], date: Option[String], displayLanguage: Option[String], properties: Seq[String]): Future[Option[JObject]] = unsupported
    override def lookup(code: String, system: String): Future[Option[JObject]] = unsupported
    override def lookup(coding: JObject, date: Option[String], displayLanguage: Option[String], properties: Seq[String]): Future[Option[JObject]] = unsupported
    override def lookup(coding: JObject): Future[Option[JObject]] = unsupported

    override def translate(code: String, system: String, conceptMapUrl: String, version: Option[String], conceptMapVersion: Option[String], reverse: Boolean): Future[JObject] = unsupported
    override def translate(code: String, system: String, conceptMapUrl: String): Future[JObject] = unsupported
    override def translate(codingOrCodeableConcept: JObject, conceptMapUrl: String, conceptMapVersion: Option[String], reverse: Boolean): Future[JObject] = unsupported
    override def translate(codingOrCodeableConcept: JObject, conceptMapUrl: String): Future[JObject] = unsupported
    override def translate(code: String, system: String, source: Option[String], target: Option[String], version: Option[String], reverse: Boolean): Future[JObject] = unsupported
    override def translate(code: String, system: String, source: Option[String], target: Option[String]): Future[JObject] = unsupported
    override def translate(codingOrCodeableConcept: JObject, source: Option[String], target: Option[String], reverse: Boolean): Future[JObject] = unsupported
    override def translate(codingOrCodeableConcept: JObject, source: Option[String], target: Option[String]): Future[JObject] = unsupported

    override def expandWithId(id: String, filter: Option[String], offset: Option[Long], count: Option[Long]): Future[JObject] = unsupported
    override def expand(url: String, version: Option[String], filter: Option[String], offset: Option[Long], count: Option[Long]): Future[JObject] = unsupported
    override def expandWithValueSet(valueSet: Resource, offset: Option[Long], count: Option[Long]): Future[JObject] = unsupported

    private def unsupported[T]: Future[T] = Future.failed(new UnsupportedOperationException("Not used by this test"))
  }

  private final class TestExternalReferenceResolver(result: Option[Resource]) extends IExternalFhirReferenceResolver {
    var literalReferences: Seq[FhirLiteralReference] = Nil

    override def resolveLiteral(reference: FhirLiteralReference): Future[Option[Resource]] = {
      literalReferences = literalReferences :+ reference
      Future.successful(result)
    }

    override def resolveCanonical(reference: FhirCanonicalReference): Future[Option[Resource]] = Future.successful(None)

    override def resolveLogical(reference: FhirLogicalReference): Future[Option[Resource]] = Future.successful(None)
  }
}
