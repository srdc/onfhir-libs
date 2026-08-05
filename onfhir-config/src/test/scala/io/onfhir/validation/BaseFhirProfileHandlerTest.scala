package io.onfhir.validation

import io.onfhir.api.FHIR_ROOT_URL_FOR_DEFINITIONS
import io.onfhir.api.validation.{ConstraintKeys, ElementRestrictions, FhirRestriction, ProfileRestrictions}
import io.onfhir.config.BaseFhirConfig
import org.junit.runner.RunWith
import org.slf4j.{Logger, LoggerFactory}
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

/**
 * Characterization tests for BaseFhirProfileHandler: resolution of element
 * paths against parsed profile restrictions (target types, choice elements,
 * paths continuing into complex data types, contentReference redirection,
 * and array cardinality detection).
 */
@RunWith(classOf[JUnitRunner])
class BaseFhirProfileHandlerTest extends Specification {

  private val baseResourceProfileUrl = s"$FHIR_ROOT_URL_FOR_DEFINITIONS/StructureDefinition/TestResource"
  private val codeableConceptProfileUrl = s"$FHIR_ROOT_URL_FOR_DEFINITIONS/StructureDefinition/CodeableConcept"
  private val targetProfileUrl = "http://example.org/fhir/StructureDefinition/TargetResource"

  private def element(
      path: String,
      restrictions: Map[Int, FhirRestriction] = Map.empty,
      contentReference: Option[String] = None
  ): (String, ElementRestrictions) =
    path -> ElementRestrictions(
      path = path,
      restrictions = restrictions,
      slicing = None,
      sliceName = None,
      contentReference = contentReference,
      profileDefinedIn = baseResourceProfileUrl
    )

  private def profile(url: String, elementRestrictions: Seq[(String, ElementRestrictions)]): ProfileRestrictions =
    ProfileRestrictions(
      url = url,
      version = None,
      id = None,
      baseUrl = None,
      resourceType = url.split('/').last,
      resourceName = None,
      resourceDescription = None,
      elementRestrictions = elementRestrictions,
      summaryElements = Set.empty,
      constraints = None,
      isAbstract = false
    )

  private val testResourceProfile = profile(baseResourceProfileUrl, Seq(
    element("status", Map(ConstraintKeys.DATATYPE -> TypeRestriction(Seq("code" -> Nil)))),
    element("tag", Map(
      ConstraintKeys.ARRAY -> ArrayRestriction(),
      ConstraintKeys.DATATYPE -> TypeRestriction(Seq("string" -> Nil))
    )),
    element("value[x]", Map(ConstraintKeys.DATATYPE -> TypeRestriction(Seq("Quantity" -> Nil, "string" -> Nil)))),
    element("subject", Map(
      ConstraintKeys.DATATYPE -> TypeRestriction(Seq("Reference" -> Nil)),
      ConstraintKeys.REFERENCE_TARGET -> ReferenceRestrictions(
        referenceDataTypes = Set("Reference"),
        targetProfiles = Set(targetProfileUrl),
        versioning = None,
        aggregationMode = Set.empty
      )
    )),
    element("code", Map(ConstraintKeys.DATATYPE -> TypeRestriction(Seq("CodeableConcept" -> Nil)))),
    element("item", Map.empty),
    element("item.text", Map(ConstraintKeys.DATATYPE -> TypeRestriction(Seq("string" -> Nil)))),
    element("nested", contentReference = Some("item"))
  ))

  private val codeableConceptProfile = profile(codeableConceptProfileUrl, Seq(
    element("coding", Map(
      ConstraintKeys.ARRAY -> ArrayRestriction(),
      ConstraintKeys.DATATYPE -> TypeRestriction(Seq("Coding" -> Nil))
    )),
    element("text", Map(ConstraintKeys.DATATYPE -> TypeRestriction(Seq("string" -> Nil))))
  ))

  private val fhirConfig: BaseFhirConfig = {
    val config = new BaseFhirConfig("test")
    config.fhirVersion = "test"
    config.profileRestrictions = Seq(testResourceProfile, codeableConceptProfile)
      .groupBy(_.url)
      .view
      .mapValues(_.map(p => p.version.getOrElse("latest") -> p).toMap)
      .toMap
    config.FHIR_RESOURCE_TYPES = Set("TestResource")
    config.FHIR_COMPLEX_TYPES = Set("Quantity", "CodeableConcept", "Coding", "Reference")
    config.FHIR_PRIMITIVE_TYPES = Set("code", "string")
    config
  }

  private val handler = new BaseFhirProfileHandler(fhirConfig) {
    override protected val logger: Logger = LoggerFactory.getLogger(this.getClass)
  }

  private val profileChain = fhirConfig.getBaseProfileChain("TestResource")

  "BaseFhirProfileHandler.findTargetTypeOfPath" should {
    "resolve a simple path to its element's target data type" in {
      handler.findTargetTypeOfPath("status", profileChain) mustEqual
        Seq(("status", "code", Nil, Set.empty[String]))
    }

    "resolve concrete choice paths against a choice ([x]) element for complex and primitive types" in {
      handler.findTargetTypeOfPath("valueQuantity", profileChain) mustEqual
        Seq(("valueQuantity", "Quantity", Nil, Set.empty[String]))
      handler.findTargetTypeOfPath("valueString", profileChain) mustEqual
        Seq(("valueString", "string", Nil, Set.empty[String]))
    }

    "expand a choice element root path to every alternative with concrete paths" in {
      handler.findTargetTypeOfPath("value", profileChain) mustEqual Seq(
        ("valueQuantity", "Quantity", Nil, Set.empty[String]),
        ("valueString", "string", Nil, Set.empty[String])
      )
    }

    "return reference target profiles for Reference-typed elements" in {
      handler.findTargetTypeOfPath("subject", profileChain) mustEqual
        Seq(("subject", "Reference", Nil, Set(targetProfileUrl)))
    }

    "resolve a path that continues inside a complex data type via its base profile" in {
      handler.findTargetTypeOfPath("code.text", profileChain) mustEqual
        Seq(("code.text", "string", Nil, Set.empty[String]))
    }

    "resolve a path through a contentReference element" in {
      handler.findTargetTypeOfPath("nested.text", profileChain) mustEqual
        Seq(("nested.text", "string", Nil, Set.empty[String]))
    }

    "return empty for a path with no element definition" in {
      handler.findTargetTypeOfPath("bogus", profileChain) must beEmpty
    }
  }

  "BaseFhirProfileHandler.findPathCardinality" should {
    "detect array and non-array elements on a direct path" in {
      handler.findPathCardinality("tag", profileChain) must beTrue
      handler.findPathCardinality("status", profileChain) must beFalse
    }

    "resolve the base profile chain from a resource type" in {
      handler.findPathCardinality("tag", "TestResource") must beTrue
    }

    "detect arrays on a path that continues inside a complex data type" in {
      handler.findPathCardinality("code.coding", profileChain) must beTrue
      handler.findPathCardinality("code.text", profileChain) must beFalse
    }
  }
}
